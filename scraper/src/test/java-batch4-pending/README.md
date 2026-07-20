# Batch 4 pending — SQLite-coupled DatabaseService tests

This directory is **outside** Maven's default `testSourceDirectory`
(`src/test/java`), so nothing here is compiled or run by `mvn test`.

## Why these files live here

`decouple-services-postgres` Batch 1 rewrote `DatabaseService` to use a
pooled `DataSource` (HikariCP/Postgres) and removed the SQLite-era test seam
these 15 files depended on: the no-arg `new DatabaseService()` constructor,
the package-private `initEn(String path)`/`cerrar()`/`conexion()`/
`readConexion()` methods, and (for two files) direct
`DriverManager.getConnection("jdbc:sqlite:...")` calls and the now-removed
`backfillBadgeKeys()` migration behavior.

Per `sdd/decouple-services-postgres/tasks` (task 4.6), migrating all
DatabaseService test classes to the Postgres-backed `PostgresTestBase`
(Testcontainers in CI, portable `_tools/pgsql` locally, `TRUNCATE ...
RESTART IDENTITY CASCADE` isolation) is explicit **Batch 4** scope. Batch 1
intentionally does not build that shared test-lifecycle infrastructure, so
these files are quarantined here rather than left broken in
`src/test/java` (which would silently break `mvn test-compile` for the
whole module).

## Files here (15)

- `DatabaseServiceWriteSerializationTest.java` — tests the SQLite WAL/
  busy_timeout pragma + `writeLock`/`readLock` lock-dance mechanics that
  design decision D1 (`sdd/decouple-services-postgres/design`) explicitly
  **removes**. This file's premise no longer applies under Postgres MVCC —
  its replacement is the new `DatabaseServiceConcurrencyTest`
  (`scraper/src/test/java/ar/scraper/db/`, task 1.4) written against the
  stored-proc write path. Do not "migrate" this file as-is in Batch 4;
  decide whether any of its assertions still have a Postgres-relevant
  analog, or retire it.
- `DatabaseServiceBadgeMigrationTest.java` — tests the removed
  `backfillBadgeKeys()` runtime migration (obsolete: the Postgres baseline
  ships with current badge keys from day one, no legacy SQLite data is
  carried over by this infra swap).
- `DatabaseServiceImageEmbeddingsTest.java` — mixes DatabaseService-seam
  assertions with direct `jdbc:sqlite:` connections simulating a "legacy DB"
  migration scenario; needs a Postgres-native rewrite, not a mechanical port.
- The remaining 12 files (`DatabaseServicePresetTest`,
  `DatabaseServiceCronTest`, `DatabaseServiceFavoritosSitiosTest`,
  `DatabaseServiceCategoriaDismissTest`,
  `DatabaseServiceCantidadUnidadesTest`,
  `DatabaseServiceOutfitFeedbackSlotAgnosticTest`,
  `DatabaseServiceOutfitFeedbackEstiloTest`,
  `DatabaseServiceSavedOutfitsTest`,
  `DatabaseServiceVisualAttrsPreserveTest`,
  `ApiControllerFeedbackEstiloTest`,
  `ApiControllerRecomendadosBidirectionalTest`,
  `ApiControllerRecomendadosPackFieldsTest`) test business logic that is
  unaffected by the Postgres swap — only their `@BeforeEach`/`@AfterEach`
  setup (`new DatabaseService()` + `initEn(tempPath)` + `cerrar()`) needs to
  change to construct `DatabaseService(DataSource)` against
  `PostgresTestBase`. These are the most mechanical of the 15 and the best
  candidates to migrate first in Batch 4.

## Rollback boundary

Reverting Batch 1 = `git mv` these files back to
`scraper/src/test/java/ar/scraper/db/` and reverting the `DatabaseService`/
`pom.xml`/`db/migration/V1__baseline.sql` changes in the same commit.
