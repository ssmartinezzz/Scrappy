# Design — `swagger-ui-admin-gated`

Phase: `sdd-design` · Date: 2026-09-03 · Inputs: `proposal.md` (+ Addendum), `explore.md`,
change 1's `design.md`, `OpenApiRouteCoverageTest`, `LiveRoutes`, `ApiRoutePolicy`,
`authSession.js`, `authedFetch.js`, `api.js`, `RequireRole.jsx`, `App.jsx`, `nav-config.js`,
`vite.config.js`, `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `docs/openapi.yaml`.

## Technical approach

One backend route streams the classpath-bundled contract; the SPA fetches it **through
`authedFetch`**, parses it, rewrites its `servers` block to the base the rest of the app
already talks to, and hands the resulting object to `swagger-ui-react` as a `spec` prop.
A deny-list blocks 10 operations at two seams — one that explains, one that enforces.

The design's two non-obvious moves: the spec fetch goes through the app's single auth
chokepoint rather than around it, and the console's request base comes from
`window.__API_BASE__`, never from the YAML's hardcoded `servers` entry.

## Architecture decisions

### ADR-1 — Fetch the spec ourselves; do not rely on `requestInterceptor` (Q1)

**Context.** Q1 asks whether Swagger UI's `requestInterceptor` covers the spec fetch when
the `url` prop is used. **I could not resolve it against current docs**: Context7 was not
invokable in this session (fourth consecutive phase), and this agent has no
WebSearch/WebFetch/Bash and no `node_modules` copy of `swagger-ui` to read. Recall is not
evidence, so it is not used as one.

**Decision.** `authedFetch(`${API_BASE}/api/openapi.yaml`)` → `yaml.parse` → `spec` prop.
Keep `requestInterceptor` for try-it-out calls, where it is uncontested.

**Consequences.** Correct under *both* answers to Q1 — the reverse is not true, which is
the whole reason to pick it. Three independent gains beyond safety:

1. **401 handling.** `requestInterceptor` only attaches whatever token is in memory. It
   does not refresh. `authedFetch` refreshes once and retries (`authedFetch.js:24-32`), so
   an ADMIN returning to a tab past token expiry gets the document instead of Swagger UI's
   generic "Failed to load API definition". CLAUDE.md states `authedFetch` is *"el único
   punto por el que pasan todas las llamadas"*; a `url` prop would create the second path.
2. **Base resolution.** We reuse `api.js`'s exact base expression (ADR-3), instead of
   hand-building a URL for the one call that skips it.
3. **Legible failures.** We map 401/403/404/500 to a sentence each.

**Cost.** One dependency (`yaml`) and ~25 lines, exactly as the proposal forecast.
**Rejected — server-side YAML→JSON** (SnakeYAML is already on the runtime classpath): it
would drop the npm dependency but stop the served bytes being identical to the checked-in
file, and move a parse failure from build time to request time. **Rejected — `url` prop:**
unverifiable here, and loses gains 1–3 even if Q1's answer is favourable.

### ADR-2 — Classpath bundling and a build step that cannot fail silently

| Element | Choice |
|---|---|
| Maven | `maven-resources-plugin` `copy-resources`, phase `generate-resources`, from `${project.basedir}/../docs`, into `${project.build.outputDirectory}/contract`, `overwrite=true`. No version (managed by `spring-boot-starter-parent`) |
| Classpath name | `contract/openapi.yaml` — **never** `static/`, `public/`, `resources/` or `META-INF/resources/`, which Boot would serve directly, straight into `denyAll()` |
| Docker | `COPY docs/openapi.yaml docs/openapi.yaml` **before** `RUN mvn` (`Dockerfile:12`). Context is `.` (`docker-compose.yml:39`) and `.dockerignore` excludes only `*.md`, so the file is reachable |
| Controller | `@GetMapping("/api/openapi.yaml")` → `ResponseEntity<Resource>` over `new ClassPathResource(...)`, `Content-Type: application/yaml` (RFC 9512; UTF-8 explicit) |
| Missing resource | `log.error` + throw `IllegalStateException` naming **both** causes ("the copy-resources execution did not run, or the Docker build context lacks docs/openapi.yaml") → 500. Never an empty 200 |

**The silent-failure trap this closes.** `copy-resources` over a missing directory logs a
warning and **succeeds**. The jar would ship without the document and nothing would fail
until a human opened the page. Two tests close it, in the file that owns each fact:

- `OpenApiDocumentControllerTest` — the classpath resource exists, is non-empty, and parses
  with >40 `paths` keys.
- `OpenApiRouteCoverageTest` gains one **additive** test: the classpath copy is
  **byte-identical** to `docs/openapi.yaml`, located by the upward walk that file already
  owns (`ubicarContrato`, line 75). Catches "not copied" *and* "copied a stale one", with
  zero duplication and no extraction (`CODE-2`).

The served path is a compile-time constant with no path variable, so no request can steer
it at another classpath entry.

### ADR-3 — The console follows `window.__API_BASE__`, not the YAML's `servers` (Q3)

**Context.** `docs/openapi.yaml:20-22` declares one server, `http://localhost:3000`.
Swagger UI resolves try-it-out URLs against it. The CLI rewrites the frontend's API base
per run (`cli/core/runtime_config.py` → `frontend/dist/config.js`; `api.js:13-16` reads
`window.__API_BASE__` first). Under `start lan`, a phone opening the console would fire
every Execute at *its own* `localhost:3000`. That is the recorded `VITE_API_BASE_URL`
bug class, reproduced in a new place.

