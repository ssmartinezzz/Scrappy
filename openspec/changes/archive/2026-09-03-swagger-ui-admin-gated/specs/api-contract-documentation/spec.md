# Delta for API Contract Documentation

This delta retires one Non-Goals line now made false by `swagger-ui-admin-gated`
and extends Contract Completeness by the one new route it adds. No other
requirement in `openspec/specs/api-contract-documentation/spec.md` changes.

## Non-Goals Update

Remove:

> springdoc, Swagger UI, or any served interactive documentation endpoint.

Replace with:

> Reflection-generated schemas (springdoc, `@Schema`/`@ApiResponse`, an
> `OpenAPI` bean). A served interactive endpoint now exists
> (`interactive-api-console`), but it renders this hand-written YAML — it does
> not generate it, and adds no schema-reflection machinery.

The remaining two Non-Goals bullets (typed-DTO refactor, response body schema
accuracy) are untouched.

## MODIFIED Requirements

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

(Previously: identical, without the `GET /api/openapi.yaml` sentence — that
route did not exist.)

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
