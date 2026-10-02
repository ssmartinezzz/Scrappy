# Backend dedup — JdbcTemplate, scraper registry, Product builder

## Objective

Cut duplication and wiring-only files in `scraper/src/main/java/ar/scraper` with patterns that
already fit the stack (Spring JDBC generics, Factory-as-registry, Lombok `@Builder`), keeping
normalization, ACID, Page Object Model, SOLID, commons-lang3 and Lombok conventions, and no
boilerplate comments.

## Problem (measured 2026-10-02, master b5ecf2a)

- 343 files, 33,144 lines. Small files are records/enums/ports: merging them would break ISP
  and the hexagonal ports — **file count alone is not the target**.
- PMD CPD (`mvn -f scraper/pom.xml org.apache.maven.plugins:maven-pmd-plugin:3.21.2:cpd
  -DminimumTokens=50 -Dformat=xml` → `scraper/target/cpd.xml`; the property is `minimumTokens`,
  not `cpd.minimumTokens`): 80 clones, ~790 lines (~2.4%). At 80 tokens: 13 clones.
  By package: pages 40, model 34, pcs 23, ml 20, db 18, web 12, pcs/specs 8.
- `db/`: `spring-boot-starter-jdbc` is a dependency but `JdbcTemplate` is used in **0** files;
  **113** hand-written `dataSource.getConnection()` + `PreparedStatement` + try/catch blocks.
  CPD misses them because the SQL strings differ.
  - `SavedOutfitsRepository` (201) ↔ `SavedPcsRepository` (232): `eliminar*`/`renombrar*` are
    identical except table name and log text.
  - The DataSource is already a `TransactionAwareDataSourceProxy` (`config/TransactionConfig.java:43`)
    and `@Transactional` + `Sql.traducir`/`Sql.marcarRollback` (`db/Sql.java`) are in use, so a
    `JdbcTemplate` over that same DataSource joins the same transactions.
- `scrapers/` (451 lines): `ScraperFactory.crear` is a 13-branch `if` chain; 13 `BaseScraper`
  subclasses of ~20 lines are pure wiring (`new XxxPage(...).scrapeAll()`), a parallel hierarchy
  to `pages/`. Only `ScraperFactoryPlatformTest` references the subclasses (`isInstanceOf`).
- `model/Product.java`: record with ~21 components, 8 telescoping legacy constructors (34 CPD
  hits), no validation. `new Product(` call sites: 24 in `src/main`, 181 in `src/test`.

## Decisions

- Order by impact (agreed in chat 2026-10-02): A `JdbcTemplate` → B scraper registry →
  C `Product` builder → D remaining clones.
- Generics go where a caller needs the type (`RowMapper<T>`, `SavedBuildRepository<T>`), NOT in
  the scraper registry: every Page is used only through `BasePage.scrapeAll()`.
- Builder option (b): add `@Builder(toBuilder = true)`, migrate all call sites, delete the 7
  legacy constructors. Option (a) (keep `@Deprecated` constructors) rejected: 88% of calls are
  in tests, so the duplication would stay.
- Every repository keeps its current error contract (catch → log → sentinel, or
  `Sql.traducir` → `PersistenciaException`). `JdbcTemplate` throws `DataAccessException`; the
  translation lives in one place, not per method.
