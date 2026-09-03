# Design — `openapi-swagger-docs`

Phase: `sdd-design` · Date: 2026-09-03 · Inputs: `proposal.md` (+ Addendum), `explore.md`,
`RouteCoverageTest.java`, `ApiRoutePolicy.java`, `DocumentedRollback.java`.

## Technical approach

A checked-in `docs/openapi.yaml` plus a reflection-and-parse test, sibling to
`RouteCoverageTest`, no Spring context. The design's one non-obvious move: **the guard is
written and run RED before a single route is transcribed.** Its direction-2 failure output
is the complete, code-derived transcription worklist, which dissolves the "the guard only
catches omissions once the YAML exists" objection (`TEST-2`, red first).

Second non-obvious move: every operation carries `x-access`, mirroring `ApiRoutePolicy.Access`
verbatim. The guard then proves the documented **authorisation level**, not merely that a row
exists — upgrading a weak parity check into a real assertion about the security matrix.

## Architecture decisions

### ADR-1 — `docs/openapi.yaml` (Q2), and the choice is deferrable

**Context.** Change 2 has Swagger UI render this exact file, from either the backend
(static resource) or the frontend (Vite). Either could argue for moving it.

**Decision.** `docs/openapi.yaml`. Do not move it for change 2.

**Consequences.** Change 2 pays a build-time copy in either topology — Maven resource copy
into the jar, or a Vite copy into `dist/` — mirroring the settled `scraper/ml_*.py` shape:
one source of truth in the repo, a gitignored copy next to the artifact. Cost is 3–8 lines
of build config, identical under both topologies, so the topology decision stays open at
zero cost. **Rejected:** `scraper/src/main/resources/` — classpath-readable and Boot-served,
but static resources still hit the chain and `denyAll()`, so it buys change 2 nothing without
the policy row it would need anyway, while breaking this change's "nothing under `src/main`"
invariant and shipping docs inside every deployed jar. **Rejected:** repo root — no precedent;
`docs/` is where `DocumentedRollback` already resolves repo docs from a test.

### ADR-2 — Extract `LiveRoutes`, with zero assertions touched (Q3)

**Context.** `rutasDeLaAplicacion()` + `concretar()` + the `Ruta` record (~35 lines) are needed
twice. `CODE-2` makes editing `RouteCoverageTest` a deliberate call.

**Decision.** Extract to a package-private `ar.scraper.security.LiveRoutes` in the test tree.
`RouteCoverageTest` keeps two one-line private delegating wrappers and imports `Ruta`, so
**not one `@Test` body, `@DisplayName`, assertion or javadoc line changes** — its diff is
delete-helpers / add-two-lines / add-import.

**Consequences.** `CODE-2` is satisfied in the strongest available reading, and a reviewer
verifies it by diff shape in seconds. Duplication would have been worse than repetition:
knowledge of *how Spring mappings are declared* would live in two places, and a scanner
blind spot fixed in one copy would leave direction 2 silently not requiring a live route to
be documented — the fail-open direction. Precedent is explicit: `DocumentedRollback`'s
javadoc extracted for exactly this reason. The two delegating lines are accepted noise, paid
for by an assertion-free diff. `RoutePolicyShadowingTest.concretar` **stays put** — it rewrites
*patterns* (`/**` → `/x`), a different function from the path-template rewrite.

### ADR-3 — YAML organisation

**Context.** ~75 routes, ~900–1100 hand-maintained lines. A reviewer must check a route against
its controller in seconds.

**Decision.**

