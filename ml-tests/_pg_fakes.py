# -*- coding: utf-8 -*-
"""
Shared lightweight psycopg2-DB-API-2.0-shaped fake connection/cursor for the
`ml-tests` Postgres-backed suites (decouple-services-postgres design D4).

Introduced in task 4.9 (migrating `ml-tests/py-batch4-pending/*.py`) and
built on the SAME pattern `test_ml_embeddings_postgres_cache.py` established
in Batch 2 (`_FakeCursor`/`_FakeConnection`) — extended here with an
in-memory `productos` table (url/imagen_url/activo + the four additive
visual-attribute columns) so `ml_embeddings.py`'s `backfill()` CLI can be
pinned the same no-real-Postgres way as `get_cached`/`insert_cache` already
are. No real Postgres, no sqlite3 — pins SQL shape (`%s` placeholders,
`INSERT ... ON CONFLICT`) and semantics only.

Intentionally NOT a general-purpose SQL engine: `execute()` pattern-matches
against the small, fixed set of statements `ml_embeddings.py` actually
issues (see `get_cached`/`insert_cache`/`_pending_urls`/
`_persist_visual_attrs`) and raises `AssertionError` for anything else —
any drift between this fake and the real module's SQL surfaces immediately
as a test failure instead of a silent false-positive.
"""


class FakeCursor:
    """Minimal DB-API 2.0 cursor double dispatching on which of the fixed
    SQL shapes `ml_embeddings.py` issues against `image_embeddings` /
    `productos`."""

    def __init__(self, conn):
        self._conn = conn
        self._result = None
        self._results = None

    def execute(self, sql, params=()):
        conn = self._conn
        normalized = " ".join(sql.split())

        if normalized.startswith("SELECT embedding, dim, model_version FROM image_embeddings"):
            if conn.raise_on_embeddings_select:
                raise RuntimeError(
                    'simulated: relation "image_embeddings" does not exist'
                )
            (url,) = params
            self._result = conn._embeddings.get(url)

        elif normalized.startswith("INSERT INTO image_embeddings"):
            assert "ON CONFLICT (url) DO UPDATE" in normalized, (
                "insert_cache must use Postgres ON CONFLICT upsert syntax, "
                "not SQLite's INSERT OR REPLACE"
            )
            url, embedding, dim, model_version, computed_at = params
            # `insert_cache` wraps the blob in `psycopg2.Binary(...)` — unwrap
            # via `.adapted` (the original object passed to `Binary(...)`),
            # mirroring what a real `bytea` column round-trips.
            raw = embedding.adapted if hasattr(embedding, "adapted") else embedding
            conn._embeddings[url] = (bytes(raw), dim, model_version)
            self._result = None

        elif normalized.startswith("SELECT url, imagen_url FROM productos"):
            if conn.raise_on_productos_select:
                raise RuntimeError('simulated: relation "productos" does not exist')
            self._results = [
                (url, row.get("imagen_url"))
                for url, row in conn._productos.items()
                if row.get("activo", 1) == 1 and row.get("imagen_url")
            ]

        elif normalized.startswith("UPDATE productos SET fit"):
            fit, estampado, escote, color, url = params
            row = conn._productos.setdefault(url, {})
            row["fit"] = fit
            row["estampado"] = estampado
            row["escote"] = escote
            if color is not None:
                # COALESCE(%s, color_dominante) — a NULL color leaves the
                # existing value untouched instead of wiping it to "".
                row["color_dominante"] = color
            self._result = None

        else:
            raise AssertionError(f"unexpected SQL issued against fake cursor: {normalized!r}")

    def fetchone(self):
        return self._result

    def fetchall(self):
        return self._results if self._results is not None else []

    def close(self):
        pass


class FakeConnection:
    """In-memory psycopg2-shaped double covering the two tables
    `ml_embeddings.py` touches: `image_embeddings` (url -> (blob, dim,
    model_version)) and `productos` (url -> {imagen_url, activo, fit,
    estampado, escote, color_dominante}).

    `raise_on_embeddings_select` / `raise_on_productos_select` simulate a
    missing-table/broken-query condition (e.g. schema not migrated yet) —
    the equivalent of the old SQLite tests' "DB file exists but the table
    was never created" scenario, translated to "the query itself fails".
    """

    def __init__(
        self,
        productos=None,
        raise_on_embeddings_select=False,
        raise_on_productos_select=False,
    ):
        self._embeddings = {}
        self._productos = productos if productos is not None else {}
        self.commit_count = 0
        self.closed = False
        self.raise_on_embeddings_select = raise_on_embeddings_select
        self.raise_on_productos_select = raise_on_productos_select

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.commit_count += 1

    def close(self):
        self.closed = True


def seed_embedding(conn, url, blob, dim, model_version):
    """Test helper: pre-seed a raw `image_embeddings` row (bypassing
    `insert_cache`'s `psycopg2.Binary` wrapping) — mirrors the old SQLite
    tests' direct `conn.execute("INSERT INTO image_embeddings ...")` seeding."""
    conn._embeddings[url] = (bytes(blob), dim, model_version)
