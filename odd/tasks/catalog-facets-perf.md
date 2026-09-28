# catalog-facets-perf

## Objective
Cut `/api/data` (~230 ms) and `/api/facets` (~210 ms) server time without
changing their JSON contract, keeping the schema in 3NF and safe under
concurrent load.

## Problem (measured 2026-09-28, dev DB, 22,191 active rows)
- Facets are catalog-wide and unfiltered, yet `CatalogoEndpoints.data`
  recomputes `resumen` + `facetas` on EVERY page request, and `/api/facets`
  recomputes them again: 13 full scans per call.
- `CatalogQueryRepository.facetas` runs 8 separate `GROUP BY` scans over
  `productos` (~102 ms total). One `GROUPING SETS` pass: ~37 ms (psql).
- Child-table facets (talle ~39 ms, badge ~28 ms) and resumen (~30 ms) remain.

## Design
1. **One scan**: the 8 `productos` facets in one `GROUPING SETS` query;
   identical output (per-facet order `count DESC, key ASC`, marca top 30,
   sub_categoria by key, blank keys excluded).
2. **Version-stamped cache**: `catalog_version` single-row table bumped by
   triggers on `productos`, `producto_talle`, `producto_badge` (and any other
   table a facet/resumen reads). Deferred constraint trigger, once per
   transaction, so the row lock is held only at commit (no serialization of
   concurrent scrape writers). Monotonic value from a sequence not reset by
   TRUNCATE. Cache keyed by (cota, version); single-flight recompute; a
   missing version row means "do not cache".
   Correctness: version is read BEFORE computing, so an entry can be
   fresher than its label but never staler.

## Constraints
- 3NF (docs/DATABASE.md admission rule); applied migrations byte-frozen;
  rollback SQL documented in docs/ARCHITECTURE.md like V36-V39.
- No privilege changes; trigger function with fixed `search_path`.
- `PostgresTestBase.truncateAll` rule for new tables.
- Refactor contract: existing tests pass untouched.