**Decision.** After parsing, `loadContract.js` overwrites
`spec.servers = [{ url: API_BASE || window.location.origin }]`, where `API_BASE` is
**exported from `api.js`** (a one-line `export` on the existing `const BASE`) rather than
copied a third time.

**Consequences.** The console always talks to the backend the app is already talking to,
in every install path, with no rebuild. `docs/openapi.yaml` is **not edited** — the
override is runtime-only, so scope item "no edits beyond deliverable 4" holds and the
drift guard (which reads `paths`, never `servers`) is untouched. This does **not** reopen
Q3: Q3 asked for a *second, foreign* origin; this is the same origin, resolved correctly.
`authSession.js:15-21` deliberately duplicates a simpler 2-term expression; this one is the
3-term runtime-first form whose miscopy is a recorded production bug, so it is exported,
not duplicated.

## Deny-list mechanism (decision 3)

**Two seams, different jobs. Neither is a security boundary** — an ADMIN holds a token and
`curl`. The Addendum settles this: a convenience, not a permission boundary.

| Seam | Job | Why |
|---|---|---|
| `wrapComponents.OperationContainer` | **Explains.** Renders `<Original {...props} allowTryItOut={false}/>` plus a visible reason line | The operation stays fully visible and documented; only the *entry* to try-it-out disappears, so no Execute button can appear downstream. A reader sees why, not a missing affordance |
| `statePlugins.spec.wrapActions.execute` | **Enforces.** Returns without dispatching when the key is denied | `wrapComponents` binds to swagger-ui's internal component names and prop shapes, which are not a stable public API; `execute` is the actual chokepoint and is unit-testable as a pure function without mounting swagger-ui |

**Rejected:** `supportedSubmitMethods` (global per HTTP verb — denies every `DELETE` or
none); wrapping `TryItOutButton` alone (hides a button, leaves the form).

Keys are `METHOD path`, uppercase verb, single space, templated path verbatim from the
YAML. One exported `operationKey(method, path)` is used by both seams and the test, so a
formatting drift cannot make the test green while the plugin never matches. UI strings are
**Spanish**, matching every other panel; this document, the code and its comments are
English.

## The anti-rot test and its negative control (decision 4)

`frontend/src/lib/apiDocs/nonExecutableOperations.test.js`. Reads the real contract with
`readFileSync(fileURLToPath(new URL('../../../../docs/openapi.yaml', import.meta.url)))` —
anchored to the module, **not** vitest's cwd, which differs between `npm test`, CI and
`run-e2e.sh`. Same `yaml` parser as the page.

Recorded failure this is designed against: *a control can lie by giving too FEW reds when
two mechanisms break at once*. Here the parity assertion is `denyKeys ⊆ contractKeys`. An
empty contract goes loudly red; an **empty deny-list passes vacuously**, and both empty at
once is `∅ ⊆ ∅` — green, with two breaks showing as zero reds. So, four separate `it()`
blocks (separate, so none can short-circuit another), mirroring
`OpenApiRouteCoverageTest`'s two non-vacuity tests:

