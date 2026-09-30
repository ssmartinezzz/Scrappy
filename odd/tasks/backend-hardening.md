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
- [x] T3a Push instead of poll, backend: Hikari/Flyway boot retries, V41 `pg_notify` triggers, status bus, cron wait on the bus, Resilience4j `withRetry`, LISTEN listener with backoff, `GET /api/events` SSE
- [x] T3b Push instead of poll, frontend: fetch-stream reader through `authedFetch` replaces the 1.8 s / 2 s / 4 s polls; unit tests + `tests/e2e` (see "T3b handoff")
- [x] T7 SOLID: split `ApiController` (65 handlers) by resource
- [x] T1 Final comment sweep across `ar.scraper`
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

### T3a evidence

Baseline 3201 / 0 / 0 / 7 (HEAD 89e2a7d). Full `mvn clean test` before each commit, BUILD SUCCESS, 0 `ERROR]` lines.

| Commit | Hash | Tests |
|---|---|---|
| 1 `feat(boot): wait for the database with bounded retries` | ce0ca55 | 3203 / 0 / 0 / 7 (+2 `DatabaseBootRetryConfigTest`) |
| 2 `feat(db): notify status changes from the database` | 1d9124f | 3211 / 0 / 0 / 7 (+6 `V41StatusNotifyTest`, +2 `V41RollbackRoundTripTest`) |
| 3 `feat(status): publish scrape and ML status on an in-process bus` | abf7c33 | 3221 / 0 / 0 / 7 (+6 `InProcessStatusEventsTest`, +3 `ScraperServiceStatusEventsTest`, +1 `PythonRunnerStatusEventsTest`) |
| 4 `refactor(scheduling): wait for scrape completion on the status bus` | 443a00a | 3231 / 0 / 0 / 7 (+7 `CronJobRunnerStatusBusTest`, +3 `ScraperServiceRetryBackoffTest`) |
| 5 `feat(db): listen for database status notifications with backoff` | f279e8e | 3237 / 0 / 0 / 7 (+6 `DbNotificationListenerTest`) |
| 6 `feat(api): stream status events over SSE` | 89f3844 | 3258 / 0 / 0 / 7 (+6 `ClientQueueTest`, +5 `StatusEventJsonTest`, +4 `EventsControllerTest`, +6 `SseRealPortTest`) |

No existing assertion was deleted, weakened or `@Disabled`; `CronJobRunnerTest`, `ScraperServiceRetryTest`, `ScraperServiceCancelRetryTest` pass untouched.

**Negative controls** (break, run, restore): V41 trigger without its `status IS NOT DISTINCT FROM OLD.status` early return -> `onlyStatusChangesAreAnnounced` red. `DispatcherType.ASYNC` removed from `SecurityConfig` -> `timeoutEndsTheStreamCleanly` red, log shows `AccessDeniedException: Access Denied` on the emitter's re-dispatch. Completing the emitter from a virtual thread in `onTimeout` -> the same test red (`IOException: closed`): Spring saw the callback return without a result and dispatched `AsyncRequestTimeoutException`; reverted to a synchronous `complete()`.

