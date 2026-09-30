# Backend hardening — comment cleanup + Spring improvements

## Objective

Cut comment boilerplate across `scraper/src/main/java/ar/scraper` and, in the same effort,
apply: typed API responses (generics), declarative transactions, retry/backoff via a library,
bounded caching, and a smaller `ApiController`.

## Problem (measured 2026-09-30, master 912d19a)

- 287 files, 39,337 lines, 10,972 comment lines (28%).
- 112 `ResponseEntity<ObjectNode>` + 45 `ResponseEntity<Object>` + 45 `Map<String,Object>` in `web/`.
- Zero `@Transactional`; 12 files manage transactions by hand (`setAutoCommit(false)` /
  `TransactionTemplate`), and repositories open connections with `dataSource.getConnection()`.
- Hand-rolled backoff: `ScraperService.java:1124`, `VaypolPage.java:147/200/227`;
  `App.java:55` sleeps 500 ms waiting for Tomcat.
- Lombok is NOT a dependency: `pom.xml` only lists it as a spring-boot-maven-plugin exclude.
- `ApiController.java` is 783 lines.

## Decisions

- User choice 2026-09-30: all improvements + comment cleanup in one effort.
- Separate commits per work unit (`COMMIT-4`); comment cleanup is a pure refactor: the
  existing tests pass untouched (`TEST-1`).
- ~~JSON contract is frozen~~ — reversed by the user 2026-09-30: "cambiemos todo, incluso lo
  que espera el frontend". Every endpoint returns a generic `ApiResponse<T>` envelope and the
  frontend (plus every other API consumer) is adapted in the same change.
- User choice 2026-09-30: implement first, adapt tests afterwards (overrides strict TDD for this
  feature). To keep `TEST-1` (green on every commit), nothing is committed until the affected
  tests are adapted and the suite is green.
- Envelope confirmed by the user 2026-09-30: success `{ "data": T }` (+ `"page": {number,size,
  total,totalPages}` for lists, 0-based); every error `{ "error": { "code", "message" } }` —
  controllers, Spring Security 401/403, framework 400s and `/error` alike, via
  `@RestControllerAdvice` + `SecurityConfig`. Stable `code` everywhere. Not wrapped: `/api/csv`,
  `/api/openapi.yaml`. DB wipe endpoints move from text/plain to JSON. Frontend: `unwrap()` in
  `api.js` returns `data` or throws `ApiError{code,message}`; the 5 raw fetches (auth, reset) too.
- DTOs with Lombok `@Getter`/`@Setter` (user choice 2026-09-30); Lombok must be added as a `provided` dependency AND as
  `annotationProcessorPaths` in maven-compiler-plugin (JDK 23+ no longer runs processors found
  on the classpath implicitly; compile runs on JDK 24, so Lombok >= 1.18.36). Generic `ApiResponse<T>` / `PageResponse<T>`.
