# API Contract Documentation Specification

## Purpose

`docs/openapi.yaml` becomes the single source of truth for the mechanical
HTTP surface — path, method, auth requirement, status codes — guarded by a
bidirectional test against `ApiRoutePolicy` and the live controllers. This
capability covers documentation and its automated guard only; no runtime
behavior changes.

## Non-Goals

Deferred to a follow-up change:

- Reflection-generated schemas (springdoc, `@Schema`/`@ApiResponse`, an
  `OpenAPI` bean). A served interactive endpoint now exists
  (`interactive-api-console`), but it renders this hand-written YAML — it does
  not generate it, and adds no schema-reflection machinery.
- `@Schema`/`@ApiResponse` annotations or an `OpenAPI` bean.
- Any typed-DTO refactor of `ApiController` handlers.
- Response body schema accuracy — the guard proves path+method parity only.

## Requirements

### Requirement: Contract Completeness

`docs/openapi.yaml` MUST describe every live route registered via `@*Mapping`
on an `ar.scraper` controller: path, method, and an auth requirement in
`ApiRoutePolicy.Access` vocabulary (`PERMIT`, `AUTHENTICATED`, `ADMIN`), plus
the status codes the handler actually returns. The six deliberately
unauthenticated routes in `ApiRoutePolicy.TABLE` Band A (`OPTIONS /**`,
`POST /api/auth/login`, `POST`/`DELETE /api/auth/refresh`,
`POST /api/auth/password-reset/request`, `.../confirm`, `GET /`) MUST each be
marked `PERMIT`. `GET /api/openapi.yaml` — the document-serving route added by
`swagger-ui-admin-gated` — MUST be marked `ADMIN` and MUST document itself,
closing the self-referential gap the route would otherwise open in Guard
Direction 2.

#### Scenario: A route's auth requirement matches its policy row

- GIVEN live route `GET /api/data`, resolved `AUTHENTICATED` by `ApiRoutePolicy`
- WHEN `docs/openapi.yaml` documents it
- THEN its security field states `AUTHENTICATED`, not `PERMIT`/`ADMIN`

#### Scenario: A PERMIT route needs no bearer token

- GIVEN `POST /api/auth/login`
- WHEN it appears in `docs/openapi.yaml`
- THEN it is marked `PERMIT` and carries no bearer-auth requirement

#### Scenario: The document-serving route documents itself

- GIVEN `GET /api/openapi.yaml`, resolved `ADMIN` by `ApiRoutePolicy`
- WHEN `OpenApiRouteCoverageTest` direction 2 scans live `ar.scraper.web` mappings
- THEN it finds a `docs/openapi.yaml` entry for `GET /api/openapi.yaml` marked
  `ADMIN`, and the test does not fail

### Requirement: Guard Direction 1 — documented-but-denied

`OpenApiRouteCoverageTest` MUST fail when a documented path+method resolves
to no row in `ApiRoutePolicy.TABLE` (i.e. would hit `denyAll()`), using
`ApiRoutePolicy.coincide`.

#### Scenario: A documented path with no policy row fails the guard

- GIVEN `docs/openapi.yaml` documents `GET /api/nonexistent`
- WHEN the test resolves it against `ApiRoutePolicy.TABLE`
- THEN it fails, naming the documented-but-denied path

### Requirement: Guard Direction 2 — live-but-undocumented

`OpenApiRouteCoverageTest` MUST fail when a real controller mapping, found by
reflection over `ar.scraper` controllers (mirroring
`RouteCoverageTest.rutasDeLaAplicacion()`), has no entry in
`docs/openapi.yaml` after path variables are concretized (mirroring
`concretar()`, `{key}` → `x`). This direction MUST scan controller
annotations, never `ApiRoutePolicy.TABLE`: several policy patterns
(`/api/agent/**`, `/api/db/**`, `/api/usuarios/**`, and seven others) are
wildcards and cannot be enumerated.

#### Scenario: A new wildcard-covered route fails when undocumented

- GIVEN a new mapping `GET /api/agent/history`, matched by the `/api/agent/**` wildcard
- WHEN it is added to a controller but not to `docs/openapi.yaml`
- THEN the test fails, because it scans the live mapping, not the wildcard

#### Scenario: A documented, live route passes both directions

- GIVEN `GET /api/status`, live and documented
- WHEN both guard directions run
- THEN neither reports it

### Requirement: The Guard Is Provably Non-Vacuous

`OpenApiRouteCoverageTest` MUST include a negative control proving its scan
finds real routes, mirroring `RouteCoverageTest.theScanIsNotVacuous()`
(`hasSizeGreaterThan(40)`), so a broken or empty scan cannot pass both
directions for free.

#### Scenario: An empty scan is caught before it can mask silence

- GIVEN the reflection scan is broken and returns zero routes
- WHEN the non-vacuousness assertion runs
- THEN it fails, rather than letting direction 2 report false coverage

### Requirement: Documentation Invariants

Per `DOC-1`, no fact MUST exist in two documents. `docs/API_REFERENCE.md`
MUST retain its five rationale clusters (401-vs-403 semantics; per-owner data
scoping; the unscoped `DELETE /api/db/productos` favourites guard; the four
identical login 401s; the CSRF-nonce/cold-boot design), located by line
number in `explore.md`. `docs/FRONTEND_AUTH_CONTRACT.md` MUST stay
byte-identical.

#### Scenario: Rationale survives the mechanical retirement

- GIVEN `docs/API_REFERENCE.md` explains the unscoped favourites guard
- WHEN mechanical path/method/status lines move to `docs/openapi.yaml`
- THEN that rationale paragraph still exists in `docs/API_REFERENCE.md`

#### Scenario: The frontend auth contract is untouched

- GIVEN `docs/FRONTEND_AUTH_CONTRACT.md` at its current content
- WHEN this change is applied
- THEN a diff of that file is empty

### Requirement: Non-Regression

This change MUST NOT modify `src/main`, add an `ApiRoutePolicy` row, add an
environment variable, or change any runtime response. Per `TEST-1`, the whole
suite MUST be green on each commit.

`RouteCoverageTest` MUST stay green. It MAY be modified only by the
assertion-free extraction of its route scanner into a shared test helper
(`LiveRoutes`), decided in `design.md` ADR-2: duplicating the scanner instead
would leave direction 2 fail-open, because a blind spot fixed in one copy
would silently stop requiring a live route to be documented. Under `CODE-2`'s
refactor contract the permitted diff is exactly delete-helpers /
add-delegating-wrappers / add-import — **no `@Test` body, `@DisplayName` or
assertion may change**. An edited assertion is proof the refactor broke
something.

#### Scenario: RouteCoverageTest's assertions survive the extraction

- GIVEN the pre-change `RouteCoverageTest.java` and its eight `@Test` methods
- WHEN the `LiveRoutes` extraction lands
- THEN all eight still pass and not one assertion or `@Test` body differs

#### Scenario: No runtime behavior changes

- GIVEN any live endpoint's current response
- WHEN this change ships
- THEN that response is byte-for-byte identical, since no `src/main` file changed
