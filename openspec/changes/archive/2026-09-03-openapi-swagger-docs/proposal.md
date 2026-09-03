# Proposal — `openapi-swagger-docs` (change 1 of 2)

Phase: `sdd-propose` · Date: 2026-09-03 · Input: `explore.md` (227 lines, orchestrator-verified)

## Intent

The request was "document the API with Swagger so we can delete the giant explanation".
The honest number, from exploration: the three API documents total **1454 lines**, and
OpenAPI retires **~477 of them (33%)**. The remaining **~969 are rationale** — 401-vs-403
semantics, per-owner data scoping, the deliberately unscoped favourites count in the
`DELETE /api/db/productos` guard, the four identical login 401s, the CSRF-nonce design.
No generated document can carry any of it. `DOC-1` **relocates** that bucket; it is not
deleted.

This change ships the mechanical third as a machine-readable contract and makes it
untestable-by-drift, which is this project's established habit (`docs/DATABASE.md`
rollback SQL is executed by tests; a frontend test reads `config.properties`).

## Scope

### In scope

- **`docs/openapi.yaml`** — hand-maintained OpenAPI 3.1, covering every live route:
  path, method, tags, parameters, security requirement, status codes. No runtime
  dependency, no new attack surface, no `ApiRoutePolicy` row.
- **`OpenApiRouteCoverageTest`** — the drift guard. **A hard requirement of this change,
  not a follow-up.** Without it, a hand-written YAML carries the current markdown's rot
  risk plus OpenAPI's false authority.
- **`docs/API_REFERENCE.md`** survives, reduced to its ~644 rationale lines. Its ~450
  mechanical lines move to the YAML.
- **`docs/ARCHITECTURE.md`** gains a short index paragraph and **does not absorb** the
  rationale — mirroring how `CLAUDE.md` keeps the whole database topic in
  `docs/DATABASE.md` and lets `ARCHITECTURE.md` only point at it. `ARCHITECTURE.md` is
  791 lines; absorbing 969 more would more than double it and mix HTTP semantics with
  scrapers, ML and outfits.
- **`CLAUDE.md` §API REST (186–249)** shrinks to the ~8 navigational lines a fresh
  session needs to find things.

### Out of scope — deferred to change 2

- springdoc, Swagger UI, `@Schema`/`@ApiResponse` annotations, an `OpenAPI` bean.
- `ApiRoutePolicy` rows for `/v3/api-docs` or `/swagger-ui/**`, and the
  `RouteCoverageTest.thePermitListIsExactlyWhatWeExpect()` `hasSize(6)` bump they force.
- Any typed-DTO refactor of `ApiController`.
- `docs/FRONTEND_AUTH_CONTRACT.md` — **untouched**. ~97% client-behaviour rationale,
  distinct audience, Swagger does not reach it.

**Why change 2 is gated:** springdoc infers schemas by reflection on the Java return
type, and there is not one concrete DTO in the web layer (`ApiController` alone:
33 `ResponseEntity<ObjectNode>` + 21 `<Object>` + 3 `<String>` against 56 mappings). It
would emit `{"type":"object"}` with no properties for `GET /api/data` and
`GET /api/producto/{key}` — the two endpoints that matter most. Change 2 requires typing
those two first. When it lands, the doc endpoints must be **ADMIN**, not `PERMIT`:
`/v3/api-docs` enumerates the shape of `DELETE /api/db/productos`, `/api/agent/**` and
`/api/usuarios/**`, and `ApiRoutePolicy:135` already treats `GET /api/db/export` as a
bulk-exfiltration read.

## Capabilities

### New capabilities

- `api-contract-documentation`: the checked-in OpenAPI document is the single source of
  the mechanical HTTP contract, and a test proves path+method parity with the live
  routes and the security matrix in both directions.

### Modified capabilities

- None. No runtime behaviour changes; nothing under `src/main` is touched except an
  optional test-scoped `pom.xml` entry.

## Approach

### The drift guard, and its exact limit

A sibling to `RouteCoverageTest`, pure reflection plus a YAML parse — **no
`@SpringBootTest`**, matching the rest of the security suite. Two directions:

| Direction | Source of truth | Catches |
|---|---|---|
| documented → live | `ApiRoutePolicy.coincide(String, String)`, already `public static` at line 229 | documented-but-denied: a YAML path+method that resolves to no policy row, i.e. would 403 |
| live → documented | reflection over `@*Mapping` on `ar.scraper` controllers | live-but-undocumented: a real route missing from the YAML |

> **Finding that revises the brief.** Direction 2 must scan the *controllers*, not the
> policy table. Ten policy patterns are wildcards (`/api/agent/**`, `/api/db/**`,
> `/api/cron/**`, `/api/usuarios/**`, `/api/ml/**`, `/api/sitios/**`, `/api/producto/**`,
> `/api/suplementos/**`, `/api/outfits/saved/**`, `/api/financiacion/presets/**`). A
> wildcard cannot be enumerated, so "every concrete policy pattern appears in the YAML"
> would check ~38 of ~75 routes and be blind to every route behind a wildcard — which is
> the whole agent, the whole DB surface, and all user administration. `RouteCoverageTest`
> already solves this: `rutasDeLaAplicacion()` (line 161) reads the real annotations and
> `concretar()` (line 196) rewrites `{key}` → `x` so a matcher can be asked. Reuse both.

