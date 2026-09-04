# Interactive API Console Specification

## Purpose

An ADMIN-only console at `/apidocs` renders the checked-in OpenAPI contract
(`docs/openapi.yaml`) via `swagger-ui-react`, authenticated by the existing
session, and lets an ADMIN execute real bearer-authenticated calls against the
running backend for every documented operation except an explicit,
test-guarded deny-list of ten irreversible ones.

## Non-Goals

- springdoc or any reflection-generated schema (`@Schema`, `@ApiResponse`, an `OpenAPI` bean).
- The typed-DTO refactor of `ApiController` handlers.
- A `servers` block declaring more than the single configured origin.
- Any confirmation-dialog mechanism — the deny-list is binary, not a speed bump.
- Editing `docs/openapi.yaml` beyond the new endpoint's own entry.

## Requirements

### Requirement: Document Access Is ADMIN, Enforced by the Backend

`GET /api/openapi.yaml` MUST resolve to `Access.ADMIN` in `ApiRoutePolicy.TABLE`.
A request carrying a valid, unexpired token for a non-ADMIN role MUST be
rejected by the policy table itself, never by a frontend layer.

#### Scenario: An ADMIN fetches the document

- GIVEN a valid ADMIN bearer token
- WHEN `GET /api/openapi.yaml` is requested
- THEN the response is `200` with the YAML body

#### Scenario: A VIEWER is rejected at the policy layer

- GIVEN a valid, unexpired VIEWER bearer token
- WHEN `GET /api/openapi.yaml` is requested
- THEN `ApiRoutePolicy` returns `403`, independent of any SPA route guard

#### Scenario: An unauthenticated request is rejected

- GIVEN no bearer token
- WHEN `GET /api/openapi.yaml` is requested
- THEN the response is `401`

### Requirement: Frontend Role Layers Are Cosmetic, Never the Gate

The nav visibility filter and the `RequireRole` route guard MUST NOT be relied
on for enforcement — they exist for UX only. `ApiRoutePolicy`'s `ADMIN` row on
`GET /api/openapi.yaml` MUST remain the sole mechanism denying a non-ADMIN.
Removing either frontend layer MUST NOT expose the document to a non-ADMIN.

#### Scenario: Nav hides the entry point

- GIVEN a VIEWER session
- WHEN the sidebar renders
- THEN no `/apidocs` nav node exists in the DOM

#### Scenario: A deep link is explicit, not a silent redirect

- GIVEN a VIEWER navigates directly to `/apidocs`
- WHEN `RequireRole` evaluates the route
- THEN it renders `AccessDenied`, and the document is never fetched because
  the backend refuses it regardless

### Requirement: The Deny-List Blocks Exactly Ten Operations

The console MUST disable try-it-out, with a stated reason, for exactly the ten
operations that destroy data, change who can log in, or mutate the caller's
own session. Every other documented operation MUST remain executable.

#### Scenario: A denied operation shows no Execute button

- GIVEN `DELETE /api/db/productos` is on the deny-list
- WHEN its operation panel renders
- THEN no Execute button appears, and a reason string is shown instead

#### Scenario: An allowed operation is executable

- GIVEN `GET /api/data` is not on the deny-list
- WHEN its operation panel renders
- THEN Execute is present and, when clicked, performs a real authenticated call

### Requirement: The Deny-List Cannot Silently Rot

Every deny-list key MUST resolve to a real `METHOD path` operation present in
`docs/openapi.yaml`, verified by an automated test. A path rename that leaves
a stale key MUST fail that test rather than silently un-denying the operation.

#### Scenario: A stale key fails the guard

- GIVEN a deny-list key naming a path no longer in `docs/openapi.yaml`
- WHEN the guard test runs
- THEN it fails, naming the stale key

### Requirement: Refresh Operations Are Non-Executable by Design

`POST /api/auth/refresh` and `DELETE /api/auth/refresh` MUST be on the
deny-list. This is deliberate, not a gap: `authSession.js` keeps the
`X-Refresh-CSRF` nonce module-private and never exports it, so no console call
could supply it regardless of deny-list membership.

#### Scenario: Refresh operations state the real reason

- GIVEN `POST /api/auth/refresh`
- WHEN its operation panel renders
- THEN the shown reason names the unavailable CSRF nonce, not a generic denial

### Requirement: The Document Is Delivered Identically Across Install Paths

`GET /api/openapi.yaml` MUST stream the document from a classpath resource,
never from a filesystem path relative to `docs/`. This MUST hold under
portable, POSIX, and Docker topologies alike.

#### Scenario: Docker serves the document

- GIVEN the backend built and run inside the `Dockerfile` container, where no
  `docs/` directory exists at any filesystem depth
- WHEN `GET /api/openapi.yaml` is requested by an ADMIN
- THEN the response is `200` with the full YAML body

### Requirement: Existing Contract Guards Stay Green

Per `CODE-2`, `OpenApiRouteCoverageTest` and `RouteCoverageTest` MUST both keep
every pre-existing `@Test` body and assertion unchanged after this change
ships, with the new route as their only new input. No other documented
operation's path, method, or access level MUST change. Per `TEST-1`, the whole
suite MUST be green on each commit.

#### Scenario: The new route satisfies both coverage directions

- GIVEN `GET /api/openapi.yaml` is live and documented as `ADMIN`
- WHEN `OpenApiRouteCoverageTest` runs both directions
- THEN neither reports it as documented-but-denied or live-but-undocumented