## TDD
Strict, on (global CLAUDE.md). Backend runner:
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`

## Tasks
- [x] T1 GROUPING SETS facets (existing facet tests stay green untouched).
- [x] T2 V40 `catalog_version` + triggers; tests: bump on insert/update/
      delete/truncate of each tracked table, once per transaction.
- [x] T3 Cache in front of `resumen`/`facetas`; tests: hit when unchanged,
      miss after a catalog write, cota isolation, concurrent single-flight.
- [x] T4 Docs: DATABASE.md (V40), ARCHITECTURE.md decision entry.
- [x] T5 Measure: curl timings before/after; concurrent load run.

## Evidence
Baseline (curl, warm): `/api/data?size=48` 229-258 ms, `/api/facets`
204-212 ms.

**T1** — RED: `CatalogFacetsQueryCountTest` expected 1 direct scan of
`productos` inside `facetas()`, observed 8. GREEN after the `GROUPING SETS`
refactor: 1. `CatalogFacetsSqlEquivalenceTest` (byte-identical output vs the
in-memory oracle) stayed green **untouched** — refactor contract held.

**T2** — RED: `V40CatalogVersionTest` against the schema without `V40`:
8 of 9 tests failed with `relation "catalog_version" does not exist` (the
9th, the upsert-still-reports-`nuevos()` guard, isn't specific to V40 and
passed either way). GREEN after adding the migration: 9/9. Covers insert/
update/delete on `productos`, `producto_talle`, `producto_badge`; a 3-table
`TRUNCATE` in one statement bumping exactly once; a 5-row `sp_upsert_run`
batch bumping exactly once; a rolled-back transaction bumping zero times.

**T2 side effect, fixed**: `V40`'s deferred constraint trigger on `productos`
made `V25RollbackRoundTripTest` fail for a real reason — Postgres refuses
`ALTER TABLE productos` while a deferred trigger event from an earlier INSERT
is still pending in the same transaction. Fixed by adding
`SET CONSTRAINTS ALL IMMEDIATE` before that test's rollback statement (forces
the pending bump to run early; asserts nothing different). No other
`V*RollbackRoundTripTest` was affected — full suite confirms.

**T3** — RED: with `conCache()` temporarily short-circuited to always call
the delegate, `segundaLlamadaEsHit` (expected 1, got 2) and
`singleFlightBajoConcurrencia` (expected 1, got 20) failed for the right
reason; the other two (cota isolation, invalidation-after-write) don't
discriminate a no-cache baseline by construction and passed either way.
GREEN after restoring the real single-flight cache: 4/4.

**Wiring risk closed**: added `CatalogQueryPortPrimaryWiringTest` — with both
`CatalogQueryRepository` and `CachingCatalogQueryPort` registered as beans,
`context.getBean(CatalogQueryPort.class)` resolves to the `@Primary` cache
decorator, not an ambiguous-bean failure. `SiteRegistrySingletonWiringTest`
(hand-built Spring context, doesn't register the decorator) and
`SpringWiringTest` (static DI-graph check) both still pass — the decorator
never touched `CatalogQueryRepository`'s constructor.

## Parent review round 1 — two issues fixed

**Issue 1, cache poisoning.** `CatalogQueryRepository.facetas`/`resumen`
swallow SQL errors and return a fallback (all-empty `Facets`; `resumen` with
`total()==0` → `/api/data` answers 204). `CachingCatalogQueryPort` was caching
that fallback under the current version, so one transient DB error (e.g. a
pool timeout under load) would serve empty facets / a 204 catalog until the
next catalog write bumped the version — possibly hours.

Fix, in the decorator only (repository contract untouched): after `compute`
returns, check whether the result is shaped like the fallback (`Facets` with
all ten maps empty; `CatalogResumen.total()==0`). If so, still `complete()`
the shared future first — waiting single-flight callers get the same answer
`buscar()`/a direct call would have gotten, never worse — then remove the
cache entry so the NEXT call recomputes instead of serving the poisoned
value again. Tradeoff accepted per review: a genuinely empty catalog is
indistinguishable from the fallback and also won't get cached — cheap, since
there's nothing to scan.

RED: `CachingCatalogQueryPortTest.facetasFallbackNoSeCachea` and
`resumenFallbackNoSeCachea` — delegate returns the fallback shape once, then
real (non-empty) data at the SAME version. With the poisoning bug (fallback
completed the future but never evicted), the second call returned the stale
fallback and the delegate was called only once:
`facetasFallbackNoSeCachea` → expected `containsKey("Nike")`, got empty map;
`resumenFallbackNoSeCachea` → expected `total()==5`, got `0`. Also had to fix
the test double: `CountingDelegate` returned the fallback SHAPE by default
for every existing test, which would have made all of them accidentally
exercise the new no-cache path instead of real caching — changed its default
return to realistic non-empty data, added `facetasFallbackUnaVez`/
`resumenFallbackUnaVez` one-shot flags for the two new tests. GREEN: 6/6 in
`CachingCatalogQueryPortTest`.

**Issue 2, deferred trigger vs. same-transaction DDL.** Documented where the
next person hits it: `docs/DATABASE.md` § `V40` (new paragraph: a migration
that writes to `productos`/`producto_talle`/`producto_badge` and then
`ALTER TABLE`s that same table in the SAME `.sql` fails with
`cannot ALTER TABLE ... because it has pending trigger events`, because
Flyway runs each migration as one transaction and V40's triggers are
`DEFERRABLE INITIALLY DEFERRED`; fix is `SET CONSTRAINTS ALL IMMEDIATE;`
before the DDL, or split into two migrations) and `docs/GOTCHAS.md` →
"Entorno, procesos y config" (one-line pointer to the same section).
`CLAUDE.md` not touched.

**Full suite, final** (`mvn -f scraper/pom.xml clean test`, JDK 24 compile /
JRE 21 run): `Tests run: 2977, Failures: 0, Errors: 0, Skipped: 7` (same 7
pre-existing infra-absence skips as before this round). `BUILD SUCCESS`.

**Files touched, round 1 review fixes**:
`scraper/src/main/java/ar/scraper/db/CachingCatalogQueryPort.java` (fallback
no longer cached), `scraper/src/test/java/ar/scraper/db/CachingCatalogQueryPortTest.java`
(2 new tests + fixture fix), `docs/DATABASE.md` (V40 section, DDL-after-write
gotcha), `docs/GOTCHAS.md` (one-line pointer).

**Files touched overall** (T1-T4 + review fixes):
`scraper/src/main/java/ar/scraper/db/CatalogQueryRepository.java` (T1),
`scraper/src/main/resources/db/migration/V40__catalog_version.sql` (T2),
`scraper/src/test/java/ar/scraper/db/support/PostgresTestBase.java` (T2, comment
only — `catalog_version` deliberately excluded from `truncateAll`),
`scraper/src/main/java/ar/scraper/db/CachingCatalogQueryPort.java` (T3, then
fallback fix), `scraper/src/test/java/ar/scraper/db/migration/V25RollbackRoundTripTest.java`
(T2 fallout fix), `docs/DATABASE.md` + `docs/ARCHITECTURE.md` + `docs/GOTCHAS.md`
(T4 + review fixes), plus test files: `CatalogFacetsQueryCountTest`,
`V40CatalogVersionTest`, `CachingCatalogQueryPortTest`,
`CatalogQueryPortPrimaryWiringTest`.

**Open risk**: T5 (live curl timing, concurrent load) was not run — out of
the delegated scope (T1-T4 + the two review fixes). The GROUPING SETS query's
actual plan was not inspected with `EXPLAIN ANALYZE` against production-sized
data either; correctness is proven (byte-identical facets), the ~3x reduction
in scan count is by construction (1 scan instead of 8), but the millisecond
number from the spec's "Problem" section is not re-measured here.

## T5 — measured (2026-09-28, dev DB 22,191 active rows, real jar, parent run)
Same Python load script against master jar, then branch jar (V40 applied on boot).
Mix: `/api/data` pages 1-6 (size 48) + `/api/facets`, authenticated.

| | master | branch |
|---|---|---|
| serial `/api/data` p1, median | 242 ms | 28.5 ms |
| serial `/api/data` p4, median | 223 ms | 21.3 ms |
| serial `/api/facets`, median | 245 ms | 3.0 ms |
| 10 concurrent, throughput | 25 req/s | 264 req/s |
| 10 concurrent, p95 | 496 ms | 58 ms |
| 50 concurrent, throughput | 26 req/s | 297 req/s |
| 50 concurrent, p95 / p99 | 3288 / 4156 ms | 317 / 395 ms |
| errors | 0 | 0 |

Cache miss (first `/api/facets` right after a one-row write), 8 runs:
median 141 ms (was 245 ms uncached: the GROUPING SETS gain).

Trigger cost, 22k-row `UPDATE ... SET precio = precio` in one transaction,
2 runs each: COMMIT 63 / 68 ms with the trigger vs 15.5 / 15.5 ms without,
i.e. ~50 ms per 22k rows (~2.3 µs/row), paid once at commit.