- DB → app push instead of app → DB polling (user 2026-09-30: "lo comunica la base de datos
  hacia afuera, no nosotros hacia ella porque saturamos"). Measured: scrape status and ML
  training live IN MEMORY (`ScraperService` AtomicReferences, `PythonRunner`), so a trigger
  cannot see them. Design: one in-process status event bus (domain port) fed by (a) PostgreSQL
  triggers `pg_notify` on `scrape_run`, `scrape_run_site`, `cron_executions` (new V41), heard by
  ONE dedicated `DriverManager` connection outside Hikari, reconnecting with Resilience4j
  exponential backoff + jitter; (b) the in-memory scrape/ML state publishing directly. The bus
  feeds an SSE endpoint. Frontend replaces the 1.8 s / 2 s / 4 s polls with a fetch-stream
  reader through `authedFetch` (the access token is a Bearer header in memory, and
  `EventSource` cannot send headers). `CronJobService.tick` (30 s, time-based on `next_run_at`)
  stays. `CronJobRunner.awaitTerminal` 5 s sleep loop subscribes to the bus instead.
- Architecture: domain free of tooling (Spring, Jackson, JDBC, Playwright, servlet). Measured
  leaks: `classification/SiteRegistry` is a JDBC repository; ports throw `SQLException`
  (`ProductPort`, `MlOutputPort`, `PresetPort`, `ScrapeRunPort`, `FavoritosProtegidosException`);
  Jackson in `catalog/HistorialJson`, `ProductJson`, `MlOutputPort`, `CategoriaStatsPort`,
  `pcs/PcBuildJson`; `@Component`/`@Service`/`@Scheduled` in classification, outfits, indices,
  pcs, scheduling, identity. Fix: domain exception instead of `SQLException`, JSON mappers to
  adapters, Spring wiring via `@Bean` in `config`. New ArchUnit rule bans those imports in
  domain packages.
- Work cadence: per task, code first, then adapt that task's tests, then commit green. Tests
  must compile for `mvn test` to run at all, so tests cannot wait for the last task.
- Useless comments are deleted in every file a task touches; T1 is the final sweep.
- Retry/backoff: **Resilience4j** (`resilience4j-spring-boot3`, verified via Context7 v2.2.0:
  `enableExponentialBackoff`, `enableRandomizedWait`, `exponentialMaxWaitDuration`). Config is
  env-only (see CLAUDE.md), so properties bind from env with defaults.
- DB availability at boot: Hikari `initializationFailTimeout` / connection timeout, not a new lib.
- `@Transactional` only works if repositories get connections through
  `DataSourceUtils.getConnection` (or a `TransactionAwareDataSourceProxy`); raw
  `dataSource.getConnection()` bypasses the transaction. T4 must handle that first.
- Comment rule: keep comments that state a non-obvious *why* (constraint, gotcha, bug
  reference, ordering requirement). Delete javadoc that restates the signature, section
  banners, history/changelog prose, commented-out code.

## Scope

`scraper/` + every API consumer for T6 (`frontend/`, `docs/openapi.yaml`, e2e/perf suites, CLI
if it calls the API), one new migration V41 (T3). No `CLAUDE.md`.

## TDD

Strict TDD OFF for this feature (source: explicit user choice 2026-09-30, tests adapted after implementation). Runner:
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
(`-Dtest=...` during development; always `clean`; grep output for `ERROR]` / `BUILD FAILURE`).
T1/T2 are pure refactors: no new tests, existing suite untouched is the check.

## Tasks

Order re-planned 2026-09-30 after the polling and domain maps.

- [x] T6a Backend envelope: `ApiResponse<T>`, `PageResponse<T>`, `ApiError`, Lombok DTOs, `@RestControllerAdvice`, SecurityConfig 401/403, all 87 handlers typed
- [x] T6b Consumers: frontend `unwrap()`/`ApiError` + 5 raw fetches + components reading `.error`/`.mensaje`, CLI `rest.py`, `tests/e2e`, perf suites, `docs/openapi.yaml`, `docs/API_REFERENCE.md`, `docs/FRONTEND_AUTH_CONTRACT.md`
- [x] T6c Adapt Java + frontend tests to the envelope; suite green; commit T6
- [x] T8 Domain free of tooling + ArchUnit rule
- [x] T4 ACID: `TransactionAwareDataSourceProxy` + `@Transactional` replace manual commit/rollback (12 files)
- [x] T5 Caffeine + `@Cacheable` (`/api/grupos` and other per-request re-derivations), eviction on catalog reload; reconcile with `CachingCatalogQueryPort`
- [ ] T3 Push instead of poll: V41 triggers, LISTEN listener + Resilience4j backoff, status bus, SSE, frontend stream reader, remove hand-rolled sleeps, Hikari boot timeout
- [ ] T7 SOLID: split `ApiController` (65 handlers) by resource
- [ ] T1 Final comment sweep across `ar.scraper`
- [x] ~~T2 Remove unused Lombok dependency~~ — dropped: user wants Lombok DTOs

## Acceptance

- Full suite green on every commit; test count never drops.
- Backend boots for real (jar, grep WARN/ERROR) after T3, T4, T6.
- `tests/e2e/run-e2e.sh` green after T6 (JSON contract).

## Progress

Branch `refactor/backend-hardening` from master 912d19a.

### T6a handoff

`clean compile` OK on JDK 24 (Lombok 1.18.38 pinned via `<lombok.version>`, Boot 3.2.5 manages 1.18.32; `provided` dep + `annotationProcessorPaths`). `src/test` untouched (does not compile yet: T6c).

**Envelope types** (`ar.scraper.web.api`): `ApiResponse<T>{data, page}` (`ok(T)`, `page(List<T>, PageMeta)`, NON_NULL), `PageMeta{number,size,total,totalPages}` (0-based, `PageMeta.of(number,size,total)`), `ApiError{error:{code,message,details?}}` (`ApiError.of(code,message[,details])`), `ApiException(HttpStatus, code, message[, details])` with `.withHeader(name,value)`, `ApiExceptionHandler` (`@RestControllerAdvice extends ResponseEntityExceptionHandler`), `ApiErrorController` (replaces Boot `/error`). DTOs live in `ar.scraper.web.dto` (`OpResult{ok,mensaje}`, `MensajeDto`, `ScrapeDtos`, `CatalogoDtos`, `MlDtos`, `MarcasPicksDtos`, `ComparadorDtos`, `FinanciacionDtos`, `PcsDtos`, `OutfitsDtos`, `AgentDtos`, `CronDtos`, `UsuariosDtos`, `AuthDtos`, `ServiceStatus`), 15 files / 62 classes, Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor` (+`@Builder` on `ScrapeDtos.Status`/`Interrumpida`). 84 handlers converted (csv and openapi.yaml untouched). 401/403 in `SecurityConfig` write `ApiError` with a static `ObjectMapper`.

**Shape changes beyond wrapping** (everything is under `data`; error bodies are now always `{error:{code,message}}`):

| Endpoint | Old -> new |
|---|---|
| `GET /api/data` | `{meta:{...,total,pagina,pageSize,totalPaginas,facets,marcas,errores},productos}` -> `data:{meta:{moneda,precioMin,precioMax,rangMin,rangMax,fecha,facets,marcas,errores?},productos}` + `page:{number,size,total,totalPages}`. `page` QUERY param stays 1-based (clamped to >=1); `page.number` = query-1. 204 still returned when the catalog is empty (no body). |
| `GET /api/facets` | root facets object -> `data` (same fields, no `rubros`). 204 kept. |
| `GET /api/recomendados` | `{page,size,total,items}` -> `data:[items]` + `page`. Query `page` 1-based (clamped); `page.number` 0-based. |
| `GET /api/grupos` | `{total,page,size,grupos}` -> `data:[grupos]` + `page`. Query `page` already 0-based, unchanged. |
| `GET /api/favoritos`, `/api/usuarios`, `/api/pcs/saved`, `/api/outfits/saved` | bare array -> `data:[...]` |
| `GET /api/cron` | `{jobs:[...]}` -> `data:[jobs]` |
| `GET /api/cron/{id}/executions` | `{executions:[...]}` -> `data:[executions]` |
| `GET /api/producto/{key}` | 404 was an empty body -> `no_encontrado` error |
| `DELETE /api/db/productos`, `/api/db/ml` | text/plain -> `data:{mensaje}` |
| `GET /api/db/export`, `POST /api/db/import` | 410 `{error:text}` -> 410 `recurso_eliminado` |
| `GET /api/tendencias` | 503 `{error:"ml_failed"}` -> 503 `error.code="ml_failed"` (code kept) |
| `POST /api/ml/aplicar` / `entrenar` | Map body -> `data:{status,mensaje?}` (`entrenar`: `{status:"started"}`) |
| `POST /api/agent/apply` 422 stale | `{ok:false,codigo:"conflicto_stale",mensaje,actual}` -> `error:{code:"conflicto_stale",message,details:{actual}}` (frontend must read `error.details.actual`) |
| `POST /api/agent/chat` errors | `{mensaje[,codigo]}` -> `error:{code,message}` |
| `GET /api/agent/models` | `{available,default}` -> `data:{available,default}` |
| `POST/PUT /api/cron` | on success returns the job under `data`; the never-hit fallback `{ok,id}` is now a 500 `error_interno` |
| `POST /api/auth/login`, `/refresh` | `{accessToken,tokenType,expiresIn[,csrfNonce]}` -> `data:{...}`; `Set-Cookie` unchanged |
| `GET /api/auth/me` | `{username,roles}` -> `data:{...}` |
| `DELETE /api/auth/refresh` | `{cerrada}` -> `data:{cerrada}`; cookie still cleared |
| `POST /api/auth/password-reset/request` (202) | `{mensaje}` -> `data:{mensaje}` |
| `GET /` | `{service,status}` -> `data:{service,status}` |
| Mutations returning `{ok:true[,mensaje]}` | -> `data:{ok,mensaje?}` (`OpResult`); their failure `{ok:false,mensaje}` is now an `ApiError` |
| `POST /api/pcs/save`, `/api/outfits/save` | `{ok,id,nombre,totalEstimado}` -> `data:{same}`; failure 500 `error_interno` |
| `GET /api/suplementos/tipos` | `{tipos:[{tipo,grupo|null}]}` -> `data:{tipos}` (grupo explicit null kept) |

**Error codes** (status; where):
- `solicitud_invalida` (400): every hand-validated 400 in Scrape/Favoritos/Financiacion/Pcs/Outfits/Recomendados/Cron/Agent handlers; also Spring 400s (missing param, type mismatch, unreadable body, validation) via the advice.
- `no_encontrado` (404): producto/{key}, presets activar/eliminar, saved pc/outfit delete/rename, cron job get/put/delete/run-now; also framework 404.
- `conflicto` (409): DB wipe with protected favoritos.
- `scrape_en_curso` (409): db wipe, ml/aplicar, presets CRUD, agent chat/apply, cron run-now.
- `ml_en_curso` (409 / 400): ml/aplicar (scoring in flight), ml/entrenar (409 race, 400 pre-check kept).
- `recurso_eliminado` (410): db export/import.
- `ml_failed` (503): tendencias (existing code kept).
- `error_interno` (500): save pc/outfit failure, agent apply failed write, advice fallback, `/error` 5xx.
- `agente_no_disponible` (500), `proveedor_no_disponible` (502), `conflicto_stale` (422): agent.
- `no_autenticado` (401): entry point, `SinSujeto`, `/error` 401. `sin_permiso` (403): access denied, `/error` 403.
- Kept as-is from before: `credenciales_invalidas` 401, `demasiados_intentos` 429 (+`Retry-After`), `csrf_invalido` 403, `sesion_invalidada` 401 and `refresh_invalido` 401 (+`Set-Cookie` clear), `reseteo_invalido` 400, and the usuarios codes `faltan_campos`, `password_corta`, `rol_invalido`, `username_tomado` 409, `no_existe` 404, `ultimo_admin` 409.
- Advice only: `metodo_no_permitido` 405, `no_aceptable` 406, `media_no_soportada` 415, `payload_demasiado_grande` 413, `servicio_no_disponible` 503.

**Left dynamic JSON** (`ApiResponse<JsonNode>`/`ObjectNode`/`Map`, and why):
- `/api/tendencias`, `/api/historial`: trainer/DB-built JSON (`ObjectNode` from `HistorialJson`, ML output tree) -> `JsonNode`.
- `/api/producto/{key}` `producto` field, `/api/favoritos`, `/api/recomendados` items: rows written by `ProductJson.escribir` (`ObjectNode`); domain Jackson helper, moves in T8.
- `/api/pcs/builder`: `PcBuildJson.toJson` -> `ApiResponse<ObjectNode>` (shared with the agent tool).
- `/api/pcs/saved`, `/api/outfits/saved`: `List<Map<String,Object>>` straight from the ports; typing needs a persistence-layer change.
- `/api/ml/renormalizar`: `Map<String,Integer>` counters from `ResultAggregator`.
- `/api/ml/estado` `textMeta`: `JsonNode` (contents of `_models/text_meta.json`).
- `/api/agent/chat`: existing record `AgentChatResponse`.

**Deviations / notes**
- `ApiError.error` carries an optional `details` (NON_NULL) beyond `{code,message}`: needed for `conflicto_stale.actual`, which the frontend uses.
- `ApiResponse.error(...)` factory lives on `ApiError.of(...)` instead (a success envelope type cannot carry an error).
- `MaxUploadSizeExceeded` maps to 413 `payload_demasiado_grande`, not 400.
- Removed catch-all `catch (Exception)` blocks in DbAdmin/savePc/saveOutfit: failures propagate to the advice (500 `error_interno`, message no longer echoes `e.getMessage()`). `SQLException` from ports is wrapped in `IllegalStateException` in `DbAdminEndpoints` (T8 removes it from the ports).
- `updateConfig` with a non-numeric value still throws `NumberFormatException` -> 500 (unchanged behaviour, now enveloped).
- Not converted: nothing. Not verified at runtime (compile only; no test suite run, no boot).

### T6b handoff

Builds: backend `clean compile` exit 0 (0 `ERROR]`), `VITE_API_BASE_URL=http://localhost:3000 npm run build` exit 0 (the build refuses to run without that var), `py_compile` OK on `cli/core/rest.py`, `tests/e2e/*.py`, `tests/perf/locust/*.py`. No test suite run (T6c). CLI tests not runnable here (no pytest).

