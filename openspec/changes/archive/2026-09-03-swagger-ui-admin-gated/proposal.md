# Proposal — `swagger-ui-admin-gated` (change 2 of 2)

Phase: `sdd-propose` · Date: 2026-09-03 · Input: `explore.md` (118 lines, orchestrator-verified)
· Depends on: `openapi-swagger-docs` (PR #185)

## Intent

Change 1 shipped a contract you can read. This one ships a contract you can **use**: an
ADMIN-only page in the dashboard that renders `docs/openapi.yaml` and lets you fire real,
authenticated calls against the running backend without leaving the app or reaching for
`curl` and a hand-copied bearer token.

**Be honest about which half is trustworthy.** `OpenApiRouteCoverageTest` guards path,
method and access level in both directions — those are proven. Response schemas in the
YAML are hand-written prose that no test can check, because every handler returns an
untyped `ObjectNode`. So "try it out" is the part that never lies: the response pane
shows what the endpoint *actually* returned, which makes the interactive surface the
correction mechanism for the documented one, not just a nicer reader.

## Scope

### In scope

| # | Deliverable |
|---|---|
| 1 | `GET /api/openapi.yaml` — a ~30-line `ar.scraper.web` controller streaming the classpath-bundled document. **No springdoc.** One `ApiRoutePolicy` row, ADMIN |
| 2 | Maven `copy-resources` execution putting `docs/openapi.yaml` on the classpath at build time |
| 3 | **`Dockerfile` `COPY docs/openapi.yaml`** — without it the Docker build fails, and only Docker |
| 4 | The endpoint's own entry in `docs/openapi.yaml` (`x-access: ADMIN`) |
| 5 | `/api-docs` SPA route rendering `swagger-ui-react`, lazy-loaded, ADMIN-gated by `RequireRole` |
| 6 | `requestInterceptor` attaching `getAccessToken()`, exactly as `authedFetch` does |
| 7 | **Per-operation try-it-out deny-list** plus the Swagger UI plugin that enforces it, plus a test proving every deny-list key resolves to a real operation in the YAML |
| 8 | Nav entry `requires: 'ADMIN'` — hidden, not disabled |

### Out of scope

- **Typed-DTO refactor of `ApiController`** (56 handlers). Change 1's addendum already
  removed it as a precondition: schemas are hand-written, not reflected. Still worth
  doing on its own merits; still not this change.
- **Reflection-generated schemas** — springdoc, `@Schema`/`@ApiResponse`, an `OpenAPI`
  bean. Rejected outright, see Approach.
- **Any edit to `docs/openapi.yaml` beyond deliverable 4.** Correcting a schema found to
  be wrong *by using this UI* is a follow-up change, not scope creep into this one.
- A confirmation dialog for allowed-but-heavy operations. The deny-list is binary.
- `docs/FRONTEND_AUTH_CONTRACT.md` — untouched.

## Capabilities

### New capabilities

- `interactive-api-console`: an ADMIN-only rendered console over the checked-in OpenAPI
  contract, authenticated by the existing session, with an explicit and guarded list of
  operations whose try-it-out is disabled.

### Modified capabilities

- `api-contract-documentation`: its Non-Goals list currently reads *"springdoc, Swagger
  UI, or any served interactive documentation endpoint"* and *"Contract Completeness ...
  every live route"*. The first must be retired; the second now covers one new route.
  Delta spec required.

## Approach

### Why no springdoc

Both topologies need a backend endpoint — ADMIN-gating cannot be enforced on a static
file in the frontend's `dist/`, which nginx and `vite preview` serve with no bearer
check. Once the shell lives in the SPA, springdoc's only remaining job is vendoring
`swagger-ui-dist` assets that `swagger-ui-react` already ships. The price would be a
production dependency, 3–4 policy rows, and a **permanent** `RouteCoverageTest` blind
spot: springdoc registers under `org.springdoc.*` and the scan is
`findCandidateComponents("ar.scraper")`. It would also reopen the API-only boundary
settled in `decouple-services-postgres` (D6). Our single controller sits inside the
existing scan and is covered for free.

### Classpath bundling, not a runtime path walk

`DocumentedRollback` walks up from `user.dir` to reach `docs/`. That works only because
Maven's **test** JVM sits one hop below the repo root. At runtime it breaks in Docker,
where no `docs/` exists at any depth. A `copy-resources` execution plus
`ClassPathResource` is the one mechanism identical across portable, POSIX and Docker.

The same one-hop trick remains legitimate **in tests** — the deny-list guard (deliverable
7) reads `../docs/openapi.yaml` from vitest's cwd. Test-time and runtime are different
questions; only the runtime read is rejected.

### The guard catches us on purpose

The new endpoint is a live `ar.scraper.web` route, so change 1's `OpenApiRouteCoverageTest`
direction 2 goes red the moment it exists unless the YAML documents it. That is the guard
doing its job on its author's own next change — the cheapest possible proof it works.

### Try-it-out: the deny-list

The YAML declares **no `operationId`** (verified). Keys are therefore `METHOD path` using
the templated path exactly as written in the YAML, e.g. `DELETE /api/usuarios/{username}`.
A vitest guard asserts every key resolves to a real operation, so a renamed path cannot
silently un-deny an operation.

Mechanism: a `wrapComponents.OperationContainer` Swagger UI plugin forcing
`allowTryItOut={false}` and rendering the reason. Entries are `key → reason`, not a bare
set — the UI states *why*, instead of showing a button that 403s.

**Rule.** Deny only where one Execute (a) destroys data no UI action can restore,
(b) starts a long background job that mutates the catalog or the models, (c) changes who
can log in, or (d) mutates the caller's own browser session. Everything else stays
executable — a console that cannot execute is a worse document viewer.

| # | Operation | Limb | Why |
|---|---|---|---|
| 1 | `DELETE /api/db/productos` | a | Empties the catalog, cascades `precio_historico`. The 409 guard only fires when favorites exist; on a catalog with none it wipes clean on one click, and there is no `?force=`-free undo |
| 2 | `DELETE /api/db/ml` | a | Clearing ML-derived data is only recoverable by a full pipeline re-run |
| 3 | `DELETE /api/data` | a | Soft-deletes a shared catalog product. Needs a `url` param so it is not one-click, but no UI action restores it |
| 4 | `POST /api/agent/apply` | a | The LLM agent's only real write path: UPDATE plus audit row |
| 5 | `POST /api/ml/renormalizar` | a | Bulk-rewrites `categoria`/`marca` across the persisted catalog |
| 6 | `POST /api/scrape` | b | Launches a long run over 29 sites. `cancel` exists, but a partial run has already upserted and soft-deleted |
| 7 | `POST /api/scrape/resume` | b | Same, from an interrupted run |
| 8 | `POST /api/cron/{id}/run-now` | b | Dispatches a real execution on a virtual thread |
| 9 | `POST /api/ml/entrenar` | b | Long GPU job; overwrites `_models/text_classifier.pkl` and contends for VRAM |
| 10 | `DELETE /api/usuarios/{username}` | c | Deactivates an account. `ultimo_admin` blocks the worst case; locking a colleague out from a docs page is still not a documentation action |
| 11 | `PUT /api/usuarios/{username}/rol` | c | Replaces, never accumulates — a mistyped body demotes an ADMIN |
| 12 | `POST /api/auth/login` | d | Issues a new token pair and sets the refresh cookie over the caller's own live session |
| 13 | `POST /api/auth/refresh` | d | Not executable anyway: needs the `X-Refresh-CSRF` nonce, which `authSession.js` keeps module-private and never exports. Denying it states the reason instead of returning a puzzling 403. A live rotation also risks tripping `RefreshTokenService` reuse detection against the user's own second tab |
| 14 | `DELETE /api/auth/refresh` | d | Same nonce requirement; logs the caller out of their own session |

**Deliberately NOT denied**, so the exclusions are reviewable too: `POST`/`DELETE`
`/api/cron` and `/api/cron/{id}` (cron jobs are re-creatable from the same console),
`POST`/`DELETE /api/sitios` (dynamic sites, likewise), `PUT /api/config` (in-memory only,
lost on restart), `POST /api/scrape/cancel` (idempotent, and stopping is the safe
direction), `POST /api/ml/aplicar` (applies to the in-memory catalog),
`POST /api/db/import` (410 Gone — it can do nothing), `POST /api/usuarios` and
`PUT /api/usuarios/{username}/activar` (additive), every favourite/outfit/feedback write
(per-owner, and each has a UI undo).

### How a VIEWER experiences this

Three independent layers, in the project's established order:

1. **Nav** — `nav-config.js` gains `requires: 'ADMIN'`; `visibleNav` drops the node, so
   a VIEWER has no such element in the DOM. Hidden, not disabled — same as `Cronjobs`
   (`nav-config.js:49`) and `Cuentas` (`:50`).
2. **Route** — `<RequireRole role="ADMIN">` around the page in `App.jsx`, exactly as
   `App.jsx:151`. A deep link renders `AccessDenied`: explicit, never a silent redirect.
3. **Backend** — `GET /api/openapi.yaml` is ADMIN in `ApiRoutePolicy.TABLE`. Defeating
   both frontend layers yields an empty shell, because the document never arrives.

Lazy-loading the page also means a VIEWER never downloads the `swagger-ui-react` bundle.

## Affected areas

| Area | Impact | Est. lines |
|---|---|---|
| `scraper/.../web/OpenApiDocumentController.java` | New | +30 |
| `scraper/.../web/OpenApiDocumentControllerTest.java` | New | +40 |
| `scraper/.../security/ApiRoutePolicy.java` | Modified | +2 |
| `scraper/pom.xml` | Modified | +12 |
| `Dockerfile` | Modified | +2 |
| `docs/openapi.yaml` | Modified | +10 |
| `frontend/src/pages/ApiDocsPage.jsx` | New | +70 |
| `frontend/src/lib/apiDocs/nonExecutableOperations.js` | New | +45 |
| `frontend/src/lib/apiDocs/denyTryItOutPlugin.js` | New | +35 |
| `frontend/src/pages/ApiDocsPage.test.jsx` | New | +60 |
| `frontend/src/lib/apiDocs/nonExecutableOperations.test.js` | New | +35 |
| `frontend/src/App.jsx` · `nav-config.js` | Modified | +5 |
| `App.test.jsx` · `nav-config.test.js` (VIEWER sees nothing) | Modified | +12 |
| `frontend/package.json` | Modified | +1 |
| `CLAUDE.md` · `docs/ARCHITECTURE.md` · `docs/API_REFERENCE.md` | Modified | +29 |
| **Total (authored)** | | **~388** |

`frontend/package-lock.json` is generated and excluded from the authored count (Section E),
though it ships in the diff.

**Fits the 800-line budget with room to spare — no `size:exception`.** My estimate is
above exploration's 220–300 because it counts tests and doc updates, which `COMMIT-5`
requires to travel with their code. Two work-unit commits (`COMMIT-4`):
`feat(api): serve the OpenAPI contract to authenticated admins`, then
`feat(ui): add the ADMIN-only interactive API console`.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Missing `Dockerfile COPY` breaks only the Docker build, silently | High if forgotten | In scope as deliverable 3; `docker-smoke.yml` catches it in CI |
| Swagger UI's `requestInterceptor` may not cover the spec fetch itself, leaving the document request unauthenticated | Medium | Q1. Fallback costs one `yaml` dependency and ~25 lines |
| An operation belongs on the deny-list and nobody noticed | Medium | The exclusions are listed above by name, so review is over content, not a silent default |
| Deny-list rots when a path is renamed | Low | Guarded by `nonExecutableOperations.test.js` |
| `swagger-ui-react` bloats the bundle | Medium | Lazy route; a VIEWER never fetches it. Measure, do not estimate (`CODE-3`) |
| `RouteCoverageTest` carries a row-count assertion the new row breaks | Low | Cheap to check in `sdd-design` |

## Precondition (not a risk)

**`apply` MUST NOT start until PR #185 is merged to `master`.** `docs/openapi.yaml`,
`LiveRoutes` and `OpenApiRouteCoverageTest` exist only on
`feat/openapi-contract-and-drift-guard`. The user chose to wait rather than stack a
chained PR, so `spec`, `design` and `tasks` may run now; `apply` branches clean from
`master` afterwards. Branching from today's `master` would serve a file that does not
exist and either duplicate #185's ~1030-line commit inside this diff or conflict outright.

## Rollback plan

- `git revert` the UI commit: the SPA loses one lazy route and one nav entry. The backend
  endpoint keeps working and stays documented — no red guard.
- `git revert` the backend commit: removes one controller, one test, one policy row, one
  pom execution, one `Dockerfile` line and the YAML's self-entry. Direction 2 of
  `OpenApiRouteCoverageTest` stays green **because the route disappears with its entry**.
- **Revert the UI commit before the backend commit** — the reverse order leaves the page
  pointing at a route that 403s.
- No Flyway migration, so nothing is byte-frozen and no rollback SQL is owed.
- No new environment variable; `RequiredEnvVarsGuard` untouched.

**Affected subsystems:** backend web layer · security policy table · Maven build ·
Docker build · frontend routing, nav and auth session · `docs/openapi.yaml` · root docs.

## Success criteria

- [ ] An ADMIN opens `/api-docs`, sees every documented operation, and executes
      `GET /api/data` successfully without pasting a token anywhere.
- [ ] A VIEWER has no `/api-docs` element in the DOM, and a deep link renders
      `AccessDenied`.
- [ ] `GET /api/openapi.yaml` returns 200 for an ADMIN, 403 for a VIEWER, 401 unauthenticated.
- [ ] All 14 deny-listed operations render with no Execute button and a stated reason.
- [ ] `OpenApiRouteCoverageTest` green in both directions with the new route present.
- [ ] `docker compose up --build` succeeds and serves the document (CI `docker-smoke`).
- [ ] Whole suite green on **each** commit (`TEST-1`), built with `clean`.
- [ ] `DOC-1`/`DOC-2` honoured — the springdoc rejection is rationale and belongs in
      `docs/ARCHITECTURE.md`, not `CLAUDE.md`.
- [ ] `COMMIT-1`/`COMMIT-2` subjects name the behaviour; no AI attribution (`COMMIT-3`).

## Open questions

| # | Question | Why I could not settle it |
|---|---|---|
| Q1 | Does Swagger UI's `requestInterceptor` apply to the **spec fetch** when the `url` prop is used, or only to try-it-out calls? | External library behaviour, absent from the tree, and Context7 was unavailable in this phase. If yes: `url` + interceptor, no parser. If no: fetch with `authedFetch` and pass a parsed `spec` object, adding a `yaml` dependency and ~25 lines. `sdd-design` must confirm against current docs, not recall |
| Q2 | Bundle target: is a lazy chunk enough, or should `swagger-ui-react` be a `manualChunks` entry in `vite.config`? | Needs a measured build (`CODE-3`), which belongs to `apply` |
| Q3 | Does `RouteCoverageTest` assert a total row count that the new policy row would break? | Reading it is cheap; it is `sdd-design`'s to check, not a proposal-level unknown |

## Proposal question round

Interactive mode calls for a question round, and this sub-agent has no tool to ask
directly. These are the product questions whose answers would change the proposal.

1. **Is the deny-list the right shape, or would you rather have a confirmation step?**
   This proposal makes it binary: an operation is executable or it is not. The
   alternative — everything executable behind a "type the path to confirm" dialog —
   costs ~40 more lines and trades a hard stop for a speed bump. Which failure would
   annoy you more: not being able to fire `POST /api/scrape` from the console, or
   firing it by accident?
2. **Items 6–9 (limb b) are the debatable ones.** They destroy nothing directly; they
   start work. Denying them means the console cannot trigger a scrape or a training run,
   which is arguably one of the more useful things to do from it. Keep them denied?
3. **Should the console be able to reach a backend other than its own origin?** Today the
   `servers` block declares only `http://localhost:3000`. If you ever want to point the
   console at a LAN instance, that is a different (small) change and it should be said now.
4. **Who else will see this page?** The proposal assumes a single-operator install where
   every ADMIN is you. If a second ADMIN is expected, the deny-list stops being a
   convenience and becomes a real permission boundary, which argues for widening it.

**Assumptions if this round is skipped:** binary deny-list, no confirmation dialog;
items 6–9 stay denied; single origin; the audience is you plus a future session.

---

## Addendum — user decisions from the question round (2026-09-03)

Recorded by the orchestrator.

**Q1 — the deny-list stays binary.** No confirmation dialog. An operation is
executable or it is not. Reasoning on record: a hard stop cannot be clicked
through in a hurry, and a dialog that appears often gets dismissed without
reading, so the speed bump would buy less safety than it looks like it does.

**Q2 — limb (b) is RELEASED. The deny-list drops from 14 operations to 10.**
`POST /api/scrape`, `POST /api/scrape/resume`, `POST /api/cron/{id}/run-now` and
`POST /api/ml/entrenar` become executable. They start work rather than destroying
it, they are cancellable — `POST /api/scrape/cancel` was never on the list — and
triggering a scrape or a training run is among the more useful things a console
can do. Denying the start of something whose cancel is allowed was asymmetric.

The remaining **10** are the irreversible ones: the five that destroy data, the
two that change who can log in, and the three that mutate the caller's own
session.

**Q3 and Q4 were not answered; their stated assumptions stand.** The `servers`
block declares a single origin (`http://localhost:3000`); pointing the console at
a LAN instance is a separate, small change. The audience is the operator plus a
future session, so the deny-list is a convenience rather than a permission
boundary between two administrators. If a second ADMIN ever appears, revisit its
width — that is the trigger to record.

**Resolved by the orchestrator, not an open risk**: `RouteCoverageTest` carries
**no** total row-count assertion. Line 72 is `hasSizeGreaterThan(40)`, a lower
bound that a new route only makes more true, and line 89's `hasSize(6)` covers
the PERMIT band alone. The new row is ADMIN, so neither is affected.
`OpenApiRouteCoverageTest:131` is likewise a lower bound.

**Also settled**: PR #185 merged to `master` as `cb86a4f` on 2026-09-03 (merge
commit per `PR-1`, branch deleted). `docs/openapi.yaml`, `LiveRoutes` and
`OpenApiRouteCoverageTest` are on `master`, the suite there is green at
2013/0/0/7, and this change branches clean from `master`. **The apply
precondition is satisfied.**
