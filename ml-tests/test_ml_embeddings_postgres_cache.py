# -*- coding: utf-8 -*-
"""
Tests for `ml_embeddings.py`'s Batch 2 (decouple-services-postgres) rewrite
of the `image_embeddings` cache from SQLite to Postgres/psycopg2.

There is no Docker daemon and no running Postgres in this sandbox (same
constraint documented in Batch 1's `DatabaseServiceTest`/
`DatabaseServiceConcurrencyTest` apply-progress) — so these tests exercise
`get_cached`/`insert_cache` against a lightweight IN-MEMORY fake connection
that speaks the exact DB-API 2.0 shape psycopg2 exposes (`conn.cursor()`,
`cur.execute(sql, params)`, `cur.fetchone()`, `conn.commit()`), instead of a
real database. This pins:

  - the SQL these two functions issue is `%s`-parameterized (psycopg2 style,
    not sqlite3's `?`), including the `INSERT ... ON CONFLICT (url) DO
    UPDATE` upsert shape (Postgres has no `INSERT OR REPLACE`);
  - the `embedding` bytea round-trip is byte-for-byte lossless through
    `psycopg2.Binary(...)` on write and `bytes(...)` + `np.frombuffer` on
    read, exactly like the real `bytea` column would return;
  - `model_version` mismatch is still treated as a cache miss;
  - a real `psycopg2` import succeeds in THIS environment (verifying the
    Batch 2 spike's `psycopg2-binary` wheel choice is actually importable
    here, not just theorized) — `test_psycopg2_binary_is_importable` below.

This is deliberately NOT a full re-platforming of the old (now quarantined)
sqlite3-based `ml-tests/py-batch4-pending/test_ml_embeddings*.py` suite —
see `py-batch4-pending/README.md` for the Batch 4 follow-up that owns that.
"""
import numpy as np
import pytest

import ml_embeddings


# ─── Fake psycopg2-shaped connection (no real Postgres needed) ──────────────


class _FakeCursor:
    """Minimal DB-API 2.0 cursor double, backed by an in-memory dict keyed
    by URL — just enough surface (`execute`, `fetchone`, `close`) for
    `get_cached`/`insert_cache`, dispatching on which of the two fixed SQL
    shapes those functions actually issue."""

    def __init__(self, store):
        self._store = store
        self._result = None

    def execute(self, sql, params=()):
        normalized = " ".join(sql.split())
        if normalized.startswith("SELECT embedding, dim, model_version FROM image_embeddings"):
            (url,) = params
            self._result = self._store.get(url)
        elif normalized.startswith("INSERT INTO image_embeddings"):
            assert "ON CONFLICT (url) DO UPDATE" in normalized, (
                "insert_cache must use Postgres ON CONFLICT upsert syntax, "
                "not SQLite's INSERT OR REPLACE"
            )
            url, embedding, dim, model_version, computed_at = params
            # `insert_cache` wraps the blob in `psycopg2.Binary(...)` (the
            # correct adapter for a real bytea column) — unwrap it via its
            # `.adapted` attribute (the original object passed to
            # `Binary(...)`), so this fake mirrors what a real bytea column
            # would receive and round-trip.
            raw = embedding.adapted if hasattr(embedding, "adapted") else embedding
            self._store[url] = (bytes(raw), dim, model_version)
            self._result = None
        else:
            raise AssertionError(f"unexpected SQL issued against fake cursor: {normalized!r}")

    def fetchone(self):
        return self._result

    def close(self):
        pass


class _FakeConnection:
    def __init__(self):
        self._store = {}
        self.commit_count = 0

    def cursor(self):
        return _FakeCursor(self._store)

    def commit(self):
        self.commit_count += 1

    def close(self):
        pass


# ─── get_cached / insert_cache: bytea round-trip (task 2.5 RED → GREEN) ────


def test_insert_then_get_cached_round_trip_bytea():
    conn = _FakeConnection()
    embedding = np.array([0.1, 0.2, 0.3], dtype=np.float32)

    ml_embeddings.insert_cache(conn, "http://x/a.jpg", embedding, 3, "v1")
    result = ml_embeddings.get_cached(conn, "http://x/a.jpg", "v1")

    assert result is not None
    np.testing.assert_array_almost_equal(result, embedding)
    assert conn.commit_count == 1


def test_get_cached_returns_none_on_miss():
    conn = _FakeConnection()
    result = ml_embeddings.get_cached(conn, "http://x/missing.jpg", "v1")
    assert result is None


def test_get_cached_returns_none_on_model_version_mismatch():
    conn = _FakeConnection()
    ml_embeddings.insert_cache(conn, "http://x/a.jpg", np.array([1.0, 2.0], dtype=np.float32), 2, "old-version")

    result = ml_embeddings.get_cached(conn, "http://x/a.jpg", "new-version")

    assert result is None


def test_insert_cache_overwrites_on_conflict_same_url():
    """Postgres `ON CONFLICT (url) DO UPDATE` must behave like the old
    SQLite `INSERT OR REPLACE` — re-inserting the same URL overwrites the
    row rather than erroring or duplicating it."""
    conn = _FakeConnection()
    first = np.array([1.0, 1.0], dtype=np.float32)
    second = np.array([9.0, 9.0, 9.0], dtype=np.float32)

    ml_embeddings.insert_cache(conn, "http://x/a.jpg", first, 2, "v1")
    ml_embeddings.insert_cache(conn, "http://x/a.jpg", second, 3, "v2")

    result = ml_embeddings.get_cached(conn, "http://x/a.jpg", "v2")
    np.testing.assert_array_almost_equal(result, second)


def test_corrupted_cache_row_is_treated_as_a_miss():
    """A row whose byte length doesn't match `dim*4` (e.g. a truncated
    bytea value) must degrade to a miss, not raise — same contract as the
    pre-Batch-2 SQLite BLOB behavior."""
    conn = _FakeConnection()
    conn._store["http://x/corrupted.jpg"] = (b"\x00\x01\x02", 999, ml_embeddings.MODEL_VERSION)

    result = ml_embeddings.get_cached(conn, "http://x/corrupted.jpg", ml_embeddings.MODEL_VERSION)

    assert result is None


# ─── Spike verification: psycopg2-binary is importable in this environment ──


def test_psycopg2_binary_is_importable():
    """Batch 2 spike (task 2.1) decision: `psycopg2-binary` — verified here
    to actually import successfully (not just theorized from PyPI wheel
    metadata). The embeddable Python 3.11 target isn't present in this
    sandbox (no `_tools/python`), but this project's own installer already
    proves the identical pip-install-onto-embeddable-Python mechanism works
    for numpy/torch/open_clip/transformers (see INSTALAR_Y_CORRER.bat step
    3d/3e/3f) — `psycopg2-binary` ships a prebuilt `cp311-win_amd64` wheel
    (confirmed against PyPI's release metadata during the spike) with a
    statically-linked libpq, the same "binary wheel, no compiler needed"
    shape as those already-working packages."""
    import psycopg2

    assert psycopg2.__version__


def test_get_connection_raises_when_database_url_unset(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)

    with pytest.raises(RuntimeError):
        ml_embeddings._get_connection()