**Part 1 (backend)**: `page` query param is 0-based on `/api/data`, `/api/recomendados` (default 0, negatives clamp to 0); `/api/data` converts to the 1-based `CatalogQueryPort.buscar` at the endpoint (port untouched). Empty catalog: `/api/data` -> 200 `{data:{meta(empty facets, marcas {}),productos:[]},page:{total:0}}`, `/api/facets` -> 200 empty `FacetsDto` (`FacetsDto.vacio()`). Also: `/api/recomendados` with no snapshot yet returns 200 empty page instead of 204 (not requested; same reason).

**Frontend** (`api.js`): `ApiError{code,message,details,status}`, `unwrap(r)` -> `data` (204/empty -> null), `unwrapPage(r)` -> `{data,page}`; internal `softUnwrap` keeps "null/[] on failure", `opResult` keeps `{ok,mensaje}` for mutations. Return values components see are unchanged EXCEPT:
- `listCronJobs()` / `fetchCronExecutions()` now return the array (were `{jobs}` / `{executions}`); `CronjobsPage.jsx`, `CronJobCard.jsx` adapted.
- `fetchData`, `fetchGrupos`, `fetchRecomendados` rebuild the old shapes from `data`+`page` (`meta.total/pagina/pageSize/totalPaginas`, `{grupos,total,page,size}`, `{items,total,page,size}`); `pagina` is now 0-based.
- `fetchRecomendados` default page is 0. UI page counters moved to 0-based: `AppLayout.jsx` (`pag` starts 0, `buildParams(0)`), `RecomendadosPanel.jsx`, `OportunidadesBadgePage.jsx`, `MarcasPanel.jsx`.
- `applyProposal` keeps `{ok,mensaje,codigo,actual}` (`actual` from `error.details.actual`); `askAgent` keeps `{error:true,mensaje,codigo}`; `usuariosFetch` keeps `{ok,status,body}` with `body={error:code,mensaje}` on failure. `limpiarCatalogo`/`limpiarMl` still return the raw Response (SplashPanel reads `r.ok`/`r.status`).
- `authSession.js` (me, refresh, login) reads `.data` and `error.code`; `ForgotPassword.jsx`/`ResetPassword.jsx` never read the body (only `res.ok`), so no change. `e2e/accounts.js`, `global-setup.js`, `scrape-poller.spec.js` (stubs now `{data:...}`) adapted. AppLayout, FinanPanel, BuySignal, ApiDocsPanel, AgentChatPanel, useInterruptedRun, useScrapeStatusPolling, readStatus, MlStatusPanel, Topbar needed no change (they consume the compat shapes above).

