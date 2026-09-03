# Exploration — `openapi-swagger-docs`

Phase: `sdd-explore` · Date: 2026-09-03 · Status: complete (persisted by the
orchestrator; the phase agent had no write tool).

## The request

> "Document the API with Swagger so we can delete the giant explanation from
> `CLAUDE.md`."

## The number the request turns on

Three documents make up "the giant explanation": `CLAUDE.md` §API REST,
`docs/API_REFERENCE.md`, and `docs/FRONTEND_AUTH_CONTRACT.md` — **1454 lines
combined**. Classified line by line into three buckets:

| File | Lines | A — mechanical contract | B — rationale | C — navigation |
|---|---|---|---|---|
| `CLAUDE.md` §API REST (186–249) | 64 | 17 | 39 | 8 |
| `docs/API_REFERENCE.md` | 1094 | ~450 | ~644 | — |
| `docs/FRONTEND_AUTH_CONTRACT.md` | 296 | ~10 | ~286 | — |
| **Total** | **1454** | **~477 (33%)** | **~969 (67%)** | **~8** |

**OpenAPI genuinely retires about a third. The other two thirds are rationale**,
and rule `DOC-1` sends rationale to `docs/ARCHITECTURE.md` — it is relocated, not
deleted.

That 33% is a ceiling, not a forecast. See "The untyped-response finding".

Rationale that no generated document can carry, with locations:

- "401 and 403 are not the same thing" — `CLAUDE.md:210-212`,
  `FRONTEND_AUTH_CONTRACT.md:91-98`, and throughout `API_REFERENCE.md`.
- Personal data scoped by owner, with no unscoped variant in existence —
  `CLAUDE.md:214-220`.
- The `DELETE /api/db/productos` guard counting *everyone's* favourites on
  purpose — `CLAUDE.md:222-225`, `API_REFERENCE.md:919-930`.
- Why login returns four identical 401s for four distinct failure causes
  (timing-attack mitigation) — `API_REFERENCE.md:107-133`.
- The whole CSRF-nonce / cold-boot-admission / `SameSite`-ignores-port design —
  `API_REFERENCE.md:155-277` and nearly all of `FRONTEND_AUTH_CONTRACT.md`.

`docs/FRONTEND_AUTH_CONTRACT.md` is ~97% rationale. It is a client-behaviour
guide, not an endpoint reference; Swagger does not touch it at all.

## The untyped-response finding

This is the finding that decides the approach, and it was verified directly
against the tree, not inferred.

Five classes carry `@RestController`: `RootController`, `AuthEndpoints`,
`CronApiController`, `UsuarioAdminEndpoints`, `ApiController`. Every other
`*Endpoints` class is a plain delegate with no Spring mapping annotations.
`ApiController` alone declares **56 method-level mappings** and delegates the
work; total live routes ≈ 75 (`RouteCoverageTest.theScanIsNotVacuous()` asserts
`> 40`).

Handler return types across `scraper/src/main/java/ar/scraper/web/`:

```
33  ApiController          ResponseEntity<ObjectNode>
21  ApiController          ResponseEntity<Object>
11  AuthEndpoints          ResponseEntity<ObjectNode>
 9  ScrapeControlEndpoints ResponseEntity<ObjectNode>
 …  (every remaining class: ObjectNode / ArrayNode / JsonNode / Object / String)
```

**There is not one concrete DTO return type in the entire web layer.** Even the
response builder for the highest-traffic endpoint is untyped: `ProductJson.escribir(ObjectNode, Product)`
writes fields onto a generic Jackson tree. The only concrete typed shape anywhere
is the *request* body of `POST /agent/apply` (`ReclassifyProposal`).

springdoc infers schemas from the Java return type by reflection.
`ObjectNode`/`Object` carry no field information, so springdoc emits
`"schema": {"type": "object"}` with **no properties** for essentially every
response — `GET /api/data`, `GET /api/producto/{key}`, `GET /ml/estado`, all of
`/api/agent/*`. `@Schema` annotations can supply the shape by hand, but that is
transcription of the same prose that already exists in `API_REFERENCE.md`: not
derived from the code, and exactly as driftable as the markdown it replaces.