**State this limit in the proposal, in the test's javadoc, and in the YAML header, or it
will be trusted more than it deserves: the guard proves path and method parity only,
never response-shape accuracy.** The handlers return opaque `ObjectNode`; no test can
assert a documented schema matches what a handler emits, short of the typed-DTO refactor
that gates change 2.

### Delivery

Two work-unit commits, in this order — the second is only non-lossy because the first
landed:

1. `feat(docs): add the OpenAPI contract and its bidirectional drift guard` — adds
   without removing. Docs stay correct throughout.
2. `docs(api): retire the mechanical contract from the markdown` — removes only what
   commit 1 already covers.

## Affected areas

| Area | Impact | Est. changed lines (`+`/`−`) |
|---|---|---|
| `docs/openapi.yaml` | New | +850 – +1100 |
| `scraper/src/test/java/ar/scraper/security/OpenApiRouteCoverageTest.java` | New | +170 – +200 |
| `docs/API_REFERENCE.md` | Modified | ~490 (−450 / +40) |
| `CLAUDE.md` §186–249 | Modified | ~64 (−56 / +8) |
| `docs/ARCHITECTURE.md` | Modified | +12 |
| `scraper/pom.xml` | Modified | +5, only if SnakeYAML must be declared (see Q1) |
| **Total** | | **~1590 – 1870** |

**Runtime endpoints affected: none.** All ~75 routes are described; zero behaviour
changes, no migration, no policy row, no new env var, no restart semantics.

### Review budget — this does NOT fit, and I am not assuming an exception

Budget is **800** changed lines; delivery strategy is `single-pr`. The estimate is
**~2×** that. The commit boundary above splits it as **~1030–1300** (contract + guard)
and **~566** (retirement) — the second slice fits, the first still does not.

The first slice cannot be sub-sliced without weakening the guard: direction 2 fails
unless *every* live route is in the YAML, so the YAML is atomic. Two ways forward, and
**this needs the user's decision, not mine**:

- **(a) Recommended — accept `size:exception` for slice 1.** The YAML is mechanical
  transcription verified by a passing bidirectional guard, so per-line reviewer load is
  far below a 1000-line logic diff.
- **(b)** Split the YAML by tag group behind a temporary explicit exemption set in the
  guard, which the final slice must empty. Honest, but it ships a weakened guard, which
  is the exact failure mode this change exists to prevent.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Slice 1 exceeds the 800-line budget | **Certain** | Explicit decision above; no silent exception |
| Hand-written YAML drifts, with OpenAPI's added authority | High without the guard | The guard is in-scope, not deferred |
| Guard read as proving response shapes | Medium | Limit stated in three places: proposal, test javadoc, YAML header |
| Rationale lost during the markdown reduction | Medium | Slice ordering: nothing is deleted before the YAML covers it; the five rationale clusters are located by line number in `explore.md` |
| Direction-2 blind spot behind wildcards | Was high | Closed by scanning controllers rather than the policy table |
| SnakeYAML absent from the test classpath | Low | Q1 — confirm before writing the test |

## Rollback plan

- **Slice 2** — `git revert` restores `docs/API_REFERENCE.md`, `CLAUDE.md` and
  `docs/ARCHITECTURE.md` verbatim. Nothing else references the removed lines.
- **Slice 1** — `git revert` deletes one new doc file, one new test class and (if added)
  one `test`-scoped `pom.xml` entry. No `src/main` change, so no rebuild of the jar is
  required and no running instance behaves differently.
- Reverting slice 1 while slice 2 is live would leave the mechanical contract
  undocumented, so **revert 2 before 1**.
- No Flyway migration, so nothing is byte-frozen and no rollback SQL is owed.

## Dependencies

- A YAML parser on the **test** classpath only. See Q1 — unconfirmed.
- No production dependency. No new environment variable; `RequiredEnvVarsGuard`
  unaffected.

## Success criteria

- [ ] `docs/openapi.yaml` parses as valid OpenAPI 3.1 and describes every live route.
- [ ] `OpenApiRouteCoverageTest` is green and fails red when a route is added to a
      controller without a YAML entry, and when a YAML entry resolves to no policy row.
- [ ] Whole suite green on **each** commit (`TEST-1`), built with `clean`.
- [ ] `docs/API_REFERENCE.md` retains all five rationale clusters named in `explore.md`.
- [ ] No fact appears in two documents (`DOC-1`); `DOC-2`'s update table honoured.
- [ ] `docs/FRONTEND_AUTH_CONTRACT.md` byte-identical.
- [ ] Conventional subjects naming the behaviour (`COMMIT-1`, `COMMIT-2`), no AI
      attribution (`COMMIT-3`), test and doc travelling with their code (`COMMIT-5`).