**Other**: `cli/core/rest.py` (`_data()` unwraps, falls back to the raw body if there is no `data` key; `_error_suffix()` adds `[code] message` to HTTP errors). `tests/e2e/_http.py` gained `.data()` / `.error_code()`; all `.json()[...]` reads in the e2e tests moved to them. Locust `conftest.py` reads `data.accessToken`; `recomendados` probes use `page=0` (locust + jmeter `Config.java`; jmeter token slicing still works on the nested body, comment updated). `docs/openapi.yaml`: `components.schemas` `ApiResponse`/`ApiPagedResponse`/`PageMeta`/`ApiError`/`ApiErrorBody`; 80 success and 29 error responses reference them, `components.responses` errors reference `ApiError`, `Page` param 0-based; still 71 paths / 85 operations. `docs/API_REFERENCE.md` (new "Envelope de respuestas" section + fixed examples), `docs/FRONTEND_AUTH_CONTRACT.md` (new shape section + flows).

**Left**: per-operation `data` schemas in openapi are still prose in the response description (only the envelope is a schema); `tests/perf/*/README.md` still tell the old `recomendados?page=0` 500 story (historical finding); `tests/cli/*` mocks use bare bodies and still pass through the `_data` fallback (not run); comment cleanup only done in `api.js`, `authSession.js`, `rest.py` touched hunks, not the large components (T1 sweep). Frontend unit tests (`*.test.js(x)`) untouched, expected red until T6c.