| # | Test | Asserts | Catches |
|---|---|---|---|
| 1 | `theDenyListIsNotVacuous` | **exactly 10** keys; every reason a non-empty string; every verb a real HTTP method | an emptied or typo'd list; a blank explanation |
| 2 | `theContractIsNotVacuous` | flattened `METHOD path` keys > 40 | a failed/empty parse making direction-1 free |
| 3 | `everyDenyListedKeyResolvesToARealOperation` | collects **all** offenders, `toEqual([])` | a renamed path silently un-denying an operation |
| 4 | `theResolverReportsAMissingKey` | a bogus `GET /api/does-not-exist` **is** reported by the same resolver #3 uses | the mechanism being unable to emit a red at all |

Exact count, not a lower bound: the Addendum settled 10, so changing it must require
editing this test.

**Stated limit.** No vitest can prove swagger-ui's runtime `props.path` has the shape the
key builder assumes. `apply` therefore records one manual run: open the console, confirm
all 10 render with a stated reason and no try-it-out affordance, and confirm one allowed
operation still executes.

## The three role-aware layers — only one enforces (decision 5)

| # | Layer | Where | Enforces? |
|---|---|---|---|
| 1 | Nav | `nav-config.js`: `{ kind:'link', label:'Consola API', to:'/api-docs', icon: Code2, requires:'ADMIN' }`, third direct admin link (still **not** grouped — same reasoning as the existing `nav-config.js:39-44` comment) | **No — cosmetic** |
| 2 | Route | `App.jsx`: `<Route path="api-docs" element={<RequireRole role="ADMIN"><ApiDocsPanelRoute/></RequireRole>}/>`, exactly as `:151` | **No — cosmetic** |
| 3 | Backend | one `ApiRoutePolicy` Band-C row: `GET /api/openapi.yaml → ADMIN` | **Yes — the only one** |

Made unmissable in code, in the phrasing already used at `App.jsx:148-150` and
`nav-config.js:45-48`: a header comment on `ApiDocsPanel.jsx` stating that layers 1 and 2
hide a page, layer 3 is what makes a VIEWER's deep link yield an empty shell, and the
deny-list is a **fourth** cosmetic layer that stops a slip, never an actor.

**Pattern correction to the proposal:** the page is
`frontend/src/components/ApiDocsPanel.jsx`, lazy-loaded in `AppLayout.jsx` beside the other
16 (`:25-39`) and exported as `ApiDocsPanelRoute` (`:404-408`) — not `pages/`, which holds
only routes rendered *outside* `AppLayout`. `AppLayout` already wraps `<Outlet>` in
`<Suspense>` (`:710`), so **Q2 needs no `manualChunks`**: `lazy()` already isolates
`swagger-ui-react` and a VIEWER never fetches it. Measure the chunk in apply (`CODE-3`).

## Data flow

```
docs/openapi.yaml ──copy-resources──→ target/classes/contract/openapi.yaml ──→ jar
                                                    │
        (byte-identity test)                        ▼
                                    GET /api/openapi.yaml  [ApiRoutePolicy: ADMIN]
                                                    │
      RequireRole(ADMIN) ──→ ApiDocsPanel ──authedFetch──┘   (401 → refresh → retry)
                                    │
                     yaml.parse → servers := API_BASE → <SwaggerUI spec=… plugins=[deny]
                                                          requestInterceptor=getAccessToken>
```

## File changes

| File | Action | ~Lines |
|---|---|---|
| `scraper/.../web/OpenApiDocumentController.java` | Create | +30 |
| `scraper/.../web/OpenApiDocumentControllerTest.java` | Create | +40 |
| `scraper/.../security/OpenApiRouteCoverageTest.java` | Modify (additive test only) | +15 |
| `scraper/.../security/ApiRoutePolicy.java` | Modify | +2 |
| `scraper/pom.xml` · `Dockerfile` | Modify | +12 / +2 |
| `docs/openapi.yaml` | Modify (self-entry, `x-access: ADMIN`) | +10 |
| `frontend/src/components/ApiDocsPanel.jsx` | Create | +45 |
| `frontend/src/lib/apiDocs/loadContract.js` | Create | +35 |
| `frontend/src/lib/apiDocs/nonExecutableOperations.js` | Create | +45 |
| `frontend/src/lib/apiDocs/denyTryItOutPlugin.js` | Create | +40 |
| `frontend/src/**` tests (4 files) | Create | +95 |
| `frontend/src/api.js` (export `BASE`) · `App.jsx` · `nav-config.js` | Modify | +6 |
| `App.test.jsx` · `nav-config.test.js` (VIEWER sees nothing) | Modify | +12 |
| `frontend/package.json` (`swagger-ui-react`, `yaml`) | Modify | +2 |
| `CLAUDE.md` · `docs/ARCHITECTURE.md` · `docs/API_REFERENCE.md` | Modify | +29 |
| **Total (authored)** | | **~420** |

