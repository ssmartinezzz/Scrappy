#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ml_embeddings.py — Zero-shot fashion image embeddings (Marqo-FashionSigLIP)
============================================================================

Owns model load, the SQLite embedding cache, and (PR3b) prompt-based
zero-shot classification for the fashion-image-classification feature.

This slice (PR3a) implements only:
  - lazy singleton load of ``hf-hub:Marqo/marqo-fashionSigLIP`` via
    ``open_clip``, honoring the project's existing GPU pattern
    (``torch.cuda.is_available()`` device selection + ``_CUDA_LOCK``
    serialization, same as ``ml_pipeline.py``);
  - the ``image_embeddings`` SQLite cache (schema shipped in PR1);
  - ``embed_images()``, a cache-first embedding pipeline used by both the
    full-catalog backfill (PR3b) and the incremental scrape path (PR4).

Degradation is a hard requirement: model load failure, image download
failure, or any DB hiccup for a single URL must never raise out of
``embed_images`` — every failure is logged to stderr and treated as a
skip (``None`` in the result dict), leaving text-only classification
untouched upstream.

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
        conn = sqlite3.connect(db_path)
    except Exception as e:
        print(f"[ml_embeddings] DB connect failed for {db_path}: {e}", file=sys.stderr)
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
        conn.close()
    return results


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