### T6c evidence

**Baseline (master 912d19a, clean worktree)**: backend 3089 tests / 0 failures / 0 errors / 7 skipped; frontend 401 tests (48 files).

**Final (this branch)**: backend 3119 / 0 / 0 / 7 (`mvn clean test`, BUILD SUCCESS, +30); frontend 427 (49 files, +26); CLI (`_tools/cli-venv`, `pytest tests/cli`) 282 passed (278 before adding 4; no master baseline taken); e2e `run-e2e.sh` 51 API + 26 browser passed.

**Tests adapted, same behavior under the new shape**: ~55 backend test files. Handler tests read bodies through `web/support/Wire` (`data`, `page`, `error`, `body`, `answer`, `apiError`): `Wire.answer` turns a thrown `ApiException` into the response the advice would send, so status-based assertions kept their meaning. `page` args in `controller.data(...)` / `recomendados(...)` shifted to 0-based (`data(1,24)` -> `data(0,24)`); the page-clamp, pagination and badge-membership tests were rewritten for 0-based semantics with the same intent. `AgentTest` standalone MockMvc now registers the advice. No assertion was deleted or @Disabled.

**Production fixes forced by tests (all in commit 1)**:
1. `ApiExceptionHandler` declared its own `MaxUploadSizeExceededException` handler, ambiguous with `ResponseEntityExceptionHandler`: the Spring context failed to load (every `@WebMvcTest` red). Now mapped in `mensajePara`.
2. `CatalogoDtos.Ml.zScore` serialized as `zscore` (Lombok `getZScore()`); the frontend reads `ml.zScore`. Fixed with `@JsonProperty("zScore")`.
3. `/api/data?page=Integer.MAX_VALUE` wrapped to a negative 1-based page and returned the first page instead of an empty one. Clamped in `CatalogoEndpoints`.
4. `BackendLayeringArchTest.grafoSinCiclos` red: `security -> web.api.ApiError` closed a cycle. `ApiError`, `ApiResponse`, `PageMeta`, `ApiException` moved to the leaf package `ar.scraper.api`; `ApiExceptionHandler` and `ApiErrorController` stay in `ar.scraper.web.api`. (T6a handoff paths now read `ar.scraper.api`.)

**New tests**: backend `ApiEnvelopeSerializationTest` (data-only, `page` omitted when null, PageMeta arithmetic, ApiError details, every DTO field keeps its JSON name, `/api/data` row keys), `ApiExceptionHandlerTest` (ApiException + details + headers, missing param 400, type mismatch 400, bad/missing JSON 400, 405, 415, 413, SinSujeto 401, fallback 500 without leaking the message, unknown route 404), `ApiErrorControllerTest` (`/error`), `SecurityErrorBodyTest` (401 anonymous / bad token, 403 role and deny-all, ERROR dispatch permitted), `ApiControllerPageClampTest` (+2: empty catalog and empty facets answer 200 envelopes), 500 tests in `ApiControllerStatusScrapeTest` also assert no message leak. Frontend `api.envelope.test.js` (26: ApiError, unwrap, unwrapPage, compat shapes, mutations, agent, usuarios). CLI `test_rest_auth.py` (+4: unwrap, error code suffix, login rejection, non-envelope error body).

**Boot check** (jar from `mvn clean package -DskipTests`, run with `java -jar` on JRE 21 against the dev DB; `scraper/scraper.jar` not touched): `Started App in 5.596 seconds`; the only WARN is `UserDetailsServiceAutoConfiguration` (default generated password notice, not introduced here); no ERROR. `GET /` 200 `{"data":{"service":"fashion-scraper-api","status":"ok"}}`; `GET /api/status` no token 401 `{"error":{"code":"no_autenticado","message":"Falta un access token válido."}}`; unknown route without token 401, with an ADMIN token 403 `sin_permiso`; bad login 401 `credenciales_invalidas`; `GET /api/data?page=0&size=2` 200 envelope; `page=abc` 400 `solicitud_invalida`; `/api/auth/me` `{"data":{"username":"e2e-admin","roles":["ADMIN"]}}`.

**e2e**: `tests/e2e/run-e2e.sh` -> 51 API + 26 browser passed. Two environment traps, not code: (a) the dev DB has an overdue cron job, so every backend boot starts a real scrape; killing that backend leaves an INTERRUPTED run whose banner (`role=alert`) failed `roles.spec.js:134` and the poller spec. Runs 32-34 (created by these boots) were set to CANCELLED and both cron jobs were disabled during the runs and re-enabled after (both were `enabled=true` before); their `last_run_at` moved. (b) `scrape-poller.spec.js` raced the splash mount-time status read (passes on a clean master stack by ~13 ms of margin, failed 3/3 here); it now waits for the launch button to be enabled before flipping the stub to RUNNING, assertions unchanged.