## Open questions

| # | Question | Why I could not settle it |
|---|---|---|
| Q1 | Is SnakeYAML already on the test classpath? | Boot 3.2.5 pulls `org.yaml:snakeyaml` 2.2 transitively via `spring-boot-starter` ← `spring-boot-starter-web`, and no `org.yaml` import exists anywhere in the repo today. **I had no shell tool in this phase, so I could not run `mvn dependency:tree`.** If it is only transitive, declare it `test`-scoped explicitly rather than inheriting it. `jackson-dataformat-yaml` is **not** present and would be a new dependency. |
| Q2 | `docs/openapi.yaml` or repo root? | Proposed `docs/` for consistency; the repo has no precedent for a machine-readable contract artifact. |
| Q3 | Duplicate `rutasDeLaAplicacion()`/`concretar()` (~35 lines) into the new test, or extract to a shared test helper? | Extraction edits `RouteCoverageTest`, which the `CODE-2` refactor contract makes a deliberate call rather than a default. For `sdd-design`. |
| Q4 | Slice 1 budget: `size:exception` (a) or weakened-guard split (b)? | A delivery decision that is the user's, not the executor's. |

## Proposal question round

Interactive mode calls for a question round before finalizing, and this sub-agent has no
tool to ask directly. These are the product questions whose answers would change the
proposal — the user may answer, skip, correct the framing, or ask for a second round.

1. **Who is the reader?** The proposal assumes the audience is you plus a future session
   of this project. If a third-party or frontend consumer is expected to read the
   contract, examples and response schemas stop being optional and change 2's priority
   rises sharply.
2. **What does "documented" have to mean to be worth it?** Path + method + auth + status
   codes is what this change delivers and what the guard can defend. Request/response
   *shapes* are hand-written prose in either approach until the DTO refactor. Is the
   narrower contract genuinely useful to you, or only the full one?
3. **Is a rendered UI part of the outcome you wanted?** "Swagger" usually implies
   try-it-out. This change ships a YAML renderable by Redoc or any editor, but nothing
   interactive is served. If the interactive UI *is* the point, change 2 is the real
   request and this one is groundwork.
4. **Q4, restated as a product tradeoff:** one over-budget but complete and guarded
   slice, or two in-budget slices where the guard is temporarily weaker than advertised?
5. **Anything in the ~969 rationale lines you would rather delete than relocate?**
   `DOC-1` relocates by default, but the exploration did not ask whether any of it is
   stale rather than load-bearing.

**Assumptions needing review if the round is skipped:** the audience is internal; the
narrow path+method contract is worth shipping on its own; no interactive UI in this
change; the whole rationale bucket is load-bearing and stays.

---

## Addendum — user decisions after the question round (2026-09-03)

Recorded by the orchestrator. These answers settle Q4 and reshape change 2; the
scope of **this** change is unchanged.

**Q4 — budget: `size:exception` accepted for slice 1.** The user explicitly
accepted going over the 800-line review budget rather than shipping a temporarily
weakened guard. Rationale on record: ~900 lines of mechanical YAML verified by a
passing bidirectional guard costs a reviewer far less per line than a logic diff of
the same size, and the guard is complete from birth.

**The interactive UI is the actual goal, and that changes change 2 — for the
better.** The user confirmed that try-it-out was the point, not just a machine-
readable contract. The obvious reading of that answer is that change 2 must be
springdoc plus a typed-DTO refactor of `ApiController`'s 56 handlers. It does not.

`springdoc.swagger-ui.urls[].url` points Swagger UI at an arbitrary URL, and
`springdoc.api-docs.enabled: false` turns off the reflection-generated spec
(verified against current springdoc documentation, not recalled). So the
interactive UI can render **this change's hand-written `docs/openapi.yaml`**.

Three consequences:

1. `docs/openapi.yaml` is not scaffolding to be discarded once springdoc lands. It
   is the artifact the UI serves, so none of slice 1's work is throwaway.
2. The typed-DTO refactor of `ApiController` stops being a precondition for the UI.
   It remains worth doing on its own merits, but it is no longer blocking, and the
   empty-schema problem never arises because the schemas are hand-written and
   guarded rather than reflected.
3. Change 2 shrinks from "new dependency + 56-handler refactor + annotations" to
   "render an existing contract", and the ADMIN-gating decision still applies to
   whatever route exposes it.

**Open for `sdd-design` (change 2, not this one):** whether the UI is served by the
backend (a static resource plus its own `ApiRoutePolicy` row, on an API-only
backend that deliberately does not serve the SPA) or by the React frontend (no
backend change, no policy row, reusing `authSession.js` for the bearer token via
Swagger UI's `requestInterceptor`, and the existing role-aware hiding). The second
is more consistent with the standing decoupling decision; neither is committed.