| Element | Choice |
|---|---|
| Version | OpenAPI `3.1.0` |
| Grouping | 16 tags mirroring the `CLAUDE.md` §API REST table, in its order; within a tag, path then method |
| `components` | `parameters` (the 18 catalog filters, shared by `/api/data` + `/api/facets`), `responses` (401, 403, 404, 409, 410, 429), `securitySchemes`. **No `schemas`** — response shapes are out of scope |
| Schemes | `bearerAuth` (`http`/`bearer`/JWT); `refreshCookie` (`apiKey`, cookie `refresh`) + `X-Refresh-CSRF` header, on the two `/api/auth/refresh` operations only |
| Default | Root-level `security: [{bearerAuth: []}]` — everything is gated, matching the no-catch-all posture |
| Unauthenticated | Operation-level `security: []`, exactly six occurrences; greppable against `thePermitListIsExactlyWhatWeExpect()`'s `hasSize(6)` |
| Authorisation | `x-access: PERMIT \| AUTHENTICATED \| ADMIN` on every operation, verbatim `ApiRoutePolicy.Access` |
| Header | `info.description` states the guard's limit: **path, method and access level only; never response-shape accuracy** |

**Consequences.** Indirection is limited to two `$ref` kinds a reviewer learns once; `x-access`
costs ~75 lines and buys the strongest assertion in the change. Stating the limit here is one
of the three required placements (proposal, test javadoc, YAML header).

## Data flow

```
@*Mapping annotations ──scan──→ LiveRoutes.todas() ──┐
                                                     ├─→ set difference (both ways, reported together)
docs/openapi.yaml ──SnakeYAML──→ documented ops ─────┘          │
                                                                ▼
                       ApiRoutePolicy.resolver / coincide ←── x-access agreement
```

## Guard design

**Reader.** `new Yaml(new SafeConstructor(new LoaderOptions()))` → `Map<String,Object>`, navigate
`paths` → path → method key. **Rejected:** a typed model (`swagger-parser`) — a real new
dependency with ~10 transitives, to validate a spec the guard does not need validated. Plain
`load` is ~15 lines. `SafeConstructor` is not optional: an unrestricted `new Yaml()` instantiates
arbitrary classes from `!!` tags, and seeding that pattern in a test that parses repo files is
not worth zero lines saved. SnakeYAML 2.2 is already transitively on the classpath (settled).