**Commits**: 8373b10 `feat(api)`, e155d05 `feat(frontend)`, 8f1cdda `chore(api-consumers)`, 86d9bce `docs(api)` (`docs/openapi.yaml` is in the first commit because backend tests read it).

### T8 evidence

Baseline 3119 / 0 / 0 / 7. `mvn clean test` after each unit (BUILD SUCCESS every time):

| Unit | Commit | Tests |
|---|---|---|
| 1 `refactor(ports): translate SQLException into a domain exception` | 99a0546 | 3119 / 0 / 0 / 7 |
| 2 `refactor(json): move Jackson mappers out of the domain` | 06b762f | 3119 / 0 / 0 / 7 |
| 3 `refactor(classification): load sites through a port` | ba3e0a1 | 3119 / 0 / 0 / 7 |
| 4 `refactor(config): wire domain services as beans and schedulers as adapters` | 45c0a8a | 3120 / 0 / 0 / 7 (+1: `ningunMetodoBeanPideUnNoBean`) |
| 5 `test(arch): forbid tooling imports in domain packages` | ccdfefc | 3121 / 0 / 0 / 7 (+1: `dominioSinHerramientas`) |

`dominioSinHerramientas` found zero violations after unit 4; negative control: an `@Component` on `model.PersistenciaException` turns it red, reverted. No assertion was deleted, weakened or `@Disabled`.

**Boot check** (after unit 4; `clean package -DskipTests`, `java -jar` on JRE 21, profile `dev`, dev DB): `Started App in 4.803 seconds`; the only WARN is `UserDetailsServiceAutoConfiguration`; no ERROR; `GET /` 200 `{"data":{"service":"fashion-scraper-api","status":"ok"}}`; `GET /api/status` without a token 401. The first two attempts died on missing env (`AUTH_JWT_SECRET`, then `ADMIN_BOOTSTRAP_USERNAME`), not on code; the run used throwaway secrets and the existing `admin`/`cli` accounts (seeding is `ON CONFLICT DO NOTHING`, no new usuario rows). Cron jobs 3 and 4 were set `enabled=false` for the boot and back to `true` afterwards; their `last_run_at`/`next_run_at` are unchanged; no scrape_run was created (latest ids 33/34 still CANCELLED).

**Deviations from the plan**
- Unit 1: adapters translate through a private `xxxSql` method plus a public wrapper using the new `db.Sql.traducir` helper, instead of editing every body. `DbAdminEndpoints` does not catch `PersistenciaException`: only `FavoritosProtegidosException` is caught, any other one propagates to the advice as the generic 500 (same result as the old `IllegalStateException`). `limpiarProductos` now rolls back on `SQLException | RuntimeException`.
- Unit 3: `SiteRegistry` kept `@Component` until unit 4 (it moved to `ClassificationConfig` there). `forTesting` builds the registry over a fixed `SiteSource`.
- Unit 4: the DatabaseService fixture is `ar.scraper.db.TestDatabaseServices` (src/test, same package, not `db.support`) because the repositories are package-private. `DatabaseService` gained a `RubroResolver` ctor param and a `rubroResolver()` accessor; `ProductRepository` takes the bean; `AgentEndpoints` receives `db.rubroResolver()` from `ApiController` (the existing accessor pattern) and no longer builds it lazily. `ApiControllerAgentTest` stubs `db.rubroResolver()` instead of `db.siteRegistry()`. `CronApiControllerTest` was left mocking `CronJobService` (still works). `unBeanConVariosConstructoresMarcaCual` needed no skip: it only scans `@Component` classes, and `@Bean`-produced classes are not in that list. Added one more wiring test (`ningunMetodoBeanPideUnNoBean`). `identity/` moved to `security/` (arch lists still name `ar.scraper.identity..` as forbidden, harmless).
- Docs updated in the same commits: `docs/STRUCTURE.md`, `docs/ARCHITECTURE.md`, `docs/LLM_EMBED.md`.
- `ar.scraper.json` and `ar.scraper.ml` (the two moved ports) are outside the domain list; `CronJobRunner` still imports logback (not in the banned set).

### T4 evidence

Baseline 3121 / 0 / 0 / 7 (HEAD 410e0c9). `mvn clean test` before each commit, BUILD SUCCESS every time, 0 `ERROR]` lines.

| Commit | Hash | Tests |
|---|---|---|
| prelude `refactor(scheduling): capture cron run logs through a port` | a53cce2 | 3121 / 0 / 0 / 7 |
| 1 `feat(db): route JDBC connections through Spring transactions` | f05d7c8 | 3125 / 0 / 0 / 7 (+4 `TransactionWiringTest`) |
| 2 `feat(db): make repository write units atomic by declaration` | 4d91b0e | 3140 / 0 / 0 / 7 (+3 `TransactionalBeansTest`, +12 `TransactionalUnitsRollbackTest`) |
| 3 `feat(db): make scrape-run and product writes atomic by declaration` | c4505a1 | 3147 / 0 / 0 / 7 (+7 rollback tests) |
| 4 `refactor(security): replace hand-rolled user transactions` | b043d53 | 3151 / 0 / 0 / 7 (+3 `PasswordResetRollbackTest`, +1 `AdminSeederRollbackTest`) |
| 5 `docs(db): document declarative transactions` | 1f73a2c | 3151 / 0 / 0 / 7 |