**Boot check** (`clean package -DskipTests`, `java -jar` on JRE 21, profile `dev`, dev DB, env from the gitignored `tests/e2e/.e2e-secrets.env`, existing `e2e-admin` account; cron jobs 3 and 4 disabled for the boot and back to `enabled=true` after, `last_run_at`/`next_run_at` unchanged):
- `flyway_schema_history`: latest `41|t` (V41 applied to the dev DB by this boot; it was `40` before).
- `Started App in 5.121 seconds`; only WARN is `UserDetailsServiceAutoConfiguration`; no ERROR.
- `17:21:11 ... a.s.d.DbNotificationListener [DB-LISTEN] listening on 'status_events'`; `pg_stat_activity` shows one backend with `application_name='scrappy-listen'`.
- `HikariConfig` (second boot with `--logging.level.com.zaxxer.hikari=DEBUG`): `connectionTimeout...............30000`, `initializationFailTimeout.......60000`, `maximumPoolSize.................10`: the explicit `TransactionConfig` pool binds the new properties.
- `curl -N GET /api/events` with the `e2e-admin` token: `HTTP/1.1 200`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`, `Content-Type: text/event-stream`; first event `event:snapshot` with `data:{"status":{"status":"DONE","mensaje":"Datos restaurados: 22141 productos",...},"ml":{...}}`; `: ping` arrived after 15 s of quiet.
- `UPDATE scrape_run SET status=status WHERE id=34` (run 34, already CANCELLED): zero `db.changed`. `CANCELLED -> ERROR -> CANCELLED` on run 34: `data:{"table":"scrape_run","op":"UPDATE","id":34,"status":"ERROR"}` then `...,"status":"CANCELLED"}`. Run 34 is `CANCELLED` again.
- `pg_terminate_backend` on `application_name='scrappy-listen'`: `17:21:33 WARN [DB-LISTEN] connection lost, reconnecting` then `17:21:33 INFO [DB-LISTEN] listening on 'status_events'`; the open stream received `event:resync` / `data:{}`.
- After `kill`: 0 `scrappy-listen` backends (clean close). `usuario` 222 before and after; `scrape_run` 34 rows before and after, none RUNNING/INTERRUPTED; no scrape was started.

**Deviations from the plan**
- `StatusEvent.DbChanged` has an extra `job` field (`DbChanged(table, op, id, run, site, job, status)`): `cron_execution` payloads carry the job id and the UI needs it to refetch the right executions.
- `PythonRunner` gained a no-arg constructor (kept: six tests build it with `new`) plus an `@Autowired` `PythonRunner(StatusEvents)`. `ScraperService` kept its 8- and 9-arg constructors; the new `@Autowired` one has 10 args.
- Every `status`/`statusMsg` write in `ScraperService` goes through `transition(status, msg)` or `anunciar(msg)`; progress through `progreso(...)`, which is also called once after the global-deadline loop (a fourth publish point the plan did not list). `tomarElTurno` publishes nothing: each caller announces right after (RUNNING + its message).
- `CronJobRunner.awaitTerminal` returns the status carried by the event, not a re-read of `scrape.estado()`: a new run started between the event and the read would otherwise report `success` for the wrong run. A package-private constructor takes the re-check interval (60 s in production) so tests use 20-30 ms.
- `withRetry`: the interrupt during the backoff is not surfaced by Resilience4j, so the method checks `Thread.interrupted()` after a failed `executeCallable` and rethrows `InterruptedException`. Covered by `anInterruptDuringTheBackoffEndsTheRetriesAndIsNotSwallowed`.
- Extra optional env var `DB_CONNECT_RETRY_INTERVAL` (Flyway retry interval, default `10s`). `spring.mvc.async.request-timeout=660000` backstop added.
- Comments: the heartbeat is `: ping` (Spring renders `comment("ping")` as `:ping`; the code passes `" ping"`), sent after 15 s WITHOUT traffic (the pump waits on the queue, so any event resets the timer). Wire format is `event:name` / `data:json` (no space after the colon; valid SSE).
- SSE is a new `EventsController` (not a method of `ApiController`, which T7 will split); it gets the snapshot DTOs through two new public accessors on `ApiController` (`statusSnapshot()`, `mlEstadoSnapshot()`) backed by `ScrapeControlEndpoints.statusDto()` / `MlEndpoints.estadoDto()`, which the existing handlers now also use. `ScrapeDtos.SitioProgreso.desde(...)` is the single copy of the lower-case-state / 60-char-error rule.
- `docs/DATABASE.md` migration table was missing `V40`; added with `V41`. `CLAUDE.md` still says `V1..V40` (user-only file, not touched).
- Comment cleanup was NOT swept in the touched hunks of `ScraperService`, `PythonRunner`, `App` (left to T1); new files carry only why-comments.

**Not observed / caveats**
- No scrape ran during the boot check, so `scrape.progress` and `ml.status` events were verified by unit tests only (`InProcessStatusEventsTest`, `ScraperServiceStatusEventsTest`, `PythonRunnerStatusEventsTest`); `progreso(...)` and the backfill `MlStatus` are not exercised end to end (need a real scrape / a Python subprocess).
- Hikari waiting for a database that is down at boot, and Flyway `connect-retries`, are covered by the binding test only (`DatabaseBootRetryConfigTest`); not exercised against a stopped database.
- `ScrapeRunIndexBenchmarkTest` (wall-clock benchmark, threshold +10%) failed once in the commit-2 run (+12.5% with four forks competing); green alone and on the rerun. Pre-existing and unrelated to V41.
- `SseRealPortTest.slowClientGetsDropOldestAndAResync` takes ~10 s (it waits for the emitter timeout). A reader that stops reading ties up only its own write thread, but the timeout callback of that client waits on the emitter monitor until Tomcat cuts the write (`server.tomcat.connection-timeout`, 20 s). Documented in `docs/GOTCHAS.md`.

### T3b handoff

For the frontend writer. Contract source of truth: `docs/openapi.yaml` (`/api/events`) and `docs/API_REFERENCE.md` (`GET /events`).

- **Endpoint**: `GET /api/events`, `text/event-stream`. Any authenticated role. The token is a Bearer header held in memory, so `EventSource` cannot be used: read it with `authedFetch` and a `response.body.getReader()` stream parser (split on blank lines; a line starting with `:` is a comment/heartbeat; `event:` names the event, `data:` is one line of JSON; there is no space after the colon, parsers must accept both forms).
- **Order**: the first event is always `snapshot`. `data` = `{ "status": <data of GET /api/status>, "ml": <data of GET /api/ml/estado> }`; replace the local state with it.
- **Events** (names exact):
  - `scrape.status`: `{ status: "IDLE"|"RUNNING"|"DONE"|"ERROR", mensaje }`.
  - `scrape.progress`: `{ total, completados, productos, sitios: [{ nombre, estado: "esperando"|"en_curso"|"done"|"error", count, durMs, error? }] }` (same as `status.progreso`; at most 4 per second).
  - `ml.status`: `{ kind: "training"|"backfill", running, phase, pct, msg, startedAt }` (`startedAt` is `""` when unset; for `training` it is the `ml.training` object of `/api/ml/estado`).
  - `db.changed`: `{ table: "scrape_run"|"scrape_run_site"|"cron_execution", op: "INSERT"|"UPDATE", id?, run?, site?, job?, status }` (only present fields are sent). `cron_execution` is sent to ADMIN only. Use it as a cue to refetch (runs list, `/api/cron` executions), not as the data itself.
  - `resync`: `{}`. Events may have been lost (the app reconnected to the database, or this client fell behind and the oldest events were dropped): refetch `/api/status`, `/api/ml/estado` and whatever cron/run data the screen shows.
- **Lifetime and reconnect**: the server closes the stream after 10 minutes (access token lives 15) and a `: ping` comment arrives every 15 s of quiet. The reader must reconnect on any end or error, through `authedFetch` so an expired token is refreshed first; each connection starts with a fresh `snapshot`. Use a short backoff with jitter on repeated failures and stop on 401 after a failed refresh (session over). A 401 before the stream starts has the standard `{ error: { code: "no_autenticado" } }` envelope.
- **Replace**: the 1.8 s `/api/status` poll (`useScrapeStatusPolling`, `readStatus`), the 2 s and 4 s ML polls (`MlStatusPanel`, Topbar/splash), keeping a one-shot fetch on mount and on `resync` as fallback. The CLI keeps polling `/api/status` (contract unchanged).
- **Tests**: unit-test the parser with split chunks, comments, multi-event chunks and CRLF; e2e (`tests/e2e/run-e2e.sh`, never `vite dev`) must assert the banner/progress update without a poll and that the stream survives a forced token refresh. `scrape-poller.spec.js` stubs `/api/status`; it needs a stub for `/api/events` or the reader falls back to polling.

### T3b evidence

Frontend unit baseline 49 files / 427 tests (HEAD a5b9fda). `npm test` before each commit; `npm run build` (VITE_API_BASE_URL=http://localhost:3000) green.

| Commit | Hash | Files / tests |
|---|---|---|
| 1 `feat(frontend): read status events from a server stream` | 29ad528 | 51 / 479 (+32 `eventStream.test.js`, +20 `EventStreamProvider.test.jsx`) |
| 2 `refactor(frontend): replace status polling with the event stream` | 31ca1ab | 55 / 511 |
| fix `ignore a status read older than a pushed event` | 974322c | 55 / 512 |
| fix `open the status stream once per session` | d3407ef | 55 / 513 |
| 3 `test(e2e): drive the scrape UI from a stubbed event stream` | 1c43c0c | unit unchanged (513); e2e below |
| 4 `docs(frontend): document the status stream` | 92ebbc7 | docs only |

Final: 55 files / 513 tests (+86). `useScrapeStatusPolling.test.js` went 15 -> 24 tests and `SplashRoute.test.jsx` kept 3; every removed interval assertion has a stream counterpart with the same intent (see the test names). No assertion was skipped or deleted; the only edits to existing test files are `SplashPanel.test.jsx` (prop `onStartPolling` -> `onWatchRun`), `App.test.jsx` (an `/api/events` stub in `authedRouter` + 2 new tests) and `tabs.spec.js` (below).

**Negative controls** (break, run, restore): parser without the CR-then-LF skip -> CRLF and split-CRLF tests red; watchdog not re-armed per chunk -> heartbeat test red; 403 retried -> red; no backoff reset after a frame -> red; training watch armed after the POST instead of before -> "terminal event beats the launch response" red; Topbar ignoring the stream -> red; MlStatusPanel toasting without having been running -> red; provider keyed on the username -> "one connection" red; reconcile guard removed -> "never overwrites a newer push" red; e2e: the hook re-reading `/api/status` on every push -> `scrape-stream.spec.js` "the poll is back" red.

**E2E** (`tests/e2e/run-e2e.sh`, fresh `clean package` jar from HEAD 31ca1ab's backend, fresh `npm run build` bundle, JRE 21, profile dev, dev DB; cron jobs 3 and 4 disabled for the runs and back to `enabled=true` after, `last_run_at`/`next_run_at`/`cron_executions` unchanged; no scrape started, 0 RUNNING/INTERRUPTED runs): final full run = pytest 51 passed + Playwright 31 passed (26 before: `scrape-poller` 1 -> `scrape-stream` 2, +4 `event-stream.spec.js`). Four more consecutive full browser runs on a warm stack and five on a cold boot: 31/31 each.

**Deviations from the plan**
- Provider mounted in commit 2, not commit 1 (commit 1 leaves it unmounted so the old polls stay green; mounting needed the App tests adapted, which is commit 2's scope).
- Two extra `fix` commits, both found by the e2e run: (a) a reconcile read that answered after a newer push overwrote it (splash stuck on the old message); (b) the provider keyed on `identity.username`, so `/api/auth/me` arriving after the session closed the first stream and opened a second at every login.
- No `route.fulfill` for `/api/events`: a fulfilled body ends at once, so the client reconnects after its backoff and every assertion races it. `e2e/event-stream-stub.js` wraps `window.fetch` for that one URL and keeps the stream open; the app's parser/reconnect code is unchanged. The real endpoint is covered by the new `event-stream.spec.js` (200 + `text/event-stream` + `X-Accel-Buffering: no`, none for anonymous, 401 -> one refresh -> stream, logout closes it).
- The watchdog (45 s) is unit-tested only; an e2e for it would outlive the 45 s test timeout. The backend-cut variant asserts the UI leaves RUNNING within the backoff (<= 6 s) and recovers after restore.
- `tabs.spec.js` "two tabs cold-starting": `waitForLoadState('networkidle')` can never fire while a session holds a stream open; it now waits for each tab's `/api/events` response (opened only after the session settled). The claim (exactly one refresh) is unchanged.
- `useScrapeStatusPolling` keeps its name (the task names the file) although it no longer polls; its API changed: `pollingNeeded`/`startPolling`/`stopPolling`/`POLL_INTERVAL_MS` -> `runInFlightAtMount`/`watchRun(done, { reconcile })`; `SplashPanel` prop `onStartPolling` -> `onWatchRun`.
- `AppLayout`'s own `startPolling` was dead code (no route used it); it was deleted. The stream now mirrors the status into the reducer (`scrapeStatus`/`scrapeMsg`/`progreso`), so the existing "refetch tendencias on RUNNING -> finished" effect finally fires on a real scrape end. It does NOT reload the first page on progress (the dead poller did, every 1.8 s, while RUNNING).
- `/api/ml/resultado` is never fetched for a result: it is a pure function of the training status (`running, phase, pct, msg, done = !running && phase != idle`), derived from `ml.status`. Topbar keeps ONE read on mount as a fallback. After training ends the provider refetches `/api/ml/estado` once (model flags/metadata).
- Cron (optional item done): `CronjobsPage` re-lists and `CronJobCard` re-reads its executions on `db.changed` of `cron_execution` (and on `resync`).
- "Create no user rows" cannot hold for this suite: `global-setup.js` and the pytest `conftest.py` each create disposable accounts through `POST /api/usuarios` (deactivated, never deleted, by design). The 14 runs left 232 inactive `e2e-viewer-*` / `e2e-ui-viewer-*` rows (+ their 1094 refresh tokens, 15 reset tokens, 232 role rows); I deleted exactly those in one transaction (created after 20:30 UTC, inactive, e2e prefix). `usuario` = 222 before and after. `refresh_token` 10573 -> 10796 (logins by the existing `e2e-admin`, same as any run).

**Polls left in place** (none hits the API on a timer): `MlStatusPanel` `setInterval(2000)` only bumps a local `tick` to redraw the elapsed time; `SplashPanel` `setInterval(600)` animates the bar; `MlStatusPanel.handleApply` waits a fixed 3 s and then reads `/api/ml/estado` once (the "aplicar" scoring emits no event; not a loop). The CLI still polls `/api/status` by contract.

**Not observed / caveats**
- One failure in ~33 full-suite runs: `tabs.spec.js` "two tabs cold-starting" saw 2 refreshes instead of 1 on the first run after a fresh backend boot. The artifact was overwritten by the next run, and it did not reproduce in 12 isolated runs, 60 `--repeat-each`, 13 full runs or 5 cold boots. It is the race-sensitive cold-start spec and I could not attribute it (the stream opens only after the session settles, so it should not add refreshes); treat it as open.
- No real scrape ran, so a live `scrape.progress`/`ml.status` stream into the browser was verified only by unit tests and the stubbed-stream e2e, not by the real backend.

### T7 evidence

Baseline 3258 / 0 / 0 / 7 (HEAD 3c3ee4b). Route set captured BEFORE touching code (source scan of the mapping annotations, verified equal to `LiveRoutes.todas()` on the untouched tree): 83 (method, path) pairs in `src/test/resources/ar/scraper/security/rutas-vivas.txt`, asserted by the new `LiveRouteSetTest` (equality + no duplicates).

**Controllers** (all `@RestController @RequestMapping("/api")`, constructor injection, ports injected directly, no `new XxxEndpoints`): `ScrapeController` 6 (status, scrape, interrupted, resume, discard, cancel), `SitiosController` 4 (sitios x3, `PUT /config`), `CatalogoController` 5 (data GET/DELETE, facets, csv, producto), `ComparadorController` 2, `MarcasPicksController` 2, `RecomendadosController` 4, `FavoritosController` 3, `OutfitsController` 8, `SuplementosController` 2, `PcsController` 7, `MlController` 5, `TendenciasController` 2 (tendencias, historial), `AgentController` 3, `FinanciacionController` 7 (presets x5, recomendacion, indices), `DbAdminController` 4 = 64 handlers in 15 controllers (the "65" counted the mapping-less `data` overload). `ApiController` deleted.

| Commit | Hash | Suite |
|---|---|---|
| `refactor(web): give scrape, catalog, ml and feed handlers their own controllers` | b136a3c | 3259 / 0 / 0 / 7 (+1 route-set test) |
| `refactor(web): give outfit, supplement, pc, agent and financing handlers their own controllers` | 0e3cde0 | 3259 / 0 / 0 / 7 |
| `refactor(web): drop ApiController` | 6382c48 | 3259 / 0 / 0 / 7 |

`docs/openapi.yaml` untouched; `RouteCoverageTest`, `OpenApiRouteCoverageTest`, `SpringWiringTest`, `BackendLayeringArchTest`, `CacheUsageArchTest` green unchanged.

**Boot check** (`clean package -DskipTests`, JRE 21, profile dev, dev DB, throwaway secrets, `admin` existing; cron jobs 3 and 4 disabled for the run and back to `enabled=true`, `last_run_at`/`next_run_at` unchanged): `Started App in 3.7 s`, only WARN is `UserDetailsServiceAutoConfiguration`, no ERROR. As `e2e-admin`: 25 GET/DELETE routes spanning every new controller answered 200/204 (`/api/db/export` 410, as before), `/api/events` 200 `text/event-stream` with `X-Accel-Buffering: no`, anonymous `/api/status` 401.

**E2E** (`tests/e2e/run-e2e.sh`, fresh jar + bundle): pytest 51 passed + Playwright 31 passed. Cleanup: the run left 40 inactive `e2e-*` usuario rows (created after the run start), deleted in one transaction (FKs cascade); `usuario` 222 before and after, no scrape_run created (max id 34), `cron_executions` 32, `refresh_token` 10797 -> 10806 (logins, as in any run).

**Deviations**
- Handler names follow the old `*Endpoints` helpers, not `ApiController`: `scrapeInterrumpido/retomarScrape/descartarScrapeInterrumpido/cancelarScrape` -> `interrumpida/retomar/descartar/cancelar`, `pcsBuilder` -> `builder`, `get/putPcsPreferencia` -> `get/putPreferencia`. Callers in tests adapted.
- Split beyond the helpers: `ScrapeControlEndpoints` -> Scrape + Sitios, `OutfitsEndpoints` -> Outfits + Suplementos (shared `SuplementoPicks` mapper), `MlEndpoints` -> Ml + Tendencias.
- Test-only overloads that lived on `ApiController` (`data` 17 args, `pcsBuilder` 3 args, `suplementosBuilder` 2 args) are gone; their call sites pass the defaults explicitly (`null, null, null, null` / `""`). The `PcsController.builder` overloads of 4/10/14 args that `PcsEndpoints` already had were kept.
- `PcBuilder` became a bean (`PcsConfig`); `ProposePcTool` still builds its own (javadoc corrected).
- `EventsController` reads `ScrapeStatusView` / `MlEstadoView` (new small `@Component`s holding the former `statusDto`/`estadoDto`), not a controller; `EventsControllerTest` / `SseRealPortTest` mock those.
- `verifyNoInteractions(db)` assertions (`ScrapeControllerTest` x2, `AgentControllerTest` x5) now name the ports the route must not touch (`mlOutput, productos, aggregator` / `productos`), because the controllers no longer receive `db`; the `clearInvocations(db)` workaround and its comment are gone.
- Test classes renamed (`ApiController*Test` -> `Catalogo*/Outfits*/Pcs*/...Test`, 32 files, `git mv`); assertions unchanged. `DisplayName`s and stale comments updated. `DatabaseService` port accessors are now test-only handles (comments corrected, not removed: 100+ test uses).
- Docs updated with the code: `STRUCTURE.md`, `ARCHITECTURE.md` (+ a T7 paragraph), `GOTCHAS.md`, `LLM_EMBED.md`. Historical paragraphs in `ARCHITECTURE.md`/`DATABASE.md` and archived openspec/odd files still name the old classes on purpose.
- Mid-task tooling bug (mine): my signature rewriter matched a continuation line once and mangled `OutfitsController`/`PcsController`; compile caught it, regenerated from the originals and diffed every controller against its `*Endpoints` source.

### T1 evidence

Comment sweep over `scraper/src/main/java/ar/scraper` (313 of 343 files touched; the rest had no droppable comments). Tool: a string/text-block-aware Java tokenizer that keeps, per comment, only the 1-2 sentences carrying a why-marker (constraint, ordering, concurrency, units, security) and drops restatements, banners, history prose (phase/design/T-ids/change names), tag-only javadoc and commented-out code.

- Comment lines (`find ... -exec cat {} + | grep -cE '^\s*(//|/\*|\*)'`): 10263 -> 3268. Total lines: 40219 -> 33144.
- Per commit (files, +/-): d8d4e03 agent/api/catalog/classification/config 60, +205/-994; 884aa4c db/favoritos/feedback/financiacion/fuentes/health 41, +286/-1470; e960f75 aggregator/indices/json/model 37, +349/-1417; 6493158 ml/scheduling/scrape/scrapers/pages 46, +346/-1668; 0132f46 outfits/pcs 63, +498/-2182; e4003de security/web/App 66, +394/-1422.
- Comment-only check: for all 313 files, source with comments removed (Java-aware, strings and text blocks preserved) and whitespace stripped is byte-identical between 62855f5 and HEAD; also run per commit on the staged blobs (bad=0 each time). Nothing under `src/test` or `src/main/resources` changed.
- Suite before each commit: 3259 tests, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS (clean, dev DB up). `ScrapeRunIndexBenchmarkTest` did not flake.
- Files reverted: none. `NoIntegerBooleanLiteralsTest` scans `db/*.java` text; deleting comments cannot add matches and it stayed green.
- Residual: surviving comments keep their original language; some extracted sentences read a little terse without the dropped lead-in.

## Next step

Open PR(s). (T1, T3 and T7 done.)

### Upsert sentinel decision APPLIED (user, 2026-09-30) in 6f15a11

User chose "0 nuevos": when a transaction cannot even be opened (DB down), `upsertProductos` /
`upsertParcial` must return the sentinel (`UpsertStats(0,0,0,0)`) again instead of propagating
(T4 changed this; caller `ResultAggregator.java:143`). Fix before T3: catch at the port wrapper
outside the `@Transactional` boundary, plus a test with an unreachable DataSource.

Applied in 6f15a11: `ProductRepository` opens the transaction with a `TransactionTemplate` (extra ctor arg `PlatformTransactionManager`), catching open failures outside it. Test: `ProductRepositoryNoTransactionTest` (negative control red/green). Suite 3201 / 0 / 0 / 7.

Next: T3 (plan in Engram `odd/backend-hardening/t3-plan`), T7, T1. (T3a done below.)
