# -*- coding: utf-8 -*-
"""
Tests for ml_embeddings.py (PR3a slice): model load + embedding cache.

Runs with NO network access and NO model weights present — every heavy
dependency (open_clip, torch, PIL) is monkeypatched at the module's own
lazy-import boundary (`_load_model`, `_download_image`, `_compute_embedding`),
so this module stays importable and testable even on a machine where
open_clip/torch are not installed (a required constraint per this PR's
apply-progress brief).
"""
import sqlite3
import numpy as np
import pytest

import ml_embeddings


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
def db_path(tmp_path):
    """A temp SQLite file with the `image_embeddings` table pre-created
    using the EXACT schema shipped in PR1 (DatabaseService.crearTablas)."""
    path = tmp_path / "scraper.db"
    conn = sqlite3.connect(str(path))
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS image_embeddings (
            url           TEXT PRIMARY KEY,
            embedding     BLOB NOT NULL,
            dim           INTEGER NOT NULL,
            model_version TEXT NOT NULL,
            computed_at   TEXT NOT NULL
        )
        """
    )
    conn.commit()
    conn.close()
    return str(path)


def _seed_cache_row(db_path, url, embedding, model_version):
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at) "
        "VALUES (?, ?, ?, ?, ?)",
        (url, np.asarray(embedding, dtype=np.float32).tobytes(), len(embedding), model_version, "2026-01-01T00:00:00+00:00"),
    )
    conn.commit()
    conn.close()


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


# ─── get_cached / insert_cache (direct unit coverage) ───────────────────────


def test_insert_then_get_cached_round_trip(db_path):
    conn = sqlite3.connect(db_path)
    embedding = np.array([0.1, 0.2, 0.3], dtype=np.float32)
    ml_embeddings.insert_cache(conn, "http://x/a.jpg", embedding, 3, "v1")

    result = ml_embeddings.get_cached(conn, "http://x/a.jpg", "v1")
    conn.close()

    assert result is not None
    np.testing.assert_array_almost_equal(result, embedding)


def test_get_cached_returns_none_on_miss(db_path):
    conn = sqlite3.connect(db_path)
    result = ml_embeddings.get_cached(conn, "http://x/missing.jpg", "v1")
    conn.close()
    assert result is None


def test_get_cached_returns_none_on_model_version_mismatch(db_path):
    _seed_cache_row(db_path, "http://x/a.jpg", [1.0, 2.0], "old-version")
    conn = sqlite3.connect(db_path)
    result = ml_embeddings.get_cached(conn, "http://x/a.jpg", "new-version")
    conn.close()
    assert result is None


# ─── embed_images: cache hit ─────────────────────────────────────────────────


def test_cache_hit_skips_download_and_inference(monkeypatch, db_path):
    url = "http://x/cached.jpg"
    seeded = [0.5, 0.25, 0.75]
    _seed_cache_row(db_path, url, seeded, ml_embeddings.MODEL_VERSION)

    def _boom_load(*_a, **_kw):
        raise AssertionError("model should never be loaded on a cache hit")

    def _boom_download(*_a, **_kw):
        raise AssertionError("image should never be downloaded on a cache hit")

    monkeypatch.setattr(ml_embeddings, "_load_model", _boom_load)
    monkeypatch.setattr(ml_embeddings, "_download_image", _boom_download)

    results = ml_embeddings.embed_images([url], db_path=db_path)

    assert url in results
    np.testing.assert_array_almost_equal(results[url], np.array(seeded, dtype=np.float32))


# ─── embed_images: cache miss ────────────────────────────────────────────────


def test_cache_miss_downloads_computes_and_inserts(monkeypatch, db_path):
    url = "http://x/new.jpg"
    computed = np.array([1.0, 2.0, 3.0, 4.0], dtype=np.float32)

    monkeypatch.setattr(ml_embeddings, "_load_model", lambda db_path: (_FakeModel(), object()))
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda u: "fake-pil-image")
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: computed
    )

    results = ml_embeddings.embed_images([url], db_path=db_path)

    assert url in results
    np.testing.assert_array_almost_equal(results[url], computed)

    # Verify it was actually persisted, not just returned in-memory.
    conn = sqlite3.connect(db_path)
    cached = ml_embeddings.get_cached(conn, url, ml_embeddings.MODEL_VERSION)
    conn.close()
    assert cached is not None
    np.testing.assert_array_almost_equal(cached, computed)


def test_model_version_mismatch_treated_as_miss_and_recomputed(monkeypatch, db_path):
    url = "http://x/stale.jpg"
    _seed_cache_row(db_path, url, [9.0, 9.0], "old-version")

    recomputed = np.array([1.0, 1.0], dtype=np.float32)
    monkeypatch.setattr(ml_embeddings, "_load_model", lambda db_path: (_FakeModel(), object()))
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda u: "fake-pil-image")
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: recomputed
    )

    results = ml_embeddings.embed_images([url], db_path=db_path, model_version=ml_embeddings.MODEL_VERSION)

    np.testing.assert_array_almost_equal(results[url], recomputed)

    conn = sqlite3.connect(db_path)
    row = conn.execute(
        "SELECT model_version FROM image_embeddings WHERE url = ?", (url,)
    ).fetchone()
    conn.close()
    assert row[0] == ml_embeddings.MODEL_VERSION


# ─── Degradation paths ────────────────────────────────────────────────────────


def test_image_download_failure_skipped_without_raising(monkeypatch, db_path):
    url = "http://x/broken.jpg"

    monkeypatch.setattr(ml_embeddings, "_load_model", lambda db_path: (_FakeModel(), object()))

    def _raise_download(_url):
        raise TimeoutError("simulated network failure")

    monkeypatch.setattr(ml_embeddings, "_download_image", _raise_download)

    results = ml_embeddings.embed_images([url], db_path=db_path)

    assert results[url] is None

    conn = sqlite3.connect(db_path)
    row = conn.execute("SELECT 1 FROM image_embeddings WHERE url = ?", (url,)).fetchone()
    conn.close()
    assert row is None  # nothing was persisted for a failed download


def test_blank_url_skipped_without_touching_model_or_db(monkeypatch, db_path):
    def _boom_load(*_a, **_kw):
        raise AssertionError("model should never load for a blank URL")

    monkeypatch.setattr(ml_embeddings, "_load_model", _boom_load)

    results = ml_embeddings.embed_images([""], db_path=db_path)

    assert results[""] is None


def test_model_unavailable_marks_all_pending_urls_as_none(monkeypatch, db_path):
    monkeypatch.setattr(ml_embeddings, "_load_model", lambda db_path: (None, None))

    def _boom_download(*_a, **_kw):
        raise AssertionError("should never attempt download when model is unavailable")

    monkeypatch.setattr(ml_embeddings, "_download_image", _boom_download)

    results = ml_embeddings.embed_images(["http://x/1.jpg", "http://x/2.jpg"], db_path=db_path)

    assert results["http://x/1.jpg"] is None
    assert results["http://x/2.jpg"] is None


def test_load_model_degrades_to_none_on_import_failure(monkeypatch, db_path):
    """`_load_model` itself must never raise, even if open_clip/torch are
    missing or the underlying model load throws for any reason."""

    class _FakeOpenClip:
        @staticmethod
        def create_model_and_transforms(_model_id):
            raise RuntimeError("simulated: weights not available / import failure")

    import sys as _sys

    monkeypatch.setitem(_sys.modules, "torch", _FakeTorch())
    monkeypatch.setitem(_sys.modules, "open_clip", _FakeOpenClip())

    model, preprocess = ml_embeddings._load_model(db_path)

    assert model is None
    assert preprocess is None


def test_load_model_is_a_singleton_loaded_at_most_once(monkeypatch, db_path):
    calls = {"n": 0}

    class _FakeOpenClip:
        @staticmethod
        def create_model_and_transforms(_model_id):
            calls["n"] += 1
            raise RuntimeError("simulated failure, still counts as one attempt")

    import sys as _sys

    monkeypatch.setitem(_sys.modules, "torch", _FakeTorch())
    monkeypatch.setitem(_sys.modules, "open_clip", _FakeOpenClip())

    ml_embeddings._load_model(db_path)
    ml_embeddings._load_model(db_path)
    ml_embeddings._load_model(db_path)

    assert calls["n"] == 1


# ─── HF_HOME provisioning ─────────────────────────────────────────────────────


def test_ensure_hf_home_sets_default_next_to_db_when_unset(monkeypatch, db_path):
    monkeypatch.delenv("HF_HOME", raising=False)

    ml_embeddings._ensure_hf_home(db_path)

    expected = ml_embeddings._default_hf_home(db_path)
    assert ml_embeddings.os.environ["HF_HOME"] == str(expected)
    assert expected.name == "marqo"
    assert expected.parent.name == "_models"


def test_ensure_hf_home_respects_existing_env_var(monkeypatch, db_path):
    monkeypatch.setenv("HF_HOME", "/already/set/path")

    ml_embeddings._ensure_hf_home(db_path)

    assert ml_embeddings.os.environ["HF_HOME"] == "/already/set/path"


# ─── DB-failure degradation paths ────────────────────────────────────────────


def test_missing_table_degrades_to_none_without_raising(tmp_path):
    """A DB file that exists but never had `image_embeddings` created
    (e.g. schema migration didn't run yet) must degrade, not raise."""
    path = tmp_path / "no_table.db"
    conn = sqlite3.connect(str(path))
    conn.close()

    results = ml_embeddings.embed_images(
        ["http://x/1.jpg", "http://x/2.jpg"], db_path=str(path)
    )

    assert results["http://x/1.jpg"] is None
    assert results["http://x/2.jpg"] is None


def test_corrupted_cache_row_is_treated_as_a_miss_and_recomputed(monkeypatch, db_path):
    """A cached row whose blob length doesn't match `dim*4` is explicitly
    validated (PR3b fix, deferred from PR3a judgment-day): `get_cached`
    now detects this up front and returns `None`, the SAME contract as
    any other cache miss — so `embed_images` self-heals by recomputing
    and overwriting the corrupted row, exactly like a model_version-bump
    miss, rather than degrading the whole URL to `None`."""
    url = "http://x/corrupted.jpg"
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at) "
        "VALUES (?, ?, ?, ?, ?)",
        (url, b"\x00\x01\x02", 999, ml_embeddings.MODEL_VERSION, "2026-01-01T00:00:00+00:00"),
    )
    conn.commit()
    conn.close()

    recomputed = np.array([1.0, 1.0], dtype=np.float32)
    monkeypatch.setattr(ml_embeddings, "_load_model", lambda db_path: (_FakeModel(), object()))
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda u: "fake-pil-image")
    monkeypatch.setattr(
        ml_embeddings, "_compute_embedding", lambda model, preprocess, image: recomputed
    )

    results = ml_embeddings.embed_images([url], db_path=db_path)

    np.testing.assert_array_almost_equal(results[url], recomputed)

    conn = sqlite3.connect(db_path)
    row = conn.execute(
        "SELECT dim, model_version FROM image_embeddings WHERE url = ?", (url,)
    ).fetchone()
    conn.close()
    assert row == (2, ml_embeddings.MODEL_VERSION)


def test_unusable_db_path_degrades_all_urls_to_none(monkeypatch, tmp_path):
    """`sqlite3.connect()` itself failing (e.g. an unwritable/unreachable
    path, or a locked-DB scenario surfacing at connect time) must degrade
    every requested URL to `None` instead of raising out of `embed_images`."""

    def _boom_connect(*_a, **_kw):
        raise sqlite3.OperationalError("simulated: database is locked")

    monkeypatch.setattr(ml_embeddings.sqlite3, "connect", _boom_connect)

    results = ml_embeddings.embed_images(
        ["http://x/1.jpg", "http://x/2.jpg"], db_path=str(tmp_path / "unreachable.db")
    )

    assert results == {"http://x/1.jpg": None, "http://x/2.jpg": None}
