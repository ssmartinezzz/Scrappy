# Interactive API Console Specification

## Purpose

A public console at `/apidocs` renders the OpenAPI contract via
`swagger-ui-react` and lets its reader execute real calls against the running
backend. The document it renders is **filtered server-side**: every operation
carrying `x-access: ADMIN` is stripped before serialising, so what reaches the
page is the `PERMIT` + `AUTHENTICATED` subset — exactly what a VIEWER may
reach. An authenticated reader's calls carry their bearer token; an anonymous
one can still read the document and execute the `PERMIT` operations. Every
served operation is executable except an explicit, test-guarded deny-list of
three that mutate the caller's own session.

## Non-Goals

- springdoc or any reflection-generated schema (`@Schema`, `@ApiResponse`, an `OpenAPI` bean).
- The typed-DTO refactor of `ApiController` handlers.
- A `servers` block declaring more than the single configured origin.
- Any confirmation-dialog mechanism — the deny-list is binary, not a speed bump.
- Editing `docs/openapi.yaml` beyond the endpoint's own entry — the checked-in
  file stays the complete contract; only the served body is filtered.
- Any nav entry, link or button anywhere in the app pointing at `/apidocs`.
- A per-role served document: there is one filtered body, identical for
  anonymous, VIEWER and ADMIN alike.

## Requirements

### Requirement: The Document Is Public, and Filtered by the Backend

`GET /api/openapi.yaml` MUST resolve to `Access.PERMIT` in
`ApiRoutePolicy.TABLE`. The response body MUST NOT contain any operation whose
`x-access` is `ADMIN`, and a path left with no operation after filtering MUST
be dropped from the response entirely rather than served as an empty object.
`info`, `servers`, `security`, `tags` and `components` MUST be served intact.
Filtering MUST happen in `OpenApiDocumentController`, never in the frontend:
a document filtered client-side has already crossed the wire.

#### Scenario: Anyone fetches the document

- GIVEN no bearer token, a VIEWER token, or an ADMIN token
- WHEN `GET /api/openapi.yaml` is requested
- THEN the response is `200` with the same filtered YAML body in all three cases

#### Scenario: An ADMIN operation is absent, not merely hidden

- GIVEN `DELETE /api/db/productos` carries `x-access: ADMIN` in `docs/openapi.yaml`
- WHEN the served document is parsed
- THEN no operation with that method and path exists in it, and the
  `/api/db/productos` path key is absent altogether

#### Scenario: The filter does not pass by returning nothing

- GIVEN the served document
- WHEN its operations are enumerated
- THEN `GET /api/data` (`AUTHENTICATED`) and `POST /api/auth/login` (`PERMIT`)
  are both present

#### Scenario: The bundled resource is untouched

- GIVEN filtering happens at serve time
- WHEN `OpenApiRouteCoverageTest` compares the classpath copy to `docs/openapi.yaml`
- THEN they are byte-identical

### Requirement: The Console Has No Entry Point in the App

No nav node, link or button anywhere in the app MUST point at `/apidocs`, for
any role. The route MUST NOT be wrapped in a role guard, and `/apidocs` MUST be
in `AuthGate`'s `PUBLIC_ROUTES` so an anonymous visitor is not redirected to
`/login`. Reaching the console MUST require typing the URL.

#### Scenario: Nav offers nothing, for any role

- GIVEN a VIEWER session, an ADMIN session, or no session
- WHEN the nav renders
- THEN no node with destination `/apidocs` exists in it

#### Scenario: An anonymous deep link reaches the console

- GIVEN no session
- WHEN `/apidocs` is opened directly
- THEN the console renders, and `AuthGate` does not redirect to `/login`

### Requirement: The Deny-List Blocks Exactly Three Operations

The console MUST disable try-it-out, with a stated reason, for exactly the
three operations that mutate the caller's own session: `POST /api/auth/login`,
`POST /api/auth/refresh` and `DELETE /api/auth/refresh`. Every other served
operation MUST remain executable. The seven destructive operations this list
previously named are all `x-access: ADMIN` and no longer reach the page at
all, so they MUST NOT be on it — a key naming an unservable operation would
document a protection the deny-list is not providing.

#### Scenario: A denied operation shows no Execute button

- GIVEN `POST /api/auth/login` is on the deny-list
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
- WHEN `GET /api/openapi.yaml` is requested
- THEN the response is `200` with the filtered YAML body

### Requirement: Existing Contract Guards Stay Green

`OpenApiRouteCoverageTest` MUST keep every pre-existing `@Test` body and
assertion unchanged. `RouteCoverageTest`'s permit-band size assertion MUST be
raised from six to seven deliberately, naming this change and the route as its
reason — its javadoc requires any growth of the credential-free surface to be
an explicit decision, not a number nudged to get green. No other documented
operation's path, method, or access level MUST change. Per `TEST-1`, the whole
suite MUST be green on each commit.

#### Scenario: The route satisfies both coverage directions

- GIVEN `GET /api/openapi.yaml` is live and documented as `PERMIT`
- WHEN `OpenApiRouteCoverageTest` runs both directions
- THEN neither reports it as documented-but-denied or live-but-undocumented

#### Scenario: The permit band grows by exactly one, on purpose

- GIVEN the permit band had six entries
- WHEN `GET /api/openapi.yaml` moves from ADMIN to PERMIT
- THEN `RouteCoverageTest` asserts seven, and names the new entry