- The upsert path (`sp_upsert_run`, `sp_soft_delete_ausentes`, "the upsert swallows SQL
  errors") goes last, in its own commit (user authorized "todo" on 2026-10-02).

## Open — ask the user at the start of each phase

- **B**: rewriting `ScraperFactoryPlatformTest` (assert the chosen Page/platform instead of the
  subclass) breaks `CODE-2`. Declare it as a behavior-visible change up front, or keep it?
- **C**: migrating 181 test call sites breaks `CODE-2` literally. Proposed: one mechanical
  commit, zero assertion changes. Needs explicit OK.
- **C**: compact-constructor validation (`Validate.notBlank(sitio/nombre)`) is a behavior
  change → its own commit with RED first. Needs explicit OK.

## Checks

- TDD: strict (global config). Pure-refactor tasks: the existing suite is the net (`CODE-2`);
  any new behavior (C3) needs observed RED → GREEN → REFACTOR (`CODE-1`).
- Runner (always `clean`, redirect to a file, check `$?`, grep `ERROR]`/`BUILD FAILURE`):
  `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java > $SCRATCH/test.log 2>&1; echo $?`
- `db/` changes: a green suite is not enough — boot the jar (crons disabled) and grep WARN
  (see memories `backend-runtime-smoke-recipe`, `dev-boot-starts-overdue-cron-scrape`).
- Re-run CPD after each phase and record the delta here (`CODE-3`).
- Commits: conventional, English, no AI attribution (`COMMIT-1..3`); one unit per commit
  (`COMMIT-4`); suite green on every commit (`TEST-1`). PRs > 400 lines → `chained-pr`.

## Tasks

### Phase 0 — baseline
- [x] 0.1 Branch `refactor/backend-dedup-jdbctemplate`; suite 3261 run / 0 F / 0 E / 7 skipped.
- [x] 0.2 CPD 50t: 80 clones / 790 lines; 80t: 13 / 184. `dataSource.getConnection()`: 113 in 24 files.

### Phase A — JdbcTemplate (generics) in `db/`
- [x] A1 Not needed: each repository builds `new JdbcTemplate(dataSource)` in its existing
      `(DataSource)` constructor. A bean would widen constructors and break the hand-built contexts
      (`TestDatabaseServices`, `SiteRegistrySingletonWiringTest`) — refactor contract.
- [x] A2 Pilot (37a03ad): abstract `SavedBuildRepository` (table + entity label) owns
      `guardar`/`listar`/`eliminar`/`renombrar`. No class-level `<T>`: both ports return
      `Map<String,Object>` rows, so `T` would have one instantiation; generics stay on
      `RowMapper<Map<…>>`. `@Transactional` stays on the subclass port methods
      (`TestTransactions.isTransactional` reads `getDeclaredMethods()`). Generated key via
      `new String[]{"id"}` (`KeyHolder.getKey()` rejects PG's all-columns keys).
- [x] A3 Measured (pilot):
      - Lines: the two repos 433 → 334 (+88 base) = net −11. Gain is 8 hand-written
        connection/statement/try blocks gone, not line count.
      - CPD 50t: 80/790 → 78/774; 80t unchanged. `getConnection()`: 113/24 → 105/22.
      - Suite: 3261/0/0/7, zero test files touched; `TransactionalUnitsRollbackTest` green
        (JdbcTemplate joins the tx).
      - Boot + HTTP smoke on `/outfits` and `/pcs` OK; crons restored, smoke users deleted.
- [x] A4 Roll-out — **authorized 2026-10-02 ("migrá todo con JdbcTemplate")**, incl. the upsert
      call sites, last and in their own commit. One repository per commit, suite green on each.
      Out: `DbNotificationListener` (LISTEN/NOTIFY needs a dedicated raw `PGConnection`).
      Perf: Spring 6.1.6 `StatementCreatorUtils.setNull` skips `getParameterMetaData()` for the
      PostgreSQL driver (checked in bytecode) → no extra round trip per null bind.
  - [x] A4.0 `Sql.traducir` must also translate `DataAccessException` → `PersistenciaException`
        (else the 15 `traducir` sites leak a different exception type). New test, RED first.
        Done d840d81 (`SqlTest`, 4 tests). RED: `SqlTest.consultaTraduceDataAccessException:27 Unexpected exception
        type thrown, expected: <PersistenciaException> but was: <DataAccessResourceFailureException>` (2 of 4 red,
        same for `accion…`). GREEN: multi-catch `SQLException | DataAccessException`. Suite 3265/0/0/7.
  - [x] A4.P0 Perf baseline: `tests/perf/jmeter` `SmokeIT` against the jar at 37a03ad.
        1 thread, 3 iterations, 0 errors, crons disabled for the boot and restored (3,4 were enabled).
        Numbers: `/tmp/claude-1000/-games-Scrappy/a836f8e7-1960-4d9b-92a1-dcabc2808cb1/scratchpad/perf-baseline.txt` (p95 ms: data 36, data_filtrado 53, recomendados 93, pcs_builder 48,
        suplementos_builder 19, outfits_builder 18, grupos 10, marcas 10, status 8, indices 7, facets 6,
        mejores 6; only 3 samples/iteration so p99 ≈ max and first-iteration warm-up is included).
  - [x] A4.1 Small repos: MarcaSeeder, JdbcSiteSource, UnownedRowsWarner, CachingCatalogQueryPort,
        TechSpecsRepository, PreciosExternosRepository, IndiceRepository, PreferenciaArmadorRepository,
        CategoriaStatsRepository, PasswordResetRepository, HistorialRepository, MlOutputRepository,
        SitiosRepository, FavoritosRepository.
        Done, one commit each (post-rebase 24c136b..5dece5e, 14 commits), zero existing test files touched. Parity fixups
        (coordinator review): partial-list-on-failure restored (Indice, Historial x2, Favoritos, Sitios, PreciosExternos
        read) and per-row `jdbc.update` loops restored (PreciosExternos, CategoriaStats, WARN interleaved). Each commit
        ran its own clean suite in a worktree: 3265/0/0/7. `ScrapeRunIndexBenchmarkTest.medirLasTresRamas` (timing
        benchmark) flaked 5 of 10 first runs and passed on rerun at every SHA: flaky, unrelated.
        `getConnection()` left in these files: 0, except `HistorialRepository` 1 (`ps.getConnection()` inside a
        PreparedStatementSetter to `createArrayOf("text")`, not a pool checkout). FavoritosRepository never had
        `ON CONFLICT (url)`; it uses `ON CONFLICT ON CONSTRAINT uq_fav_owner_url`, kept as is.
  - [x] A4.2 Medium: RefreshTokenRepository, PresetRepository, FeedbackRepository, CatalogQueryRepository.
        Done, one commit each, own clean suite each, 3265/0/0/7 (see git log). `activarPreset` catches
        `DataAccessException` where it caught `SQLException` (same set of failures, other exceptions still propagate).
  - [x] A4.3 Large: CronRepository, UsuarioRepository, ScrapeRunRepository, ProductRepository
        (non-upsert methods).
        Done, one commit each, own clean suite each, 3265/0/0/7. No FOR UPDATE/advisory lock/check-then-set exists in
        ScrapeRunRepository, UsuarioRepository or CronRepository; multi-statement units are all @Transactional. 11
        `Sql.traducir` methods in ScrapeRunRepository still throw PersistenciaException (the `insert returned no id`
        SQLException is thrown outside the extractor so its cause/message are unchanged). `ProductRepository.obtenerProducto`
        stays on `jdbc.execute(ConnectionCallback)`: it reads the row and two child lookups on ONE connection, and a nested
        JdbcTemplate call would hold a second pool connection while the first is open.
  - [x] A4.4 Upsert call sites (`sp_upsert_run` ×2, `sp_soft_delete_ausentes`): own commit, typed
        binds kept explicit (`?::jsonb`), `nuevos()`-first tests must stay green.
        Done in its own commit; inside the TransactionTemplate the JdbcTemplate joins the same bound connection; suite 3265/0/0/7.
  - [x] A4.5 Boot smoke (crons disabled, grep WARN) + `SmokeIT` after; compare p95 with A4.P0.
        HEAD 33af024 jar: `Started App` 5.65 s (A4.P0 jar 4.9 s; old jar re-boot 3.8 s: noise). Only WARN in either boot log is
        Spring's UserDetailsServiceAutoConfiguration generated-password notice (present in both); zero ERROR, none during HTTP
        smoke. SmokeIT x3 per jar, medians of p95 (ms): order old-after-new showed +4..+23 ms on EVERY endpoint including
        in-memory `status`, so it was machine/order noise; reversed order (old then new) gives deltas -6..+1 ms (data 19/19,
        data_filtrado 32/33, recomendados 73/67, pcs_builder 32/33, facets 2/3). Conclusion: no measurable regression. One
        new-jar run tripped the 50 ms budget for pcs_builder (p95 56) in the noisy first pass. HTTP smoke (admin): login 200,
        status/data/facets 200, preset create/rename/activar/delete 200, favorito add/list/remove 200, cron create(disabled)/get/
        list/delete 200 then 404. Crons restored, scrape_run unchanged (39), smoke rows and refresh tokens deleted.
        Raw: `/tmp/claude-1000/-games-Scrappy/a836f8e7-1960-4d9b-92a1-dcabc2808cb1/scratchpad/{old,new,old2,new2}-N.txt`, `http-smoke.txt`.
  - [x] A4.6 CPD + `getConnection()` re-count; record delta.
        CPD 50t: 80 clones/790 lines -> 76/741 (db 18 -> 9 files in clones); 80t: 13/184 -> 12/170. Pool checkouts
        (`dataSource.getConnection()`) in db/: 113 -> 0. Remaining `getConnection()` text matches are all
        `ps.getConnection().createArrayOf(...)` inside setters: CatalogQuery 3, Historial 1, Product 2, ScrapeRun 2.

### Phase B — scraper registry (Factory)
- [ ] B0 Ask the `CODE-2` question above.
- [ ] B1 `PageFactory` functional interface + `PageContext` record (timeout, sitio, url,
      min/max, extraUrls, maxPaginas); `BaseScraper` becomes concrete and takes a `PageFactory`.
- [ ] B2 `ScraperFactory` → `Map<String, PageFactory>` with constructor refs; URL fallbacks
      (`myshopify.com`, `vtexcommercestable.com.br`, `vteximg.com.br`) checked before the map;
      Tiendanube stays the default.
- [ ] B3 Delete the 13 subclasses; fix the `VaypolScraper` reference in `QloudPage` javadoc.
- [ ] B4 Check the platform vocabulary copies still agree (memory `platform-vocabulary-has-two-copies`).

### Phase C — `Product` builder
- [ ] C0 Ask the two `CODE-2`/validation questions above.
- [ ] C1 `@Builder(toBuilder = true)` on the record; migrate the 24 `src/main` call sites.
- [ ] C2 Mechanical commit: migrate the 181 test call sites, no assertion changes.
- [ ] C3 Delete the 7 legacy constructors (+ validation commit if approved).

### Phase D — remaining clones (only after A–C, re-measured)
- [ ] D1 `pages/`: Template Method in `BasePage` for Shopify↔Tiendanube, OsCommerce/Qloud/FullH4rd,
      `VtexPage` internal clones.
- [ ] D2 `ml/PythonRunner`: one parameterized script-launch method (6 internal clones).
- [ ] D3 `pcs/specs`: move shared extraction into `Tokens`.
- [ ] D4 `web/` Outfits/Pcs/Suplementos controllers: shared helper.
- [ ] D5 `@RequiredArgsConstructor` where constructors only assign fields (grep found ~117
      candidate files — unverified heuristic, check each).

## Progress

- 2026-10-02: audit done, plan written. No code changed. Next: Phase 0.
- 2026-10-02: Phase 0 done; A1/A2 done (37a03ad, unpushed).
- 2026-10-02: user authorized full A4 roll-out. Next: A4.0.
- 2026-10-02: A4.P0, A4.0, A4.1 done (d840d81, 24c136b..5dece5e), A4.2 done. Next: A4.3.
- 2026-10-02: A4.3, A4.4, A4.6 done. A4.5 (boot + SmokeIT) pending, scheduled by coordinator.
- 2026-10-02: A4 done (24 commits 37a03ad..33af024, unpushed): 0 pool checkouts left in db/, CPD 50t 76/741, 80t 12/170; suite 3265/0/0/7; boot + HTTP smoke clean; SmokeIT p95 before/after within ±6 ms. Next: PR plan (chained-pr, ~2460 changed lines) or Phase B (ask B0 first).
