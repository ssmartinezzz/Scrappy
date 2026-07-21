# -*- coding: utf-8 -*-
"""
Tests for `ml_embeddings.py`: model-load singleton, `embed_images()`'s
cache-first pipeline, HF_HOME provisioning, and DB-failure degradation
paths.

Migrated (task 4.9, `decouple-services-postgres`) from the pre-Postgres
`ml-tests/py-batch4-pending/test_ml_embeddings.py`, which built a real
SQLite fixture and called the (now-removed) `db_path=...` keyword argument
on `embed_images`/`_load_model`/`_ensure_hf_home`. Since design D4/D5,
`ml_embeddings.py` connects via `_get_connection()` (psycopg2 over
`DATABASE_URL`) and derives HF_HOME from `SCRAPER_MODELS_ROOT`, not a
filesystem `db_path` — every test here monkeypatches `_get_connection`
to return the shared in-memory `FakeConnection` (`ml-tests/_pg_fakes.py`,
same fake-cursor pattern Batch 2's `test_ml_embeddings_postgres_cache.py`
established) instead of building a real SQLite file.

The `get_cached`/`insert_cache` bytea round-trip itself (cache hit/miss,
model-version mismatch, corrupted row, `ON CONFLICT` overwrite) is already
covered directly in `test_ml_embeddings_postgres_cache.py` — not repeated
here. This file focuses on the scenarios the old suite pinned that are
NOT covered there: the `embed_images()`-level pipeline (cache-first
short-circuit, preloaded-image handling, degradation on download/model/DB
failure), the model-load singleton, and HF_HOME provisioning.

Runs with NO network access and NO model weights present — every heavy
dependency (open_clip, torch, PIL) is monkeypatched at the module's own
lazy-import boundary (`_load_model`, `_download_image`, `_compute_embedding`).
"""
import numpy as np
import pytest

import ml_embeddings
from _pg_fakes import FakeConnection, seed_embedding


# ─── Fixtures ────────────────────────────────────────────────────────────────


@pytest.fixture(autouse=True)
def _reset_model_singleton():
    """Model-load singleton state is module-global; reset it around every
    test so one test's mocked model/failure doesn't leak into the next."""
    ml_embeddings._model = None
    ml_embeddings._preprocess = None
    ml_embeddings._model_load_attempted = False
    yield
    ml_embeddings._model = None
    ml_embeddings._preprocess = None
    ml_embeddings._model_load_attempted = False


@pytest.fixture
def fake_conn():
    return FakeConnection()


def _use_fake_conn(monkeypatch, conn):
    """Point `ml_embeddings._get_connection` at the given fake instead of a
    real psycopg2 connection — every DB access in this module goes through
    `_get_connection()` since the Batch 2 psycopg2/DATABASE_URL rewrite."""
    monkeypatch.setattr(ml_embeddings, "_get_connection", lambda: conn)


class _FakeModel:
    """Stand-in for an open_clip model; never actually used because
    `_compute_embedding` itself is monkeypatched in these tests."""
    pass


class _FakeCuda:
    @staticmethod
    def is_available():
        return False


class _FakeTorch:
    """Minimal stand-in for the `torch` module so `_load_model` can reach
    `open_clip.create_model_and_transforms` without the real (heavy,
    not installed in this test env) torch package."""

    cuda = _FakeCuda()

    @staticmethod
    def device(name):
        return name


# ─── embed_images: cache hit ─────────────────────────────────────────────────


def test_cache_hit_skips_download_and_inference(monkeypatch, fake_conn):
    url = "http://x/cached.jpg"
    seeded = np.array([0.5, 0.25, 0.75], dtype="<f4")
    seed_embedding(fake_conn, url, seeded.tobytes(), 3, ml_embeddings.MODEL_VERSION)
    _use_fake_conn(monkeypatch, fake_conn)

    def _boom_load():
        raise AssertionError("model should never be loaded on a cache hit")

    def _boom_download(*_a, **_kw):
        raise AssertionError("image should never be downloaded on a cache hit")

    monkeypatch.setattr(ml_embeddings, "_load_model", _boom_load)
    monkeypatch.setattr(ml_embeddings, "_download_image", _boom_download)

    results = ml_embeddings.embed_images([url])

    assert url in results
    np.testing.assert_array_almost_equal(results[url], seeded)


# ─── embed_images: cache miss ────────────────────────────────────────────────


def test_cache_miss_downloads_computes_and_inserts(monkeypatch, fake_conn):
    url = "http://x/new.jpg"
    computed = np.array([1.0, 2.0, 3.0, 4.0], dtype=np.float32)
    _use_fake_conn(monkeypatch, fake_conn)

    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (_FakeModel(), object()))
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda u: "fake-pil-image")
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: computed
    )

    results = ml_embeddings.embed_images([url])

    assert url in results
    np.testing.assert_array_almost_equal(results[url], computed)

    # Verify it was actually persisted, not just returned in-memory.
    cached = ml_embeddings.get_cached(fake_conn, url, ml_embeddings.MODEL_VERSION)
    assert cached is not None
    np.testing.assert_array_almost_equal(cached, computed)