## The security matrix is the gate, not a detail

`ApiRoutePolicy.TABLE` has no catch-all; the chain ends in
`anyRequest().denyAll()` (`SecurityConfig.java:101`). `/v3/api-docs`,
`/v3/api-docs.yaml`, `/swagger-ui.html` and `/swagger-ui/**` are absent from the
table, so **adding springdoc changes nothing observable until rows are added** —
the endpoints 401/403. That fails closed, which is the safe direction and
consistent with the codebase's posture.

Two consequences worth stating plainly:

- `RouteCoverageTest` will **not** catch a missing row for springdoc's routes:
  its reflection scan is `findCandidateComponents("ar.scraper")`
  (`RouteCoverageTest.java:166`), and springdoc registers controllers under
  `org.springdoc.*`. Nothing forces the rows to exist and nothing confirms they
  are right.
- `RouteCoverageTest.thePermitListIsExactlyWhatWeExpect()` hardcodes
  `.hasSize(6)` (line 90). Making any Swagger route `PERMIT` breaks that
  assertion **by design** ("any growth should be deliberate"), and the bump must
  ship in the same PR.

**Recommended access level: ADMIN, not PERMIT and not bare AUTHENTICATED.** Once
annotated, `/v3/api-docs` enumerates the exact shape of `DELETE /api/db/productos`,
`/api/agent/**` and `/api/usuarios/**`. `ApiRoutePolicy` already treats
`GET /api/db/export` as "a bulk-exfiltration read, not a benign one" (line 135),
and this app is explicitly designed to run beyond localhost behind TLS
(`API_REFERENCE.md:146-152`). "It's just docs" is not a safe default here.

## Blast radius elsewhere: small

`CorsConfig`'s catch-all `/**` mapping already allows every allow-listed origin
for GET with `allowCredentials(false)` (`CorsConfig.java:121-125`), so a
frontend-hosted UI fetching `/v3/api-docs` with a bearer header needs no new CORS
mapping. No new required environment variable; `RequiredEnvVarsGuard` is
unaffected. The existing `dev` profile is a natural toggle point
(`springdoc.swagger-ui.enabled`), but a toggle is a convenience on top of the
ADMIN row, never a substitute for it — `SecurityConfig` is what actually
enforces.

## Approaches

### 1 — `springdoc-openapi-starter-webmvc-ui` with annotations on controllers

- **For**: standard; paths, verbs and params stay in sync with method signatures
  for free; interactive try-it-out UI.
- **Against**: does not solve the untyped-response problem. Every `ObjectNode`
  return still needs hand-written `@Schema`/`@ApiResponse` content to say
  anything, which is the same effort as prose and just as driftable — an
  `@ApiResponse` can lie exactly like a paragraph can. A minimum-viable pass
  (paths, params, tags; no real schemas) is ~300–500 changed lines across ~75
  endpoints plus the dependency, the policy rows and an `OpenAPI` bean — at or
  over the 800-line budget, and it still leaves `GET /api/data` and
  `GET /api/producto/{key}` with empty schemas.
- **Effort**: medium for a low-value MVP; high for real fidelity, likely
  multi-PR.

### 2 — springdoc emits JSON only; Swagger UI served from the React frontend

- **For**: respects the standing "backend is API-only, does not serve the SPA"
  decision; can reuse `authedFetch` and role-aware hiding so a VIEWER never sees
  the affordance, matching the established pattern.
- **Against**: same untyped-response ceiling as (1), plus a new frontend
  dependency and page (~100–150 lines) on top of the same backend annotation
  cost, plus the `/v3/api-docs` policy row. Strictly more lines for the same
  fidelity.
- **Effort**: medium-high.

### 3 — Hand-maintained `openapi.yaml`, no runtime dependency

- **For**: no new dependency, no new attack surface, no new policy rows (a pure
  repo artifact, renderable with Redoc or any editor); fidelity is whatever the
  author writes — the same ceiling as (1) and (2) for the mechanical bucket,
  without the machinery. Fits the 800-line budget comfortably, since it is
  transcription of the ~450 mechanical lines already located.