**Boot check** (`clean package -DskipTests`, `java -jar` on JRE 21, profile `dev`, dev DB, env: throwaway `AUTH_JWT_SECRET`/`ADMIN_BOOTSTRAP_*`/`CLI_SERVICE_ACCOUNT_*` with the existing `admin`/`cli` accounts; `CLI_SERVICE_ACCOUNT_USERNAME` is also required; cron jobs 3 and 4 disabled for the boot and back to `true`). After commit 1 and after commit 4:
- `HikariConfig maximumPoolSize.................10`, `After adding stats (total=10, active=1, idle=9, waiting=0)` (configured `spring.datasource.hikari.maximum-pool-size=10`).
- `o.f.c.i.c.DbValidate Successfully validated 42 migrations`.
- `ar.scraper.App Started App in 4.771 seconds` (commit 1), `Started App in 4.039 seconds` (commit 4).
- Only WARN: `UserDetailsServiceAutoConfiguration` (generated password notice); no ERROR.
- `GET /` 200 `{"data":{"service":"fashion-scraper-api","status":"ok"}}`; `GET /api/status` without token 401.
- `usuario` count 222 before and after (no rows created); latest scrape_run ids 32-34 still CANCELLED, no run created.
- A first attempt with a 1 s kill logged `ProxyConnection ... marked as broken SQLSTATE(08006)` from `IndiceRepository.guardar`: the boot-time INDEC refresh interrupted by the kill, not the change. Later runs wait 12-20 s.

**Negative controls** (annotation removed, rollback test run, annotation restored): `SavedPcsRepository.guardarPc` -> `guardarPcLeavesNoHeader` red; both `ProductRepository.upsertProductos` overloads -> `upsertProductosFailureIsAllOrNothing` and `perSiteRowsSurviveAFailedFinalUpsert` red; `PasswordResetService.confirmar` -> all 3 `PasswordResetRollbackTest` red.