def test_model_version_mismatch_treated_as_miss_and_recomputed(monkeypatch, fake_conn):
    url = "http://x/stale.jpg"
    seed_embedding(
        fake_conn, url, np.array([9.0, 9.0], dtype="<f4").tobytes(), 2, "old-version"
    )
    _use_fake_conn(monkeypatch, fake_conn)

    recomputed = np.array([1.0, 1.0], dtype=np.float32)
    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (_FakeModel(), object()))
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda u: "fake-pil-image")
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: recomputed
    )

    results = ml_embeddings.embed_images([url], model_version=ml_embeddings.MODEL_VERSION)

    np.testing.assert_array_almost_equal(results[url], recomputed)
    assert fake_conn._embeddings[url][2] == ml_embeddings.MODEL_VERSION


# ─── embed_images: preloaded_images ──────────────────────────────────────────


def test_preloaded_image_is_embedded_without_downloading(monkeypatch, fake_conn):
    """Regression: a non-None `preloaded_images[url]` entry must be used
    directly on a cache miss — `_download_image` must never be called for
    that URL."""
    url = "http://x/preloaded.jpg"
    computed = np.array([1.0, 2.0, 3.0], dtype=np.float32)
    fake_image = "already-downloaded-pil-image"
    _use_fake_conn(monkeypatch, fake_conn)

    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (_FakeModel(), object()))

    def _boom_download(*_a, **_kw):
        raise AssertionError("_download_image must not be called when a preloaded image is available")

    monkeypatch.setattr(ml_embeddings, "_download_image", _boom_download)
    monkeypatch.setattr(
        ml_embeddings,
        "_compute_embedding",
        lambda model, preprocess, image: computed if image == fake_image else (_ for _ in ()).throw(
            AssertionError("_compute_embedding received the wrong image")
        ),
    )

    results = ml_embeddings.embed_images([url], preloaded_images={url: fake_image})

    np.testing.assert_array_almost_equal(results[url], computed)
    cached = ml_embeddings.get_cached(fake_conn, url, ml_embeddings.MODEL_VERSION)
    np.testing.assert_array_almost_equal(cached, computed)


def test_explicit_none_preload_is_treated_as_a_failed_download_not_reattempted(monkeypatch, fake_conn):
    """Regression: an explicit `preloaded_images[url] = None` entry means
    "the caller already tried and failed to download this URL" and must
    NOT trigger a second `_download_image` call — a URL truly absent from
    the map is unaffected and still downloads normally."""
    url = "http://x/failed-download.jpg"
    other_url = "http://x/not-preloaded.jpg"
    computed = np.array([4.0, 5.0], dtype=np.float32)
    _use_fake_conn(monkeypatch, fake_conn)

    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (_FakeModel(), object()))

    download_calls = []

    def _tracking_download(u):
        download_calls.append(u)
        return "fake-pil-image"

    monkeypatch.setattr(ml_embeddings, "_download_image", _tracking_download)
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: computed
    )

    results = ml_embeddings.embed_images(
        [url, other_url],
        preloaded_images={url: None},  # explicit skip for `url` only
    )

    # `url` had an explicit failed-preload entry: never re-downloaded, degrades to None.
    assert results[url] is None
    assert url not in download_calls

    # `other_url` was absent from the map entirely: downloaded normally.
    assert download_calls == [other_url]
    np.testing.assert_array_almost_equal(results[other_url], computed)


# ─── Degradation paths ────────────────────────────────────────────────────────


def test_image_download_failure_skipped_without_raising(monkeypatch, fake_conn):
    url = "http://x/broken.jpg"
    _use_fake_conn(monkeypatch, fake_conn)

    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (_FakeModel(), object()))

    def _raise_download(_url):
        raise TimeoutError("simulated network failure")

    monkeypatch.setattr(ml_embeddings, "_download_image", _raise_download)

    results = ml_embeddings.embed_images([url])

    assert results[url] is None
    assert url not in fake_conn._embeddings  # nothing was persisted for a failed download


def test_blank_url_skipped_without_touching_model_or_db(monkeypatch, fake_conn):
    _use_fake_conn(monkeypatch, fake_conn)

    def _boom_load():
        raise AssertionError("model should never load for a blank URL")

    monkeypatch.setattr(ml_embeddings, "_load_model", _boom_load)

    results = ml_embeddings.embed_images([""])

    assert results[""] is None