- **Against**: **nothing stops it from drifting on its own.** Same rot risk as
  the current markdown, with the added false authority of being OpenAPI. Only
  honest when paired with the coverage test below.
- **Effort**: low.

**Version**: `org.springdoc:springdoc-openapi-starter-webmvc-ui` **2.8.x** is the
Spring Boot 3.x / Jakarta line, matching the pinned Spring Boot 3.2.5
(`scraper/pom.xml:16`). The springdoc 3.x line targets Spring Boot 4 and is wrong
here. Default paths: `/v3/api-docs` (+`.yaml`), UI at `/swagger-ui.html`
redirecting to `/swagger-ui/index.html`, assets under `/swagger-ui/**`.
*(Resolved via web search — the Context7 MCP tool was not invokable in the phase
agent's session. Re-confirm the exact patch version at apply time.)*

## The drift guard

This project's habit is to make documentation untestable-by-drift: the rollback
SQL in `docs/DATABASE.md` is executed by tests, and a frontend test reads
`config.properties` so the price band cannot diverge. The equivalent exists here
and should be a hard requirement of whichever approach ships.

`ApiRoutePolicy.coincide(String patron, String path)` is already `public static`
(`ApiRoutePolicy.java:229`) and is the reusable primitive.

- **For approach 3**: a new `OpenApiRouteCoverageTest`, sibling to
  `RouteCoverageTest`, parses the checked-in `openapi.yaml` `paths` map and
  cross-checks **both directions** — every documented path+method resolves to a
  real policy row (catches documented-but-denied), and every concrete policy
  pattern appears in the YAML (catches undocumented-but-live). Pure reflection
  plus a YAML parse; no Spring context.
- **For approaches 1 and 2**: the same cross-check, but it needs a live
  `@SpringBootTest` fetching `/v3/api-docs` at runtime — heavier than the
  reflection-only pattern the rest of the security suite uses.

**Limitation to state wherever the guard is documented**, or it will be read as
stronger than it is: the guard protects **path and method parity only, never
response-shape accuracy**. Because the handlers return opaque `ObjectNode`, no
test can assert a schema matches what a handler actually emits, short of the
typed-DTO refactor.

## Recommendation

Do not ship this as one change.

1. **First PR** (fits the 800-line budget): relocate the ~969 rationale lines to
   `docs/ARCHITECTURE.md` per `DOC-1`, trim `CLAUDE.md`'s API section to a
   pointer, and ship a hand-maintained `openapi.yaml` covering the ~450
   mechanical lines, paired with the bidirectional coverage test. No new
   dependency, no new attack surface, no security-gating decision to get wrong.
2. **Separate follow-up change**: springdoc for a live interactive UI, gated
   behind typing at least `GET /api/data` and `GET /api/producto/{key}` first,
   ADMIN-gated in `ApiRoutePolicy`, on its own budget. Bundling it with the doc
   retirement either blows the budget or ships empty schemas for the two
   endpoints that matter most.

## Risks

- Bundling annotation-based springdoc with the doc retirement in one PR exceeds
  the 800-line review budget, or delivers empty schemas for the highest-value
  endpoints.
- A missing `ApiRoutePolicy` row for springdoc's own routes fails closed (403)
  but silently — `RouteCoverageTest`'s package-scoped scan cannot see it.
- `PERMIT` on Swagger UI hands an anonymous caller a full map of ADMIN-only
  mutation shapes on an app designed to run beyond localhost.
- A hand-written `openapi.yaml` without the coverage test carries the current
  markdown's drift risk with more perceived authority.
- `RouteCoverageTest.thePermitListIsExactlyWhatWeExpect()`'s `hasSize(6)` must be
  bumped deliberately if any Swagger route becomes `PERMIT`.

## Ready for proposal

Yes — provided the two-change split is explicit in the proposal's scope and
non-goals. Without it, `sdd-propose` will scope a single PR that either blows the
budget or under-delivers on schema fidelity.
