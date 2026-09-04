# Exploration — `swagger-ui-admin-gated` (change 2 of 2)

Phase: `sdd-explore` · Date: 2026-09-03 · Persisted by the orchestrator (the
phase agent had no `Write`/`Bash`/`mem_save`). Every load-bearing claim below was
re-verified against the tree before persisting.

## Recommendation

**Frontend-served, and WITHOUT springdoc at all.** The React app renders
`swagger-ui-react`, fed by a small new `ar.scraper.web` controller that streams
`docs/openapi.yaml` from the classpath.

This corrects the framing carried over from change 1's design, which assumed
springdoc either way. The correction is the main finding:

**Both topologies need a backend endpoint.** ADMIN-gating cannot be enforced on a
static file shipped inside the frontend's own `dist/` — nginx and `vite preview`
serve static assets with no bearer check. So "frontend-served" never meant "no
backend involvement"; it means the *rendering shell* lives in the SPA.

And once the shell lives in the SPA, **springdoc's only remaining job is vendoring
`swagger-ui-dist` assets** — which `swagger-ui-react` already ships as an npm
package. Springdoc becomes pure machinery: a new backend dependency and 3–4
policy rows, bought for zero benefit.

**Estimated ~220–300 changed lines** (backend ~50–70, frontend ~150–220).
Comfortably inside the 800-line budget. **No `size:exception` needed**, unlike
change 1.

## Why frontend-served wins

| | Backend-served (springdoc) | Frontend-served (recommended) |
|---|---|---|
| New backend dependency | `springdoc-openapi-starter-webmvc-ui` 2.8.x + transitives | **None** — a ~15-line `@RestController` |
| New frontend dependency | none | `swagger-ui-react` |
| New `ApiRoutePolicy` rows | the document route **plus** `/swagger-ui/**`, `/swagger-ui.html`, `/webjars/**` | **one** |
| `RouteCoverageTest` blind spot | **Permanent.** springdoc registers under `org.springdoc.*`; the scan is `findCandidateComponents("ar.scraper")`. A forgotten row 403s silently and no test can ever see it | **None.** The one controller is in `ar.scraper.web`, inside the existing scan — covered for free by the guard that already exists |
| Settled decisions | **Reopens one.** `docs/ARCHITECTURE.md:317`, verified verbatim: *"el backend ahora es API-only (`SpaController` removido) y el frontend corre como servicio independiente"* (`decouple-services-postgres`, Batch 3, D6) | Fits the existing role-aware pattern used by `/cronjobs` and user administration |
| Auth reuse | n/a | `frontend/src/lib/authSession.js` already exports `getAccessToken()` (line 68, verified); `RequireRole` and the hidden-not-disabled nav pattern apply verbatim |

## How the YAML reaches the served location

ADR-1 of change 1 said this mirrors the `scraper/ml_*.py` shape. Directionally
right, mechanically different, and the difference matters:

- `ml_*.py` **already lives inside** `scraper/src/main/resources/ml/` (verified),
  so Maven bundles it with zero pom configuration. `MlScriptExtractor` then
  copies it *out* to a real file because a Python subprocess must `exec` a path.
- `docs/openapi.yaml` lives outside the resources tree by ADR-1's own (correct)
  rejection of moving it. So it needs a **real new `maven-resources-plugin`
  `copy-resources` execution** — genuinely new pom content, not "the same shape".
- But the runtime is **simpler** than `ml_*.py`: Spring streams a
  `ClassPathResource` straight into the response. No disk round-trip.

**New finding not in ADR-1 — the Docker path breaks silently.** `Dockerfile`'s
build stage copies only `scraper/pom.xml` and `scraper/src` (verified, lines
10–11). `docs/` is never in the build context, so the Maven copy step fails
inside the container. A `COPY docs/openapi.yaml` line is required, and its
absence breaks **only** Docker — invisible if verification runs on portable/POSIX.

**Rejected: reading `../docs/openapi.yaml` at runtime.** `DocumentedRollback`
walks up from `user.dir` to find `docs/DATABASE.md`, which is a real in-repo
precedent — but it works only because Maven's *test* JVM sits one hop below the
root. At runtime it would work in portable/POSIX (backend cwd is `scraper/`) and
**break in Docker**, where no `docs/` exists at any depth. Classpath bundling is
the only mechanism identical across all three install paths.

**Self-referential requirement, easy to forget:** the new endpoint is itself a
live route in `ar.scraper.web`, so change 1's `OpenApiRouteCoverageTest`
direction 2 goes RED the moment it exists unless `docs/openapi.yaml` documents
itself (~5–8 lines, `x-access: ADMIN`). A pleasing consequence: change 1's guard
becomes an active safety net for change 2's own surface.

## Try-it-out: what actually works

| Operations | Executable? | Why |
|---|---|---|
| Ordinary bearer-authenticated calls | **Yes** | `CorsConfig`'s catch-all already allows allow-listed origins with `allowCredentials(false)`; a `requestInterceptor` reading `getAccessToken()` supplies the header, exactly as `authedFetch` does today |
| `POST`/`DELETE /api/auth/refresh` | **No, and that is correct** | They need the `X-Refresh-CSRF` nonce, and `authSession.js` keeps the nonce module-private — it is never exported (verified). Beyond that, a stray Execute performing a live token rotation risks tripping `RefreshTokenService`'s reuse detection against the user's own session in another tab |
| `POST /api/auth/login` | Pointless | Duplicates the real login screen for someone already authenticated |
| **Destructive** (`DELETE /api/db/productos`, `DELETE /api/data`, `POST /api/scrape`, `DELETE /api/usuarios/{username}`, `PUT .../rol`) | **Unresolved — a decision, not a finding** | Stock Swagger UI has no confirmation dialog. A live Execute button beside `DELETE /api/db/productos` is a materially different risk from a document describing it |

## Branch dependency

`docs/openapi.yaml`, `LiveRoutes` and `OpenApiRouteCoverageTest` exist **only** on
`feat/openapi-contract-and-drift-guard` (PR #185, open, unmerged). This change
must branch from that branch, or wait for its merge to `master`.

Branching from `master` today: the file the controller serves does not exist, the
self-referential check has nothing to extend, and the eventual merge either
duplicates PR #185's ~1030-line commit inside this diff — defeating the point of
reviewing them separately — or conflicts outright.

## Install paths

No environment toggle is needed and `RequiredEnvVarsGuard` is untouched: the
ADMIN row is the real gate, and a toggle would be a convenience on top of it,
never a substitute. Under the recommended topology the feature is "one more
authenticated GET plus one more SPA route", so CORS, the CLI's `local`/`lan`
origin rewriting, and `VITE_API_BASE_URL` all apply unchanged. The only genuine
new requirement is the Docker `COPY` above.

## Risks

- Branching from `master` instead of PR #185's branch breaks the change or
  silently duplicates its diff.
- Shipping without an explicit destructive-operation policy risks a one-click
  irreversible catalogue wipe.
- Missing the Docker `COPY` breaks only the Docker install path, silently.
- Forgetting to document the new endpoint in `openapi.yaml` turns change 1's own
  guard red — surprising unless you know why.
- Choosing backend-served anyway permanently reopens the API-only boundary and
  creates policy rows no test can verify.

## Ready for proposal

Yes, once two things are decided by the user: the destructive-operation
try-it-out policy, and the base branch.