**Mechanics**: `TransactionConfig` declares `HikariDataSource` (`@FlywayDataSource`, `@ConfigurationProperties("spring.datasource.hikari")`), a `DataSourceTransactionManager` on the raw pool and a `@Primary TransactionAwareDataSourceProxy`; `@EnableTransactionManagement(proxyTargetClass = true)` (an explicit annotation makes Boot's auto-config back off and the default would be JDK proxies). Tests: `TestTransactions` (proxy + manager + aware DS), `TestRepositories`, `FaultInjection` (a trigger raises or skips a statement), `TestDatabaseServices` proxies each transactional repository; `TransactionalBeansTest` checks declarations, real-config context proxies and fixture proxies. `Sql.marcarRollback()` marks rollback-only on sentinel branches.

**Deviations from the plan**
- Prelude: `CronJobRunner` keeps its 3-arg constructor (defaults to `RunLogCapture.NONE`) so `CronJobRunnerTest` compiles untouched; `SchedulingConfig` uses the 4-arg one. Stale `ar.scraper.identity..` removed from the two arch lists.
- Commit 2 also covers `SitiosRepository.guardarSitio/eliminarSitio` (atomic; `SiteRegistry.reload()` registered as `afterCommit`), not listed in the plan's commit 2 but implied by the registry rule. `guardarSitio` previously swallowed each of its two statements independently.
- Commit 2/3 carry their own rollback tests (COMMIT-5) instead of deferring all of them to commit 5, so commit 5 is docs only and is named `docs(db): ...`, not `test(db): ...`.
- `actualizarCategoria` is a single statement and was left untouched (the plan listed it via `updateNormalizacion`, which it does not use).
- Commit 4: the bootstrap transaction is `UsuarioRepository.sembrarAdministracion` (a repository method, not a new service class); `sembrarCuenta`/`adoptarFilasSinDueno` became private. `PasswordResetService` lost its `DataSource` constructor parameter (both constructors). `PasswordResetRepositoryTest`: two tests that passed a `Connection` now use a `TransactionTemplate` rolling back / the no-connection overload, same assertions.
- Test edits that were not assertion changes: ~14 test files build account repositories through `TestRepositories` instead of `new`; `ScrapeRunRepositoryTest` and `ScrapeRunResumeRepositoryTest` build the repository through the transactional proxy.
- `guardarSitioWritesNeitherTable` (commit 2) read leftover `sitio` rows from other tests and was order-dependent; it passed in commits 2-3 by ordering luck and is fixed in commit 4.
- Behavior change to know: a failure to OPEN a transaction (database down) now throws instead of returning the sentinel (`-1`, `false`, `UpsertStats(0,0,0,0)`). Sentinel returns after a failure inside the unit are unchanged.
- `ScraperService` per-site loops, `ResultAggregator.agregar`, ML scoring, `CronJobRunner`, cron execution-log writes, startup seeders and `PasswordResetService.despachar` stay non-transactional.

### T5 evidence

Baseline 3151 / 0 / 0 / 7 (HEAD c637b6e). Full `mvn clean test` before each commit, BUILD SUCCESS every time, 0 `ERROR]` lines.

| Commit | Hash | Tests |
|---|---|---|
| 1 `feat(cache): add a bounded Caffeine cache manager` | 2cbd4b8 | 3157 / 0 / 0 / 7 (+6 `CacheConfigTest`) |
| 2 `feat(catalog): version the in-memory snapshot and announce changes` | b586fc1 | 3168 / 0 / 0 / 7 (+11 `ScraperServiceSnapshotVersionTest`) |
| 3 `feat(cache): cache product grouping per snapshot` | 7ca01ce | 3188 / 0 / 0 / 7 (+13 `CatalogoDerivadoCacheTest`, +4 `CacheUsageArchTest`, +3 `ComparadorGruposCacheTest`) |
| 4 `feat(cache): cache brand browser and best-per-category per snapshot` | 49eff15 | 3199 / 0 / 0 / 7 (+5 in `CatalogoDerivadoCacheTest`, +3 `SnapshotCacheIntegrationTest`, +3 `MarcasPicksCacheTest`) |
| 5 `docs(cache): document snapshot caches and their eviction` | f574ab4 | 3199 / 0 / 0 / 7 |

**Negative control**: removing `@Cacheable` from `CatalogoDerivadoCache.grupos` turns `CatalogoDerivadoCacheTest` red (4 failures); restored.

**Boot check** (after commit 3; `clean package -DskipTests`, `java -jar` on JRE 21, profile `dev`, dev DB; env from the gitignored `tests/e2e/.e2e-secrets.env`, existing `e2e-admin`/`e2e-cli-service` accounts; cron jobs 3 and 4 disabled for the boot and back to `true`): `Started App in 6.346 seconds`; the only WARN is `UserDetailsServiceAutoConfiguration`; no ERROR. Logged in as `e2e-admin`; `GET /api/grupos?minSitios=2&size=24` three times: 0.347 s (cold), 0.016 s, 0.014 s (same 26134-byte body); a different filter (`q=zapatilla`) 0.044 s. `usuario` count 222 before and after; latest `scrape_run` id 34, none RUNNING/INTERRUPTED. No `[CACHE]` log line appeared: the line is written on eviction and nothing changed the catalog during the check (no mutation was triggered on the dev DB on purpose; eviction is covered by `CatalogoDerivadoCacheTest` and `SnapshotCacheIntegrationTest`).

**Mechanics**: `ScraperService.publicarCambio()` runs after every `lastResult`/`servedResult` assignment, compares the served view (`getLastResult()`) with the last announced one, and only then bumps `snapshotVersion` and publishes `CatalogoActualizado(version)`; the progressive rebuild during a run is therefore silent. `/api/ml/aplicar` does not swap the snapshot (verified in `MlEndpoints.mlAplicar`), so it publishes nothing. `CatalogCacheEvictor` clears every cache through `CacheManager` and logs each cache's hit ratio in one INFO line.

**Deviations from the plan**
- Cache keys do not `trim` (only lower-case + blank to ""): the endpoint filters compare raw text, so trimming would change results. Decided after reading the filters.
- `sync=true` forbids `unless`, so the cached methods return an empty list, never null, when there is no snapshot (`/api/grupos` keeps its 204 through the endpoint's own null check; the race window yields an empty page). Safe because loading a snapshot bumps the version.
- Eviction goes through `CacheManager` in the evictor, not `@CacheEvict(allEntries=true)`.
- The marcas/mejores computation moved out of `MarcasPicksEndpoints` into `web.cache.MarcasPicksView` (same code, static) so the cache bean can call it; the endpoints keep only the 204 check and the bean call.
- `ScraperService` keeps its 8-arg constructor (no-op publisher) and gains a 9-arg `@Autowired` one; `ApiController`/`ComparadorEndpoints`/`MarcasPicksEndpoints` keep test-compatible constructors that build an uncached `CatalogoDerivadoCache`. No existing test was edited.
- `CacheConfig` is public (the test lives in another package); cache names live in `config.CacheNames` so `config` does not depend on `web`.
- Tendencias/indices were not cached (not trivial).
- Comment cleanup: new files carry only why-comments; I did not sweep the touched hunks of `ScraperService`/`ApiController` (left to the T1 sweep).
- Eviction log line not observed at runtime (see boot check).
- New test `CacheUsageArchTest` (web.cache) forbids `GroupingService.agrupar` outside the bean, cache annotations outside it, and the bean depending on `ActorResolver`.

## Next step

T3: push instead of poll (V41 triggers, LISTEN listener, status bus, SSE).

### Upsert sentinel decision APPLIED (user, 2026-09-30) in 6f15a11

User chose "0 nuevos": when a transaction cannot even be opened (DB down), `upsertProductos` /
`upsertParcial` must return the sentinel (`UpsertStats(0,0,0,0)`) again instead of propagating
(T4 changed this; caller `ResultAggregator.java:143`). Fix before T3: catch at the port wrapper
outside the `@Transactional` boundary, plus a test with an unreachable DataSource.

Applied in 6f15a11: `ProductRepository` opens the transaction with a `TransactionTemplate` (extra ctor arg `PlatformTransactionManager`), catching open failures outside it. Test: `ProductRepositoryNoTransactionTest` (negative control red/green). Suite 3201 / 0 / 0 / 7.

Next: T3 (plan in Engram `odd/backend-hardening/t3-plan`), T7, T1.