**Failure messages.** Every check collects into a `List<String>` asserted `isEmpty()`, so all
offenders print at once (`RouteCoverageTest.everyLiveMappingIsCovered`'s style). Exact forms:

- `live but undocumented: GET /api/mejores — add paths./api/mejores.get to docs/openapi.yaml`
- `documented but denied: POST /api/foo — resolves to no ApiRoutePolicy row, so it would 403`
- `access mismatch: DELETE /api/db/productos — openapi.yaml says AUTHENTICATED, ApiRoutePolicy resolves ADMIN`
- `GET /api/status is x-access AUTHENTICATED but carries security: [], documenting it as public`

A red build that does not name the route and the direction is a bad guard; each message does both.

**Duplicate path keys** overwrite silently in SnakeYAML (last wins). Direction 2 catches the
victim as undocumented — noted so the symptom is recognised.

## Negative control (designed against masking)

Recorded prior failure: *a negative control can lie by giving too FEW reds when two mechanisms
break at once.* Both sides here can go vacuous independently, and two empty sets compare equal.
Therefore:

| Test | Asserts | Guards against |
|---|---|---|
| `theControllerScanIsNotVacuous` | `LiveRoutes.todas()` size > 40 | scan returns nothing (also covers `RouteCoverageTest`, now sharing the helper) |
| `theYamlParseIsNotVacuous` | `paths` map > 40 keys **and** flattened operations > 40 | a 75-key map whose values yield zero method entries would pass a key count |
| Parity | reports **both** set differences in one failure, never short-circuits | two simultaneous breaks surfacing as one red |

Both non-vacuity assertions run independently of, and are stated before, the parity assertion.
Two **separate** mutation self-tests are run and their exact output recorded during apply, one
at a time so neither can mask the other: (a) add a bogus `/api/does-not-exist` → direction 1 red;
(b) delete one real YAML entry → direction 2 red.

## File changes

| File | Action | Notes |
|---|---|---|
| `docs/openapi.yaml` | Create | +900–1150 (incl. ~75 `x-access` lines) |
| `.../security/LiveRoutes.java` | Create | ~45 — `Ruta`, `todas()`, `concretar()` |
| `.../security/OpenApiRouteCoverageTest.java` | Create | ~190–220 |
| `.../security/RouteCoverageTest.java` | Modify | ~−33/+4, **zero assertions touched** |
| `docs/API_REFERENCE.md` | Modify | −450/+40, keeps all five rationale clusters |
| `CLAUDE.md` §API REST | Modify | −56/+8, pointer only |
| `docs/ARCHITECTURE.md` | Modify | +12, index paragraph only (the `docs/DATABASE.md` pattern) |
| `scraper/pom.xml` | — | **No change**; SnakeYAML 2.2 already transitive |

## Commit boundaries

| # | Commit | Contents | Green alone |
|---|---|---|---|
| A | `feat(docs): add the OpenAPI contract and its bidirectional drift guard` | YAML, `LiveRoutes`, new test, `RouteCoverageTest` extraction | Yes — purely additive; the markdown stays correct |
| B | `docs(api): retire the mechanical contract from the markdown` | `API_REFERENCE.md`, `CLAUDE.md`, `ARCHITECTURE.md` | Yes — removes only what A already covers |

Order is forced: B alone leaves the mechanical contract undocumented. Revert B before A.
The `LiveRoutes` extraction rides in A rather than a commit A0 — it has no consumer alone, and
"extract, then use" is the file-type split `work-unit-commits` warns against; `CODE-2` compliance
is verified by A's diff shape. Conventional subjects (`COMMIT-1`, `COMMIT-2`), no AI attribution
(`COMMIT-3`), tests and docs travelling with their code (`COMMIT-5`). Whole suite green with
`clean` on **each** commit (`TEST-1`). Budget: `size:exception` accepted for A.

## Transcription protocol (~450 mechanical lines, ~75 endpoints)

1. **Guard first, red.** Ship a stub `docs/openapi.yaml` (`openapi`, `info`, `components`,
   `paths: {}`) and run the test. Red output = the complete worklist, derived from the code.
2. **Transcribe tag group by tag group**, in `CLAUDE.md` table order. Re-run only
   `OpenApiRouteCoverageTest` after each group (tests on JRE 21 per the split toolchain).
3. **Two sources, two roles, stated once:** path + method + access come from the code and the
   guard enforces them. Parameters and status codes come from `docs/API_REFERENCE.md` and
   **nothing but review enforces them** — that is the limit, restated.
4. **Checkpoints:** (a) the red count decreases monotonically after every group, never rises;
   (b) reconcile top-level path count (`rg -c '^  /' docs/openapi.yaml`) against the scan's
   distinct-path count, which localises a YAML indentation slip to the right line instead of
   only surfacing it as a missing route; (c) at zero red, full suite with `clean`;
   (d) the two mutation self-tests above.

## Testing strategy

| Layer | What | How |
|---|---|---|
| Unit | Path+method+access parity, both directions | `OpenApiRouteCoverageTest`, reflection + SnakeYAML, no Spring context |
| Unit | Non-vacuity of each side | Two independent assertions (see table above) |
| Regression | Existing security suite | `RouteCoverageTest` unchanged in behaviour; `RoutePolicyShadowingTest` untouched |
| Manual | Guard actually fails | Two separate mutation runs, output recorded |

**Not tested, by construction:** response shapes, parameter accuracy, status-code accuracy.

## Threat matrix

**N/A** — no runtime routing, shell, subprocess, VCS/PR automation, executable-file
classification or process-integration boundary is added; the change touches no `src/main` file
and adds no `ApiRoutePolicy` row. The one adjacent hardening (SnakeYAML `SafeConstructor`
instead of unrestricted `new Yaml()`) is recorded under *Guard design* rather than manufactured
into matrix rows.

## Migration / rollout

No migration. No Flyway change, so nothing is byte-frozen and no rollback SQL is owed. No env
var, no policy row, no restart semantics; `RequiredEnvVarsGuard` unaffected.

## Open questions

- [ ] None blocking. Change 2's hosting topology (backend static resource vs. frontend Vite)
      stays open by design — ADR-1 shows it costs nothing to defer.