def test_model_unavailable_marks_all_pending_urls_as_none(monkeypatch, fake_conn):
    _use_fake_conn(monkeypatch, fake_conn)
    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (None, None))

    def _boom_download(*_a, **_kw):
        raise AssertionError("should never attempt download when model is unavailable")

    monkeypatch.setattr(ml_embeddings, "_download_image", _boom_download)

    results = ml_embeddings.embed_images(["http://x/1.jpg", "http://x/2.jpg"])

    assert results["http://x/1.jpg"] is None
    assert results["http://x/2.jpg"] is None


def test_load_model_degrades_to_none_on_import_failure(monkeypatch):
    """`_load_model` itself must never raise, even if open_clip/torch are
    missing or the underlying model load throws for any reason."""

    class _FakeOpenClip:
        @staticmethod
        def create_model_and_transforms(_model_id):
            raise RuntimeError("simulated: weights not available / import failure")

    import sys as _sys

    monkeypatch.setitem(_sys.modules, "torch", _FakeTorch())
    monkeypatch.setitem(_sys.modules, "open_clip", _FakeOpenClip())

    model, preprocess = ml_embeddings._load_model()

    assert model is None
    assert preprocess is None


def test_load_model_is_a_singleton_loaded_at_most_once(monkeypatch):
    calls = {"n": 0}

    class _FakeOpenClip:
        @staticmethod
        def create_model_and_transforms(_model_id):
            calls["n"] += 1
            raise RuntimeError("simulated failure, still counts as one attempt")

    import sys as _sys

    monkeypatch.setitem(_sys.modules, "torch", _FakeTorch())
    monkeypatch.setitem(_sys.modules, "open_clip", _FakeOpenClip())

    ml_embeddings._load_model()
    ml_embeddings._load_model()
    ml_embeddings._load_model()

    assert calls["n"] == 1


# ─── HF_HOME provisioning ─────────────────────────────────────────────────────


def test_ensure_hf_home_sets_default_next_to_models_root_when_unset(monkeypatch):
    monkeypatch.delenv("HF_HOME", raising=False)
    monkeypatch.delenv("SCRAPER_MODELS_ROOT", raising=False)

    ml_embeddings._ensure_hf_home()

    expected = ml_embeddings._default_hf_home()
    assert ml_embeddings.os.environ["HF_HOME"] == str(expected)
    assert expected.name == "marqo"
    assert expected.parent.name == "_models"  # fallback root when SCRAPER_MODELS_ROOT unset


def test_ensure_hf_home_respects_existing_env_var(monkeypatch):
    monkeypatch.setenv("HF_HOME", "/already/set/path")

    ml_embeddings._ensure_hf_home()

    assert ml_embeddings.os.environ["HF_HOME"] == "/already/set/path"


# ─── DB-failure degradation paths ────────────────────────────────────────────


def test_missing_table_degrades_to_none_without_raising(monkeypatch):
    """A DB that exists but never had `image_embeddings` created (e.g.
    schema migration didn't run yet) must degrade, not raise."""
    conn = FakeConnection(raise_on_embeddings_select=True)
    _use_fake_conn(monkeypatch, conn)

    results = ml_embeddings.embed_images(["http://x/1.jpg", "http://x/2.jpg"])

    assert results["http://x/1.jpg"] is None
    assert results["http://x/2.jpg"] is None


def test_corrupted_cache_row_is_treated_as_a_miss_and_recomputed(monkeypatch, fake_conn):
    """A cached row whose blob length doesn't match `dim*4` is explicitly
    validated by `get_cached` and treated as a miss — `embed_images`
    self-heals by recomputing and overwriting the corrupted row, exactly
    like a model_version-bump miss."""
    url = "http://x/corrupted.jpg"
    seed_embedding(fake_conn, url, b"\x00\x01\x02", 999, ml_embeddings.MODEL_VERSION)
    _use_fake_conn(monkeypatch, fake_conn)

    recomputed = np.array([1.0, 1.0], dtype=np.float32)
    monkeypatch.setattr(ml_embeddings, "_load_model", lambda: (_FakeModel(), object()))
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda u: "fake-pil-image")
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: recomputed
    )

    results = ml_embeddings.embed_images([url])

    np.testing.assert_array_almost_equal(results[url], recomputed)
    blob, dim, model_version = fake_conn._embeddings[url]
    assert (dim, model_version) == (2, ml_embeddings.MODEL_VERSION)


def test_unusable_db_connection_degrades_all_urls_to_none(monkeypatch):
    """`_get_connection()` itself failing (e.g. `DATABASE_URL` unreachable,
    auth failure, or Postgres down) must degrade every requested URL to
    `None` instead of raising out of `embed_images`."""

    def _boom_connect():
        raise RuntimeError("simulated: could not connect to server")

    monkeypatch.setattr(ml_embeddings, "_get_connection", _boom_connect)

    results = ml_embeddings.embed_images(["http://x/1.jpg", "http://x/2.jpg"])

    assert results == {"http://x/1.jpg": None, "http://x/2.jpg": None}
