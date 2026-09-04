# Tasks: Swagger UI Admin-Gated Console

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~420 authored (design table), against a raised 800-line budget for this change |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR, two work-unit commits (A then B) |
| Delivery strategy | single-pr |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

Rationale: design's own line count (~420) sits well under the 800-line budget set
for this change; no `size:exception` is required. Re-flag if apply's actual diff
exceeds 800.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Commit A — serve the contract, ADMIN-gated, identical across install paths | Single PR, commit 1 | `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java` | `docker compose up --build` if Docker is available locally; otherwise `docker-smoke.yml` in CI is the only real proof (say so explicitly, do not skip silently) | Revert removes controller, its test, the `ApiRoutePolicy` row, pom execution, `Dockerfile` line, YAML self-entry — direction 2 of `OpenApiRouteCoverageTest` stays green because the route disappears with its entry |
| 2 | Commit B — ADMIN-only console UI, cosmetic layers only | Single PR, commit 2 | `cd frontend && npm test` | Manual: open `/api-docs` as ADMIN, confirm all 10 deny-listed ops show reason + no Execute, confirm `GET /api/data` executes for real (no vitest can prove swagger-ui's runtime `props.path` shape) | Revert removes route, nav entry, 4 lib modules, 2 deps — backend endpoint keeps working and stays documented, no red guard. Revert B **before** A |

## Phase 1: Backend contract endpoint (Commit A — one work unit, cannot split further)

- [x] 1.1 RED: `OpenApiDocumentControllerTest` — 200+body for ADMIN, 403 VIEWER, 401 anonymous, `Content-Type: application/yaml`. `@WebMvcTest` importing `SecurityConfig`, `JwtAuthFilter`, **and** `TokenService` (CLAUDE.md gotcha: plain `@Component`s aren't auto-registered).
- [x] 1.2 GREEN: create `OpenApiDocumentController.java` — `GET /api/openapi.yaml` streams `ClassPathResource("contract/openapi.yaml")`; missing resource → `log.error` + `IllegalStateException` naming both causes (copy-resources didn't run, or Docker context lacks `docs/openapi.yaml`) → 500, never empty 200.
- [x] 1.3 Add `ApiRoutePolicy.TABLE` row: `GET /api/openapi.yaml → ADMIN`.
- [x] 1.4 Add `docs/openapi.yaml` self-entry (`x-access: ADMIN`) — required by `OpenApiRouteCoverageTest` direction 2 the instant the route exists.
- [x] 1.5 Add `pom.xml` `copy-resources` execution: phase `generate-resources`, from `${project.basedir}/../docs`, into `${project.build.outputDirectory}/contract`, `overwrite=true`.
- [x] 1.6 RED+GREEN: additive byte-identity test inside `OpenApiRouteCoverageTest` (`CODE-2`, no extraction) — classpath `contract/openapi.yaml` byte-identical to `docs/openapi.yaml`, located via the file's existing `ubicarContrato` upward walk. This is the only proof `copy-resources` didn't silently no-op over a missing/stale directory.
- [x] 1.7 Add `Dockerfile` `COPY docs/openapi.yaml docs/openapi.yaml`, before `RUN mvn` (`Dockerfile:12`). Verify: run `docker compose up --build` if Docker is present; if not, state that explicitly and rely on `docker-smoke.yml` in CI — do not claim local proof that wasn't taken. **Done for real**: built the image, ran `docker compose up` with postgres+backend, and confirmed 200 (byte-identical body) for ADMIN, 401 anonymous, 403 VIEWER against the live container.
- [x] 1.8 Verify whole backend suite green with `clean` (`TEST-1`): both `OpenApiRouteCoverageTest` directions and `RouteCoverageTest.everyLiveMappingIsCovered`. Final: 2017/0/0/7 (2013+4).
- [x] 1.9 Update `docs/API_REFERENCE.md` (new route) and `docs/ARCHITECTURE.md` (springdoc-rejection rationale, `DOC-1`).

## Phase 2: ADMIN-only console UI (Commit B)

- [x] 2.1 `frontend/src/api.js`: `export` the existing `const BASE` (ADR-3) — one line, no duplication.
- [x] 2.2 RED: `loadContract.test.js` — mocked `authedFetch` → `yaml.parse` → `spec.servers` overwritten to `API_BASE || window.location.origin`; 401/403/404/500 each map to a distinct message.
- [x] 2.3 GREEN: create `lib/apiDocs/loadContract.js` implementing the above; `docs/openapi.yaml` itself stays unedited (runtime-only override).
- [x] 2.4 RED: `nonExecutableOperations.test.js`, four separate `it()` blocks anchored via the module's own path (never cwd): (a) exactly 10 deny-list keys, every reason non-empty, every verb a real HTTP method; (b) contract has >40 flattened `METHOD path` keys; (c) every deny-listed key resolves to a real operation, collect **all** offenders via `toEqual([])`; (d) a bogus `GET /api/does-not-exist` key **is** reported by the same resolver (positive control).
- [x] 2.5 GREEN: create `lib/apiDocs/nonExecutableOperations.js` — the 10 `{key, reason}` entries (Addendum-settled list) and exported `operationKey(method, path)`.
- [x] 2.6 RED: `denyTryItOutPlugin.test.js` — pure-function test, no swagger-ui mount: `execute` wrap returns without dispatching for a denied key, dispatches normally for an allowed one.
- [x] 2.7 GREEN: create `lib/apiDocs/denyTryItOutPlugin.js` — `wrapComponents.OperationContainer` (forces `allowTryItOut={false}` + renders reason) and `statePlugins.spec.wrapActions.execute` (enforces), both keyed by `operationKey`. **Design deviation found in manual verification (task 3.1)**: an arrow-function wrapper for `OperationContainer` silently broke expand/collapse for EVERY operation in the console (swagger-ui's own `withConnect` reads a custom `mapStateToProps` off `Component.prototype`, which an arrow has none of). Fixed by using a named `function` whose `.prototype` carries only `{ mapStateToProps }` copied from the original — not the full prototype chain, which would make React treat it as a class with no `.render()`. See inline comment in the file.
- [x] 2.8 RED: `ApiDocsPanel.test.jsx` — renders `SwaggerUI` with `spec` from `loadContract`, `requestInterceptor` attaches `getAccessToken()`.
- [x] 2.9 GREEN: create `components/ApiDocsPanel.jsx`, lazy-loaded beside the other panels in `AppLayout.jsx`, exported via `ApiDocsRoute`/`ApiDocsPanelRoute` from `AppLayout.jsx` (same pattern as `CronjobsRoute`/`UsuariosAdminRoute` — not self-exported from the panel file).
- [x] 2.10 Add `swagger-ui-react` and `yaml` to `frontend/package.json`; install (lockfile generated, excluded from authored line count).
- [x] 2.11 `nav-config.js`: add `{ kind:'link', label:'Consola API', to:'/api-docs', icon: Code, requires:'ADMIN' }` (installed `lucide-react` has no `Code2` icon in this version — used `Code` instead); update `nav-config.test.js` (VIEWER: no `/api-docs` node in the DOM).
- [x] 2.12 `App.jsx`: add `<Route path="api-docs" element={<RequireRole role="ADMIN"><ApiDocsPanelRoute/></RequireRole>}/>`; update `App.test.jsx` (VIEWER deep link → `AccessDenied`, document never fetched).
- [x] 2.13 Update `CLAUDE.md` with the new console entry.
- [x] 2.14 Verify whole frontend suite green: `cd frontend && npm test`. Final: 268/268 (244+24), 39 files (35+4).

## Phase 3: Recorded manual/measured verification (Commit B evidence, no vitest coverage possible)

- [x] 3.1 Manual console run, performed for real (not skipped): built the backend jar and frontend bundle, ran both against the dev Postgres, logged in as ADMIN via a real browser (Playwright/chromium), navigated to `/api-docs`, confirmed all 10 deny-listed operations render `.api-docs-deny-reason` with their stated text and zero `.try-out__btn`/`button.execute` nodes, and confirmed `GET /api/data` expands, executes, and returns a real 200 with live catalog data. This run is what found and fixed the 2.7 deviation above — no vitest run had (or could have) caught it.
- [x] 3.2 After `npm run build` (`VITE_API_BASE_URL` set, real build, not estimated): `ApiDocsPanel-*.js` chunk is **1,376.28 kB (397.11 kB gzip)**, plus `ApiDocsPanel-*.css` at **184.04 kB (27.34 kB gzip)** — its own lazy chunk, confirmed separate from `index-*.js` (606.95 kB). No `manualChunks` needed (Q2 resolved).
