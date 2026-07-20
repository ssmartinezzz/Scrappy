# Quarantined Python ML tests (Batch 4 pending)

These two files were moved out of `ml-tests/` during **Batch 2** of
`decouple-services-postgres` (Python → psycopg migration) because they build
real SQLite fixtures and issue direct sqlite3 SQL (`?` placeholders,
`INSERT OR REPLACE`, `conn.execute(...)`) against `image_embeddings` /
`productos` — a shape that no longer matches `ml_embeddings.py` after its
psycopg2/Postgres rewrite (design D4: `%s` placeholders,
`INSERT ... ON CONFLICT`, cursor-based execute, `DATABASE_URL`-driven
connections, no more `db_path` parameter anywhere).

`ml-tests/conftest.py` excludes this directory from pytest collection via
`collect_ignore_glob = ["py-batch4-pending/*"]`, mirroring the Java
`scraper/src/test/java-batch4-pending/` quarantine pattern from Batch 1
(moving a test out of the default collection root is a config-free way to
"pause" it without deleting history).

## Files

- **`test_ml_embeddings.py`** (was 451 lines) — model-load singleton +
  `get_cached`/`insert_cache`/`embed_images` cache round-trip, degradation
  paths (corrupted row, missing table, unusable DB path), HF_HOME
  provisioning. Every test builds a real SQLite file via a `db_path` pytest
  fixture and calls the (now-removed) `db_path=...` keyword argument on
  `embed_images`/`_load_model`/`classify`.
- **`test_ml_embeddings_backfill.py`** (was 553 lines) — the `backfill()` CLI
  entrypoint's chunking, dedup, degraded-row-skip, and commit-per-chunk
  behavior. Same real-SQLite-fixture pattern, plus direct `conn.execute(...)`
  calls against a hand-built `productos` table.

## Why not migrated in Batch 2

Batch 2's task list (`sdd/decouple-services-postgres/tasks`) scopes exactly
one NEW test (task 2.5: "embedding cache round trip test") for the psycopg
rewrite, not a full re-platforming of this pre-existing 1000+ line suite. A
faithful migration needs a real running Postgres to test against (this
sandbox has neither Docker nor a portable Postgres — see Batch 1's own
`DatabaseServiceTest`/`DatabaseServiceConcurrencyTest`, quarantined /
unexecuted for the identical reason) OR a hand-built fake psycopg2-compatible
connection double sophisticated enough to run the REAL SQL these functions
now issue (parameterized `%s` execute, `ON CONFLICT` upsert semantics,
`bytea`/`memoryview` round-trip) — building and validating that fake against
every scenario in both files is exactly the scope of a dedicated Batch 4
Python test-seam task, matching the existing Batch 4 items 4.5/4.6 for the
analogous Java `PostgresTestBase` work.

`decouple-services-postgres/tasks` did not originally list a Python
test-seam migration item under Batch 4 — this gap was discovered during
Batch 2 and should be added as a new Batch 4 task (e.g. 4.9: "migrate/rewrite
`ml-tests/py-batch4-pending/*.py` against a real Postgres/Testcontainers
fixture or an equivalent psycopg2-compatible fake, then delete this
directory") before Batch 4 is considered complete.

## What replaced these in Batch 2

`ml-tests/test_ml_embeddings_postgres_cache.py` (new) exercises the SAME
`get_cached`/`insert_cache` bytea round-trip logic against a lightweight
in-memory fake connection/cursor (no real Postgres, no sqlite3) — enough to
pin the SQL shape and byte-for-byte round-trip contract without needing a
live database, per this batch's mandate ("bytea round-trip logic with a
fake/mock... should actually run and pass").
