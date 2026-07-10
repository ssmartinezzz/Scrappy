#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ml_embeddings.py — Zero-shot fashion image embeddings (Marqo-FashionSigLIP)
============================================================================

Owns model load, the SQLite embedding cache, and prompt-based zero-shot
classification for the fashion-image-classification feature. The
full-catalog backfill CLI that consumes these (``backfill()``) is a
separate slice, PR3b2, built on top of this branch.

PR3a implemented (plus hardening deferred from its own judgment-day
review):
  - lazy singleton load of ``hf-hub:Marqo/marqo-fashionSigLIP`` via
    ``open_clip``, honoring the project's existing GPU pattern
    (``torch.cuda.is_available()`` device selection + ``_CUDA_LOCK``
    serialization, same as ``ml_pipeline.py``);
  - the ``image_embeddings`` SQLite cache (schema shipped in PR1);
  - ``embed_images()``, a cache-first embedding pipeline used by both the
    full-catalog backfill (PR3b2) and the incremental scrape path (PR4).

This slice (PR3b1) adds:
  - ``PROMPTS``/``THRESHOLDS`` — the zero-shot label tables (English
    prompts internally, Spanish labels only ever emitted);
  - ``classify()`` — per-signal cosine similarity + margin-gated
    abstention against a lazily-computed, cached set of prompt text
    embeddings (reuses ``_load_model``'s singleton model);
  - ``dominant_color()`` — a Pillow pixel-histogram color signal that is
    deliberately independent of the SigLIP model.

Degradation is a hard requirement: model load failure, image download
failure, or any DB hiccup for a single URL must never raise out of
``embed_images`` or ``classify()`` — every failure is logged to stderr
and treated as a skip, leaving text-only classification untouched
upstream.

Heavy imports (``torch``, ``open_clip``, ``PIL``, ``numpy``) are performed
lazily inside functions so this module stays importable on machines where
the installer's optional image-classification step did not run.
"""
import os
import sys
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

# ─── Model identity ──────────────────────────────────────────────────────────

MODEL_ID = "hf-hub:Marqo/marqo-fashionSigLIP"
# Bumping this string invalidates every cached row (see image-embedding-cache
# spec: "Model version bump invalidates the cache").
MODEL_VERSION = "marqo-fashionSigLIP-v1"

# Serializes GPU forward passes across threads — mirrors ml_pipeline.py's
# `_CUDA_LOCK`. torch CUDA forwards are NOT thread-safe when called
# concurrently from multiple Python threads (see ml_pipeline.py for the
# native-crash history this avoids); only the transfer+forward step is
# serialized, downloads/decoding stay concurrent.
_CUDA_LOCK = threading.Lock()

# Lazy singleton state for the loaded model. `_model_load_attempted` is a
# separate flag from `_model` so a *failed* load is also cached — we never
# retry loading within the same process after the first attempt.
_model = None
_preprocess = None
_model_load_attempted = False
_model_lock = threading.Lock()


# ─── HF_HOME provisioning ─────────────────────────────────────────────────────


def _default_hf_home(db_path):
    """Compute the installer-warmed weights cache directory for this install.

    Mirrors ``load_trained_models()`` in ml_pipeline.py: the models directory
    lives next to ``scraper.db``, i.e. at the repo root / install root. This
    MUST match the `%ROOT%\\_models\\marqo` path pinned by
    INSTALAR_Y_CORRER.bat's step 3g so a runtime `hf-hub:` load hits the
    pre-warmed cache instead of re-downloading ~300MB.
    """
    return Path(db_path).resolve().parent / "_models" / "marqo"


def _ensure_hf_home(db_path):
    """Set HF_HOME to the installer-pinned path, unless already set.

    The Java side (PR5) sets HF_HOME explicitly for the subprocess env; this
    default only matters for manual/standalone runs of this module.
    """
    if os.environ.get("HF_HOME"):
        return
    os.environ["HF_HOME"] = str(_default_hf_home(db_path))


# ─── Model loader (lazy singleton) ───────────────────────────────────────────


def _load_model(db_path="scraper.db"):
    """Load (once) the Marqo-FashionSigLIP model via open_clip.

    Returns ``(model, preprocess)`` on success, ``(None, None)`` if the
    model/runtime could not be loaded (missing deps, missing weights,
    network failure at first-run download, etc). Never raises — this is
    the model-unavailable degradation path required by the spec.
    """
    global _model, _preprocess, _model_load_attempted
    if _model_load_attempted:
        return _model, _preprocess
    with _model_lock:
        if _model_load_attempted:
            return _model, _preprocess
        _model_load_attempted = True
        try:
            _ensure_hf_home(db_path)
            import torch
            import open_clip

            model, _, preprocess = open_clip.create_model_and_transforms(MODEL_ID)
            device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            model = model.to(device).eval()
            _model, _preprocess = model, preprocess
            print(
                f"[ml_embeddings] Model loaded ({MODEL_ID}, device={device})",
                file=sys.stderr,
            )
        except Exception as e:
            print(f"[ml_embeddings] Model unavailable: {e}", file=sys.stderr)
            _model, _preprocess = None, None
    return _model, _preprocess


# ─── SQLite cache I/O (image_embeddings table, schema from PR1) ─────────────


def get_cached(conn, url, model_version):
    """Return a cached embedding (float32 numpy.ndarray) for url+model_version.

    Returns ``None`` on cache miss OR when the cached row's `model_version`
    does not match (a version bump is treated as a miss, per spec).
    """
    row = conn.execute(
        "SELECT embedding, dim, model_version FROM image_embeddings WHERE url = ?",
        (url,),
    ).fetchone()
    if row is None:
        return None
    blob, dim, cached_version = row
    if cached_version != model_version:
        return None
    if len(blob) != dim * 4:
        # Defensive explicit check (deferred from PR3a judgment-day review):
        # a truncated/corrupted BLOB would otherwise only surface as a
        # ValueError from np.frombuffer below — validating the length up
        # front makes the degrade-to-miss path explicit and self-documenting.
        print(
            f"[ml_embeddings] Corrupted cache row for {url}: "
            f"blob length {len(blob)} != dim*4 ({dim * 4})",
            file=sys.stderr,
        )
        return None
    import numpy as np

    return np.frombuffer(blob, dtype="<f4", count=dim).copy()


def insert_cache(conn, url, embedding, dim, model_version):
    """Insert or replace one embedding row keyed by image URL.

    `embedding` is stored as a float32 little-endian `numpy.tobytes()` BLOB
    (per design's cache schema decision). Uses INSERT OR REPLACE so a
    URL re-processed under a new `model_version` overwrites the old row
    (the cache key is the URL, never the version).
    """
    import numpy as np

    blob = np.asarray(embedding, dtype="<f4").tobytes()
    conn.execute(
        """
        INSERT OR REPLACE INTO image_embeddings
            (url, embedding, dim, model_version, computed_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (url, blob, dim, model_version, datetime.now(timezone.utc).isoformat()),
    )
    conn.commit()


# ─── Image download + embedding compute ─────────────────────────────────────


def _download_image(url):
    """Download and decode an image URL as a PIL RGB image. Raises on failure
    — callers are responsible for catching and degrading gracefully."""
    import urllib.request
    from io import BytesIO
    from PIL import Image

    if url.startswith("//"):
        url = "https:" + url
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = urllib.request.urlopen(req, timeout=8).read()
    return Image.open(BytesIO(data)).convert("RGB")


def _compute_embedding(model, preprocess, image):
    """Compute a single L2-normalized float32 embedding for one PIL image."""
    import torch

    tensor = preprocess(image).unsqueeze(0)
    device = next(model.parameters()).device

    # Serialize GPU usage (see _CUDA_LOCK): mirrors ml_pipeline.py's
    # existing pattern to avoid concurrent CUDA forwards from multiple
    # threads. Download/decode above stays outside the lock.
    with _CUDA_LOCK:
        tensor = tensor.to(device)
        with torch.no_grad():
            features = model.encode_image(tensor)
            features = features / features.norm(dim=-1, keepdim=True)

    return features[0].cpu().numpy().astype("float32")


# ─── Public entrypoint ───────────────────────────────────────────────────────


def embed_images(urls, db_path="scraper.db", model_version=MODEL_VERSION):
    """Cache-first embedding computation for a list of image URLs.

    Returns ``{url: numpy.ndarray | None}``. ``None`` marks a degraded/
    skipped URL (blank URL, download failure, or model unavailable) —
    callers MUST treat ``None`` as "no visual signal for this URL",
    never as an error to propagate.
    """
    results = {}
    try:
        conn = sqlite3.connect(db_path, timeout=30)
    except Exception as e:
        print(f"[ml_embeddings] DB connect failed for {db_path}: {e}", file=sys.stderr)
        return {url: None for url in urls}

    try:
        # Best-effort concurrency setting (deferred from PR3a judgment-day
        # review): WAL lets the backfill CLI (PR3b) and an in-progress
        # scrape share scraper.db without lock contention. Not fatal if
        # unsupported (e.g. some network filesystems) — the `timeout=30`
        # above is the real degradation backstop either way.
        conn.execute("PRAGMA journal_mode=WAL")
    except Exception:
        pass

    try:
        model = preprocess = None
        model_load_tried = False
        for url in urls:
            if not url:
                results[url] = None
                continue

            try:
                cached = get_cached(conn, url, model_version)
            except Exception as e:
                print(f"[ml_embeddings] Cache lookup failed for {url}: {e}", file=sys.stderr)
                results[url] = None
                continue

            if cached is not None:
                results[url] = cached
                continue

            if not model_load_tried:
                model, preprocess = _load_model(db_path)
                model_load_tried = True

            if model is None:
                results[url] = None
                continue

            try:
                image = _download_image(url)
                embedding = _compute_embedding(model, preprocess, image)
                insert_cache(conn, url, embedding, embedding.shape[0], model_version)
                results[url] = embedding
            except Exception as e:
                print(f"[ml_embeddings] Failed processing {url}: {e}", file=sys.stderr)
                results[url] = None
    finally:
        # Guarded (deferred from PR3a judgment-day review): closing an
        # already-broken connection must never mask/replace whatever
        # exception (if any) is propagating, nor crash a degraded run.
        try:
            conn.close()
        except Exception:
            pass
    return results


# ─── Zero-shot prompts (English internal / Spanish output only) ─────────────
#
# PROMPTS is the single source of truth mapping each English zero-shot
# prompt to the Spanish label that is ever allowed to leave this module
# (spec: "visual-classification-rules" — Spanish-only output guarantee).
# THRESHOLDS is a per-signal (min cosine-similarity "prob", min top-vs-
# second margin) pair; below EITHER, the signal abstains. Exact wording
# and thresholds are an explicit open question in the design (empirical
# tuning deferred to spec/verify against a real sample) — these are a
# reasonable starting point, not a tuned final answer.
PROMPTS = {
    "categoria": [
        ("a photo of a t-shirt", "Remera"),
        ("a photo of a hoodie or sweatshirt", "Buzo"),
        ("a photo of a jacket or coat", "Campera"),
        ("a photo of dress pants or trousers", "Pantalón"),
        ("a photo of blue jeans", "Jean"),
        ("a photo of shorts", "Short"),
        ("a photo of a dress", "Vestido"),
        ("a photo of a skirt", "Pollera"),
        ("a photo of sneakers or athletic shoes", "Zapatillas"),
        ("a photo of boots", "Botines"),
        ("a photo of sandals or flip flops", "Sandalias"),
        ("a photo of a tank top or sleeveless top", "Musculosa"),
        ("a photo of a button-up collared shirt", "Camisa"),
    ],
    "fit": [
        ("a photo of an oversized, loose-fitting garment", "oversize"),
        ("a photo of a tight, slim-fit garment", "entallado"),
        ("a photo of a regular, standard-fit garment", "regular"),
    ],
    "estampado": [
        ("a photo of a garment with a printed graphic or pattern", "estampado"),
        ("a photo of a plain solid-color garment with no print", "liso"),
    ],
    "escote": [
        ("a photo of a garment with a round crew neckline", "cuello redondo"),
        ("a photo of a garment with a v-shaped neckline", "en v"),
        ("a photo of a hooded garment", "capucha"),
        ("a photo of a garment with a collar", "con cuello"),
    ],
    "genero": [
        ("a photo of menswear fashion", "hombre"),
        ("a photo of womenswear fashion", "mujer"),
    ],
}

# `genero` is the one signal that never abstains to "" — a below-threshold
# result there means "no strong gender cue", which is exactly what
# "unisex" already communicates (design decision #8: "genero
# (hombre/mujer→else unisex)"). Every other signal abstains to "".
THRESHOLDS = {
    "categoria": {"min_prob": 0.22, "min_margin": 0.02},
    "fit": {"min_prob": 0.20, "min_margin": 0.02},
    "estampado": {"min_prob": 0.20, "min_margin": 0.02},
    "escote": {"min_prob": 0.20, "min_margin": 0.02},
    "genero": {"min_prob": 0.20, "min_margin": 0.02},
}

# Lazy singleton cache of prompt TEXT embeddings, keyed by signal:
# {signal: [(spanish_label, text_embedding), ...]}. Computed at most once
# per process (same "attempted" sentinel pattern as `_load_model`) — a
# failure to compute is cached too, so we never retry mid-run.
_prompt_embeddings = None
_prompt_embeddings_attempted = False
_prompt_embeddings_lock = threading.Lock()


def _get_prompt_embeddings(db_path="scraper.db"):
    """Lazily compute + cache one text embedding per PROMPTS entry, using
    the SAME loaded model's text tower (`_load_model` singleton).

    Returns ``None`` when the model is unavailable or text encoding fails
    for any reason — callers MUST treat that as "no visual signal at
    all" (same degradation contract as `embed_images`), never an error.
    """
    global _prompt_embeddings, _prompt_embeddings_attempted
    if _prompt_embeddings_attempted:
        return _prompt_embeddings
    with _prompt_embeddings_lock:
        if _prompt_embeddings_attempted:
            return _prompt_embeddings
        _prompt_embeddings_attempted = True

        model, _preprocess = _load_model(db_path)
        if model is None:
            return None

        try:
            import torch
            import open_clip

            tokenizer = open_clip.get_tokenizer(MODEL_ID)
            device = next(model.parameters()).device
            computed = {}
            for signal, entries in PROMPTS.items():
                texts = [english for english, _label in entries]
                tokens = tokenizer(texts)
                with _CUDA_LOCK:
                    tokens = tokens.to(device)
                    with torch.no_grad():
                        features = model.encode_text(tokens)
                        features = features / features.norm(dim=-1, keepdim=True)
                    features = features.cpu().numpy().astype("float32")
                computed[signal] = [
                    (label, features[i]) for i, (_english, label) in enumerate(entries)
                ]
            _prompt_embeddings = computed
        except Exception as e:
            print(f"[ml_embeddings] Prompt embeddings unavailable: {e}", file=sys.stderr)
            _prompt_embeddings = None
    return _prompt_embeddings


def _cosine_top_and_margin(embedding, candidates):
    """Return ``(best_label, best_score, margin)`` for one signal.

    Both `embedding` and every candidate text embedding are already
    L2-normalized, so cosine similarity is a plain dot product. `margin`
    is best-minus-second-best (top-vs-second gate from design decision
    #5); a single-candidate signal gets an unbounded margin (-1.0 floor
    for the "second" score).
    """
    import numpy as np

    scores = [(label, float(np.dot(embedding, vec))) for label, vec in candidates]
    scores.sort(key=lambda pair: pair[1], reverse=True)
    best_label, best_score = scores[0]
    second_score = scores[1][1] if len(scores) > 1 else -1.0
    return best_label, best_score, best_score - second_score


def classify(embedding, db_path="scraper.db"):
    """Zero-shot classify one image embedding into Spanish visual-attribute
    signals, abstaining per-signal below threshold.

    Returns ``{categoria, fit, estampado, escote, genero, genImgConf,
    catMLConf}`` — every value is one of the Spanish labels in `PROMPTS`
    (or "" / "unisex" for an abstained signal), NEVER raw English prompt
    text (spec: "visual-classification-rules" Spanish-only guarantee).

    ``embedding`` is ``None`` when the image model was unavailable for
    this product (see `embed_images`'s degradation contract) — in that
    case every field stays empty and no classification is attempted at
    all, identical to today's no-image-model behavior (degradation-
    behavior spec: "Model unavailable").
    """
    empty = {
        "categoria": "",
        "fit": "",
        "estampado": "",
        "escote": "",
        "genero": "",
        "genImgConf": 0.0,
        "catMLConf": 0.0,
    }
    if embedding is None:
        return empty

    prompt_embeddings = _get_prompt_embeddings(db_path)
    if prompt_embeddings is None:
        return empty

    result = dict(empty)
    try:
        for signal in ("categoria", "fit", "estampado", "escote"):
            label, score, margin = _cosine_top_and_margin(embedding, prompt_embeddings[signal])
            th = THRESHOLDS[signal]
            if signal == "categoria":
                result["catMLConf"] = score
            if score >= th["min_prob"] and margin >= th["min_margin"]:
                result[signal] = label

        label, score, margin = _cosine_top_and_margin(embedding, prompt_embeddings["genero"])
        th = THRESHOLDS["genero"]
        result["genImgConf"] = score
        result["genero"] = label if (score >= th["min_prob"] and margin >= th["min_margin"]) else "unisex"
    except Exception as e:
        print(f"[ml_embeddings] classify() failed, degrading to text-only: {e}", file=sys.stderr)
        return empty

    return result


# ─── Dominant color (Pillow histogram, independent of SigLIP) ───────────────

# Fixed Spanish color palette (spec: "visual-classification-rules" — the
# color signal is a pixel property, not a zero-shot model prediction; see
# design decision #5). Reference RGBs are representative swatches, not
# gamut-exact — nearest-neighbor matching only needs relative distance.
COLOR_PALETTE = {
    "negro": (20, 20, 20),
    "blanco": (240, 240, 240),
    "gris": (128, 128, 128),
    "rojo": (200, 30, 30),
    "rosa": (230, 150, 180),
    "naranja": (230, 120, 30),
    "amarillo": (220, 200, 40),
    "verde": (40, 130, 60),
    "azul": (30, 80, 180),
    "celeste": (120, 180, 220),
    "violeta": (120, 60, 160),
    "marron": (110, 70, 40),
    "beige": (210, 190, 150),
}


def _closest_palette_color(rgb):
    best_name, best_dist = "", None
    for name, ref in COLOR_PALETTE.items():
        dist = sum((a - b) ** 2 for a, b in zip(rgb, ref))
        if best_dist is None or dist < best_dist:
            best_name, best_dist = name, dist
    return best_name


def dominant_color(image):
    """Return the closest named Spanish color for `image`'s dominant pixel
    color, via a plain Pillow histogram — deliberately NOT the SigLIP
    model (color is a pixel property, not a semantic one).

    `image` only needs to support the PIL Image API used here
    (`.convert`, `.resize`, `.getcolors`) — this function never imports
    PIL itself, so it stays testable without Pillow installed, same as
    the rest of this module's lazy-import discipline.

    Degrades to `""` (never raises) if the image can't be read or has no
    extractable pixel data.
    """
    try:
        small = image.convert("RGB").resize((32, 32))
        colors = small.getcolors(32 * 32) or []
    except Exception as e:
        print(f"[ml_embeddings] dominant_color failed: {e}", file=sys.stderr)
        return ""
    if not colors:
        return ""
    _, dominant_rgb = max(colors, key=lambda c: c[0])
    return _closest_palette_color(dominant_rgb)


if __name__ == "__main__":
    # Manual smoke check: attempts the REAL model load (network + weights
    # required) and prints OK/FAIL. Never run automatically by pytest.
    # Usage: python ml_embeddings.py smoke [db_path]
    if len(sys.argv) >= 2 and sys.argv[1] == "smoke":
        db_path_arg = sys.argv[2] if len(sys.argv) >= 3 else "scraper.db"
        m, p = _load_model(db_path_arg)
        if m is not None:
            print("OK: model loaded successfully")
            sys.exit(0)
        print("FAIL: model could not be loaded (see stderr above)")
        sys.exit(1)
    print("Usage: python ml_embeddings.py smoke [db_path]", file=sys.stderr)
    sys.exit(2)