`package-lock.json` is generated and excluded (Section E). Budget 800 — no `size:exception`.

## Commit boundaries (decision 6)

| # | Commit | Contents | Green alone |
|---|---|---|---|
| A | `feat(api): serve the OpenAPI contract to authenticated admins` | controller + test, policy row, **YAML self-entry**, pom execution, `Dockerfile` COPY, byte-identity test, `API_REFERENCE.md` + `ARCHITECTURE.md` | Yes |
| B | `feat(ui): add the ADMIN-only interactive API console` | deps, 4 frontend modules, 4 test files, `api.js` export, `App.jsx`, `nav-config.js` + their tests, `CLAUDE.md` | Yes |

**A cannot be split further, and two guards force it.** The instant the controller exists,
`OpenApiRouteCoverageTest` direction 2 goes red without the YAML entry, and
`RouteCoverageTest.everyLiveMappingIsCovered` goes red without the policy row. Controller,
row and YAML entry are one work unit by construction.

Order is forced: B before A leaves the page fetching a route that 403s. Revert **B before
A** (`proposal.md` rollback). `COMMIT-1`/`COMMIT-2` subjects name the behaviour, no AI
attribution (`COMMIT-3`), tests and docs travel with their code (`COMMIT-5`), whole suite
green with `clean` on **each** commit (`TEST-1`). `DOC-1`: the springdoc rejection is
rationale → `ARCHITECTURE.md` (commit A), never `CLAUDE.md`.

## Testing strategy

| Layer | What | How |
|---|---|---|
| Unit (Java) | 200 for ADMIN, 403 VIEWER, 401 anonymous; `application/yaml`; body non-empty | `@WebMvcTest` importing `SecurityConfig`, `JwtAuthFilter` **and** `TokenService` (CLAUDE.md gotcha) |
| Unit (Java) | Classpath copy present, parses, byte-identical to `docs/openapi.yaml` | `OpenApiDocumentControllerTest` + one additive `OpenApiRouteCoverageTest` test |
| Regression (Java) | Both guard directions with the new route live | `OpenApiRouteCoverageTest`, `RouteCoverageTest` unchanged in behaviour |
| Unit (JS) | Deny-list ↔ contract parity, 4 separate assertions | `nonExecutableOperations.test.js` (table above) |
| Unit (JS) | `execute` wrap blocks a denied key, passes an allowed one | pure-function test, no swagger-ui mount |
| Unit (JS) | `loadContract` overwrites `servers`; maps 401/403/500 to a message | mocked `authedFetch` |
| Unit (JS) | VIEWER: no nav node, deep link renders `AccessDenied` | `nav-config.test.js`, `App.test.jsx` |
| Manual | 10 denied render a reason and no affordance; one allowed executes; bundle chunk measured | recorded in apply |
| CI | Docker build serves the document | `docker-smoke.yml` |

## Threat matrix

| Boundary | Applicability | Reason |
|---|---|---|
| Documentation-like paths | **N/A** | `docs/openapi.yaml` is copied as an opaque resource and parsed as data; nothing classifies or executes it |
| Git repository selection | **N/A** | no VCS invocation |
| Commit state / Push state / PR commands | **N/A** | no shell, subprocess or PR automation is added |

The one real boundary is HTTP routing, which the matrix does not cover: it is closed by the
`ApiRoutePolicy` ADMIN row (asserted in three tests) and by the served classpath location
being a compile-time constant with no path variable.

## Migration / rollout

No migration, no Flyway change, nothing byte-frozen, no rollback SQL owed. No new
environment variable; `RequiredEnvVarsGuard` untouched. Two new npm dependencies.

## Open questions

- [ ] **Q1 remains formally unverified** and is deliberately designed around rather than
      answered (ADR-1). If a later session can reach Context7 or the swagger-ui source, the
      answer changes nothing in this design — it only tells us whether the safety was free.
- [ ] Trigger to record: **a second ADMIN account** turns the deny-list from a convenience
      into a real permission boundary and its width must be revisited (Addendum, Q4).
