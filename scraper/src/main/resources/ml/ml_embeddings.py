#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ml_embeddings.py — Zero-shot fashion image embeddings (Marqo-FashionSigLIP)
============================================================================

Owns model load, the SQLite embedding cache, prompt-based zero-shot
classification, and the full-catalog backfill CLI for the
fashion-image-classification feature.

PR3a implemented (plus hardening deferred from its own judgment-day
review):
  - lazy singleton load of ``hf-hub:Marqo/marqo-fashionSigLIP`` via
    ``open_clip``, honoring the project's existing GPU pattern
    (``torch.cuda.is_available()`` device selection + ``_CUDA_LOCK``
    serialization, same as ``ml_pipeline.py``);
  - the ``image_embeddings`` SQLite cache (schema shipped in PR1);
  - ``embed_images()``, a cache-first embedding pipeline used by both the
    full-catalog backfill (this slice) and the incremental scrape path
    (PR4).

PR3b1 added:
  - ``PROMPTS``/``THRESHOLDS`` — the zero-shot label tables (English
    prompts internally, Spanish labels only ever emitted);
  - ``classify()`` — per-signal cosine similarity + margin-gated
    abstention against a lazily-computed, cached set of prompt text
    embeddings (reuses ``_load_model``'s singleton model);
  - ``dominant_color()`` — a Pillow pixel-histogram color signal that is
    deliberately independent of the SigLIP model.

This slice (PR3b2) adds:
  - ``backfill()`` — the CLI entrypoint the repurposed "Construir índice
    visual" button (PR5/PR6) launches on a background thread: embeds +
    classifies every active product missing a cached embedding (or all,
    if ``force``), persists the additive visual-attribute columns, and
    streams JSON progress lines to stdout for ``PythonRunner`` to parse.

Degradation is a hard requirement: model load failure, image download
failure, or any DB hiccup for a single URL/row must never raise out of
``embed_images``, ``classify()``, or ``backfill()`` — every failure is
logged to stderr and treated as a skip, leaving text-only classification
untouched upstream.

Heavy imports (``torch``, ``open_clip``, ``PIL``, ``numpy``) are performed
lazily inside functions so this module stays importable on machines where
the installer's optional image-classification step did not run.
"""
import os
import sys
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


# ─── DB connection (psycopg2 over DATABASE_URL) ──────────────────────────────
#
# Batch 2 (decouple-services-postgres, design D4): the SQLite `db_path`
# argv/hint is gone — every DB access goes through a fresh psycopg2
# connection built from the `DATABASE_URL` env var, set by PythonRunner.java
# (design D5) on every subprocess ProcessBuilder. `psycopg2` is imported
# lazily here (not at module top-level) so this module stays importable on a
# machine where the optional image-classification/DB deps aren't installed,
# same lazy-import discipline as torch/open_clip/PIL below.


def _get_connection():
    """Open a new psycopg2 connection using the `DATABASE_URL` env var.

    Raises (never degrades on its own — callers are responsible for
    catching, exactly like the old `sqlite3.connect(db_path)` call sites
    behaved) when `psycopg2` isn't installed, `DATABASE_URL` is unset, or the
    connection itself fails.
    """
    import psycopg2

    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise RuntimeError("DATABASE_URL no está configurado")
    return psycopg2.connect(dsn)


# ─── HF_HOME provisioning ─────────────────────────────────────────────────────


def _models_root():
    """Resolve the models-cache root directory from `SCRAPER_MODELS_ROOT`
    (design D5), falling back to a local `_models` dir for manual/standalone
    runs where the env var isn't set (mirrors ml_pipeline.py/ml_train.py's
    own fallback)."""
    root = os.environ.get("SCRAPER_MODELS_ROOT")
    return Path(root) if root else Path("_models")


def _default_hf_home():
    """Compute the installer-warmed weights cache directory for this install.

    Mirrors ``load_trained_models()`` in ml_pipeline.py and PythonRunner.java's
    `SCRAPER_MODELS_ROOT`-derived `HF_HOME` (design D5) — same
    `<models_root>/marqo` shape, no longer derived from a DB file path.
    """
    return _models_root() / "marqo"


def _ensure_hf_home():
    """Set HF_HOME to the installer-pinned path, unless already set.

    The Java side (PR5, updated by design D5) sets HF_HOME explicitly for
    the subprocess env; this default only matters for manual/standalone runs
    of this module.
    """
    if os.environ.get("HF_HOME"):
        return
    os.environ["HF_HOME"] = str(_default_hf_home())


# ─── Model loader (lazy singleton) ───────────────────────────────────────────


def _load_model():
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
            _ensure_hf_home()
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


# ─── Postgres cache I/O (image_embeddings table, bytea column) ──────────────
#
# Batch 2 (design D2/D4): `embedding` is now `bytea` (was SQLite `BLOB`).
# psycopg2 returns a `bytes`/`memoryview`-like buffer for `bytea` columns —
# wrapped in `bytes(...)` below so `np.frombuffer` always sees a real
# `bytes` object regardless of the exact buffer type psycopg2 hands back.


def get_cached(conn, url, model_version):
    """Return a cached embedding (float32 numpy.ndarray) for url+model_version.

    Returns ``None`` on cache miss OR when the cached row's `model_version`
    does not match (a version bump is treated as a miss, per spec).
    """
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT embedding, dim, model_version FROM image_embeddings WHERE url = %s",
            (url,),
        )
        row = cur.fetchone()
    finally:
        cur.close()
    if row is None:
        return None
    blob, dim, cached_version = row
    blob = bytes(blob)
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
    """Insert or upsert one embedding row keyed by image URL.

    `embedding` is stored as a float32 little-endian `numpy.tobytes()`
    `bytea` value (per design's cache schema decision). Uses
    `INSERT ... ON CONFLICT (url) DO UPDATE` (Postgres equivalent of the old
    SQLite `INSERT OR REPLACE`) so a URL re-processed under a new
    `model_version` overwrites the old row (the cache key is the URL, never
    the version).
    """
    import psycopg2
    import numpy as np

    blob = np.asarray(embedding, dtype="<f4").tobytes()
    cur = conn.cursor()
    try:
        cur.execute(
            """
            INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (url) DO UPDATE SET
                embedding = EXCLUDED.embedding,
                dim = EXCLUDED.dim,
                model_version = EXCLUDED.model_version,
                computed_at = EXCLUDED.computed_at
            """,
            (url, psycopg2.Binary(blob), dim, model_version, datetime.now(timezone.utc).isoformat()),
        )
        conn.commit()
    finally:
        cur.close()


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


def embed_images(urls, model_version=MODEL_VERSION, preloaded_images=None):
    """Cache-first embedding computation for a list of image URLs.

    Returns ``{url: numpy.ndarray | None}``. ``None`` marks a degraded/
    skipped URL (blank URL, download failure, or model unavailable) —
    callers MUST treat ``None`` as "no visual signal for this URL",
    never as an error to propagate.

    ``preloaded_images`` is an optional ``{url: PIL.Image | None}`` map. When
    a URL is a cache miss and is present as a key there, its preloaded
    entry is used INSTEAD of downloading — a non-``None`` entry is reused
    directly (lets a caller that also needs the raw image for something
    else, e.g. `backfill()`'s color extraction, download it exactly once:
    deferred from PR3b1 judgment-day review, "backfill loop double-
    downloads each image"), while an explicit ``None`` entry means the
    caller already tried and failed to download that URL — it is NOT
    re-attempted here (a URL simply absent from the map, e.g. for callers
    that don't preload at all, is downloaded normally; deferred from
    PR3b2 judgment-day round 2, "failed-download re-download").

    Connects to Postgres via `DATABASE_URL` (design D4) — no more SQLite
    `db_path`/WAL tuning; Postgres MVCC already lets this run share the
    table with an in-progress scrape/cron write without lock contention.
    """
    results = {}
    try:
        conn = _get_connection()
    except Exception as e:
        print(f"[ml_embeddings] DB connect failed: {e}", file=sys.stderr)
        return {url: None for url in urls}

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
                model, preprocess = _load_model()
                model_load_tried = True

            if model is None:
                results[url] = None
                continue

            try:
                if preloaded_images is not None and url in preloaded_images:
                    preloaded = preloaded_images[url]
                    if preloaded is None:
                        # Caller already attempted (and failed) to download
                        # this URL — an explicit `None` entry is a skip, NOT
                        # "not preloaded", so it must not be re-downloaded.
                        results[url] = None
                        continue
                    image = preloaded
                else:
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
    # PR4 (T4's PROMPTS-expansion scope): expanded from PR3b1's 13 coarse
    # top-level labels to the FINE canonical Spanish category strings
    # `CategoryClassifier.clasificar()` (scraper/src/main/java/ar/scraper/
    # aggregator/normalize/CategoryClassifier.java) can actually emit — the
    # text model's `_TEXT_LABEL_SET` (ml_pipeline.py) is built from exactly
    # those strings, and the stage-1b gate silently drops any image
    # `categoria` prediction NOT in that set (`ml_pipeline.py`: "if cat and
    # _TEXT_LABEL_SET and cat not in _TEXT_LABEL_SET: cat, conf = None,
    # 0.0"). Every label below is pinned 1:1 against a manually-maintained
    # copy of CategoryClassifier's output vocabulary in
    # ml-tests/test_ml_embeddings_classify.py — keep both in sync BY HAND.
    #
    # Deliberately EXCLUDES tech (Notebook/PC/Monitor/GPU/CPU/RAM/
    # Gabinete/Teclado/Mouse/Auricular/Webcam) and nutrition/supplement/
    # food categories (Creatina/Proteína/Colágeno/Magnesio/Pre-Workout/
    # BCAA/Vitaminas/Quemadores/Gainer/Suplemento/Alimentos and their
    # Barra/Pancake/Snack Proteico subcategories) — a fashion vision-
    # language model cannot meaningfully classify a GPU or a protein
    # shaker from a product photo. Also excludes Perfume (fragrance, not a
    # wearable garment) and Accesorio Deportivo (too broad/mixed a bucket:
    # knee braces, protein shakers, and bandages share no single visual
    # concept).
    "categoria": [
        # ── Calzado (footwear) ──────────────────────────────────────────
        ("a photo of running sneakers", "Zapatilla Running"),
        ("a photo of training or gym sneakers", "Zapatilla Entrenamiento"),
        ("a photo of skateboarding sneakers", "Zapatilla Skate"),
        ("a photo of casual urban sneakers", "Zapatilla Urbana"),
        ("a photo of retro or lifestyle sneakers", "Sneaker"),
        ("a photo of sneakers or athletic shoes", "Zapatilla"),
        ("a photo of ankle boots", "Botines"),
        ("a photo of combat boots or work boots", "Borcego"),
        ("a photo of house slippers", "Pantufla"),
        ("a photo of formal dress shoes", "Zapato"),
        ("a photo of loafers or moccasins", "Mocasin"),
        ("a photo of strappy sandals", "Sandalia"),
        ("a photo of flip flops or thong sandals", "Ojotas"),
        ("a photo of tall boots", "Botas"),
        # ── Ropa interior / baño ────────────────────────────────────────
        ("a photo of men's underwear or boxers", "Calzoncillos"),
        ("a photo of a bra or bralette", "Corpino"),
        ("a photo of a swimsuit or bikini", "Malla"),
        # ── Indumentaria superior ───────────────────────────────────────
        ("a photo of a puffer jacket", "Puffer"),
        ("a photo of a raincoat", "Piloto"),
        ("a photo of a formal suit", "Traje"),
        ("a photo of a blazer", "Saco"),
        ("a photo of a vest or gilet", "Chaleco"),
        ("a photo of a jacket or coat", "Campera"),
        ("a photo of a knit sweater", "Sweater"),
        ("a photo of a hoodie or sweatshirt", "Buzo"),
        ("a photo of a sports jersey", "Casaca"),
        ("a photo of a polo shirt", "Chomba"),
        ("a photo of a tank top or sleeveless top", "Musculosa"),
        ("a photo of a button-up collared shirt", "Camisa"),
        ("a photo of a t-shirt", "Remera"),
        # ── Indumentaria inferior ───────────────────────────────────────
        ("a photo of leggings", "Calza"),
        ("a photo of baggy or wide-leg pants", "Baggy"),
        ("a photo of blue jeans", "Jean"),
        ("a photo of jogger sweatpants", "Jogging"),
        ("a photo of bermuda shorts", "Bermuda"),
        ("a photo of shorts", "Short"),
        ("a photo of a dress", "Vestido"),
        ("a photo of a jumpsuit or romper", "Enterito"),
        ("a photo of a skirt", "Pollera"),
        ("a photo of dress pants or trousers", "Pantalón"),
        # ── Accesorios ───────────────────────────────────────────────────
        ("a photo of a wallet", "Billetera"),
        ("a photo of a fanny pack or waist bag", "Riñonera"),
        ("a photo of a backpack", "Mochila"),
        ("a photo of a handbag or tote bag", "Bolso"),
        ("a photo of a belt", "Cinturón"),
        ("a photo of a scarf", "Bufanda"),
        ("a photo of gloves", "Guantes"),
        ("a photo of sunglasses", "Lentes"),
        ("a photo of a beanie hat", "Gorro"),
        ("a photo of a baseball cap", "Gorra"),
        ("a photo of socks", "Medias"),
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


def _get_prompt_embeddings():
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

        model, _preprocess = _load_model()
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


def classify(embedding):
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

    prompt_embeddings = _get_prompt_embeddings()
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


# ─── Full-catalog backfill CLI ───────────────────────────────────────────────


def _pending_urls(conn, force, model_version):
    """Return `(url, imagen_url)` pairs for active products that still need
    an embedding computed under `model_version` (or every active product
    with an image, when `force`)."""
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT url, imagen_url FROM productos "
            "WHERE activo = 1 AND imagen_url IS NOT NULL AND imagen_url != ''"
        )
        rows = cur.fetchall()
    finally:
        cur.close()
    if force:
        return rows
    pending = []
    for url, imagen_url in rows:
        try:
            cached = get_cached(conn, imagen_url, model_version)
        except Exception as e:
            print(f"[ml_embeddings] backfill: cache lookup failed for {imagen_url}: {e}", file=sys.stderr)
            cached = None
        if cached is None:
            pending.append((url, imagen_url))
    return pending


def _persist_visual_attrs(conn, url, attrs, color):
    """Persist the four ADDITIVE visual-attribute columns (fit, estampado,
    escote, color_dominante) shipped in PR1. Deliberately does NOT touch
    `genero`/`categoria` on `productos` — those require the "text always
    wins" gate (spec: "visual-classification-rules"), which lives in the
    Java pipeline (`ml_pipeline.py` stage 1b + `MlEnricher`, PR4/PR5).
    This CLI only ever adds signal, never risks silently overriding a
    confident text classification.

    `color` may be ``None`` when no new color signal could be computed
    this run (the image failed to download, or `dominant_color()` raised
    unexpectedly) — `color_dominante` is then left UNCHANGED via
    `COALESCE` instead of being wiped to `""` (deferred from PR3b2
    judgment-day round 3 CONFIRMED finding: "color_dominante wipe on
    transient image failure" — a forced rebuild whose image download
    fails this run, but whose embedding is still cache-hit-retrievable,
    must not blank a previously-computed color).

    Does NOT commit — the caller commits once per chunk instead of once
    per row (deferred from PR3b2 judgment-day round 2: "_persist_
    visual_attrs commits per product row").
    """
    cur = conn.cursor()
    try:
        cur.execute(
            "UPDATE productos SET fit = %s, estampado = %s, escote = %s, "
            "color_dominante = COALESCE(%s, color_dominante) WHERE url = %s",
            (attrs.get("fit", ""), attrs.get("estampado", ""), attrs.get("escote", ""), color, url),
        )
    finally:
        cur.close()


def _emit_progress(pct, msg):
    """Write one JSON progress line to stdout for `PythonRunner` (Java) to
    parse, matching `ml_pipeline.py`'s existing `{phase, pct, msg}` shape
    under the new `"embedding"` phase name.

    Never raises: if the reading side of the pipe already closed (e.g. the
    Java process exited or its reader stopped), `print` can raise
    `BrokenPipeError`/`OSError` — that degrades silently here instead of
    propagating out of `backfill()` (deferred from PR3b1 judgment-day
    review; same never-raises contract as the rest of this module)."""
    import json

    try:
        print(json.dumps({"phase": "embedding", "pct": pct, "msg": msg}), flush=True)
    except (BrokenPipeError, OSError):
        pass


# Batches `embed_images()` calls in the backfill loop below so its internal
# SQLite connection is opened/closed once per chunk instead of once per
# product (deferred from PR3b1 judgment-day review: "thousands of SQLite
# connection open/close cycles"). Not tuned; a reasonable middle ground
# between fewer connection cycles and not holding too many downloaded
# images in memory at once.
_BACKFILL_CHUNK_SIZE = 20


def backfill(force=False, use_gpu=True):
    """Full-catalog visual-attribute backfill entrypoint (CLI + PR5's
    `PythonRunner.backfillEmbeddingsEnBackground`).

    For every active product missing a cached embedding (or all, when
    `force`): computes/reuses the embedding via `embed_images`, runs
    `classify()`, extracts `dominant_color()`, and persists the additive
    attribute columns. Streams `{"phase":"embedding","pct":N,"msg":...}`
    progress lines to stdout.

    Pending products are processed in chunks of `_BACKFILL_CHUNK_SIZE`:
    each DISTINCT image is downloaded exactly once per chunk (reused for
    both the embedding, on a cache miss, and `dominant_color()`), and
    `embed_images` is called once per chunk rather than once per product.
    One `conn.commit()` happens per chunk rather than once per row.

    If `classify()` degrades for a row (no embedding available, prompt
    embeddings unavailable, or an internal failure — detectable because a
    real classification run always sets `genero` to a real label or
    "unisex", never leaves it ""), that row's attributes are NOT
    persisted at all: existing fit/estampado/escote/color_dominante
    values are left untouched rather than wiped to "". This is what keeps
    a forced rebuild (`force=True`) safe when the model is unavailable —
    this CLI must only ever ADD signal, never remove it.

    Never raises: a DB-connect failure, a query failure, or any single
    row's processing failure degrades that unit (or the whole run, for a
    connect/query failure) to a no-op, logging to stderr instead —
    mirrors `embed_images`'s degradation contract (spec:
    "degradation-behavior").

    Connects to Postgres via `DATABASE_URL` (design D4) — no more
    SQLite `db_path` argument or WAL tuning.
    """
    if not use_gpu:
        # Force CPU-only for this process. Must be set before `_load_model`
        # ever imports torch — mirrors the Java side's existing
        # CUDA_VISIBLE_DEVICES convention for the subprocess env (PR5).
        os.environ["CUDA_VISIBLE_DEVICES"] = "-1"

    try:
        conn = _get_connection()
    except Exception as e:
        print(f"[ml_embeddings] backfill: DB connect failed: {e}", file=sys.stderr)
        _emit_progress(0, "error: no se pudo conectar a la base de datos")
        return

    try:
        pending = _pending_urls(conn, force, MODEL_VERSION)
    except Exception as e:
        print(f"[ml_embeddings] backfill: query failed: {e}", file=sys.stderr)
        _emit_progress(0, "error: no se pudieron leer productos")
        try:
            conn.close()
        except Exception:
            pass
        return

    total = len(pending)
    if total == 0:
        _emit_progress(100, "sin productos pendientes")
        try:
            conn.close()
        except Exception:
            pass
        return

    _emit_progress(0, f"iniciando backfill de {total} productos")
    processed = 0
    for chunk_start in range(0, total, _BACKFILL_CHUNK_SIZE):
        chunk = pending[chunk_start:chunk_start + _BACKFILL_CHUNK_SIZE]

        # Dedup: several products (pack/combo or color variants, etc.) can
        # share the same imagen_url — download and embed each DISTINCT URL
        # only once per chunk instead of once per product (deferred from
        # PR3b2 judgment-day round 2: "chunk_imagen_urls can contain
        # duplicate URLs"). `dict.fromkeys` preserves order.
        chunk_imagen_urls = list(dict.fromkeys(imagen_url for _url, imagen_url in chunk))

        # Download each image ONCE per chunk — reused below for both the
        # embedding (on a cache miss, via `preloaded_images`) and
        # `dominant_color()`, instead of downloading it a second time later
        # in this loop (deferred from PR3b1 judgment-day review: "backfill
        # loop double-downloads each image").
        images = {}
        for imagen_url in chunk_imagen_urls:
            try:
                images[imagen_url] = _download_image(imagen_url)
            except Exception as e:
                print(f"[ml_embeddings] backfill: image download failed for {imagen_url}: {e}", file=sys.stderr)
                images[imagen_url] = None

        embeddings = embed_images(
            chunk_imagen_urls,
            model_version=MODEL_VERSION,
            preloaded_images=images,
        )

        for url, imagen_url in chunk:
            try:
                embedding = embeddings.get(imagen_url)
                attrs = classify(embedding)

                if attrs.get("genero", "") == "":
                    # `classify()` returned its degraded/no-signal sentinel
                    # (embedding unavailable, prompt embeddings unavailable,
                    # or an internal failure) — `genero` is the one field a
                    # REAL classification run always overwrites (to a real
                    # label or "unisex"; see `classify()`'s own contract),
                    # so "" here means "no attempt could be made", never
                    # "every signal legitimately abstained". Skip
                    # persisting so a forced rebuild with the model down
                    # can't wipe existing fit/estampado/escote/
                    # color_dominante values back to "" (deferred from
                    # PR3b2 judgment-day round 2: "degraded-classify wipe"
                    # — this CLI must only ever ADD signal, never remove
                    # it, per its own docstring).
                    print(
                        f"[ml_embeddings] backfill: no visual signal for {url}, leaving existing attrs untouched",
                        file=sys.stderr,
                    )
                else:
                    # `None` (not "") means "no new color signal this run" —
                    # `_persist_visual_attrs` preserves the existing
                    # color_dominante instead of wiping it (deferred from
                    # PR3b2 judgment-day round 3: "color_dominante wipe on
                    # transient image failure" — a cache-hit embedding can
                    # still classify successfully even when THIS run's
                    # image download failed).
                    color = None
                    image = images.get(imagen_url)
                    if image is not None:
                        try:
                            color = dominant_color(image)
                        except Exception as e:
                            print(f"[ml_embeddings] backfill: color extraction failed for {imagen_url}: {e}", file=sys.stderr)

                    _persist_visual_attrs(conn, url, attrs, color)
            except Exception as e:
                # Never abort the whole run for one bad row — that product
                # simply keeps its text-only classification, same as today.
                print(f"[ml_embeddings] backfill: failed processing {url}: {e}", file=sys.stderr)

            processed += 1
            _emit_progress(int(processed * 100 / total), f"{processed}/{total} — {url}")

        # One commit per chunk instead of one per product row (deferred
        # from PR3b2 judgment-day round 2: "_persist_visual_attrs commits
        # per product row").
        try:
            conn.commit()
        except Exception as e:
            print(f"[ml_embeddings] backfill: commit failed for chunk: {e}", file=sys.stderr)

    try:
        conn.close()
    except Exception:
        pass
    _emit_progress(100, "backfill completo")


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "smoke":
        # Manual smoke check: attempts the REAL model load (network +
        # weights required) and prints OK/FAIL. Never run automatically
        # by pytest. Usage: python ml_embeddings.py smoke
        m, p = _load_model()
        if m is not None:
            print("OK: model loaded successfully")
            sys.exit(0)
        print("FAIL: model could not be loaded (see stderr above)")
        sys.exit(1)

    if len(sys.argv) >= 2 and sys.argv[1] == "backfill":
        # Usage: python ml_embeddings.py backfill [--force] [--no-gpu]
        # Batch 2 (design D4): no more positional `db_path` — the DSN comes
        # from the `DATABASE_URL` env var PythonRunner.java sets on the
        # subprocess. Only "--"-prefixed flags are recognized now.
        flags = sys.argv[2:]
        backfill(
            force="--force" in flags,
            use_gpu="--no-gpu" not in flags,
        )
        sys.exit(0)

    print("Usage: python ml_embeddings.py smoke|backfill [--force] [--no-gpu]", file=sys.stderr)
    sys.exit(2)
