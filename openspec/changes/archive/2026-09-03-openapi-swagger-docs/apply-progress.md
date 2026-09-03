# Apply Progress — `openapi-swagger-docs`

Phase: `sdd-apply` · Mode: Strict TDD · Status: both commits landed on
`feat/openapi-contract-and-drift-guard`, not pushed, no PR opened.

## Completed Tasks

All 32 tasks in `tasks.md` are marked `[x]` (Commit A: A1.1–A3.5, 25 tasks;
Commit B: B1.1–B4.2, 7 tasks).

## Commits

| # | SHA | Subject |
|---|---|---|
| A | `8c18a7d` | `feat(docs): add the OpenAPI contract and its bidirectional drift guard` |
| B | `e86c7e3` | `docs(api): retire the mechanical contract from the markdown` |

## Files Changed

| File | Action | Commit | What Was Done |
|------|--------|--------|---------------|
| `docs/openapi.yaml` | Created | A | 1150-line OpenAPI 3.1 contract, 63 documented paths / 76 operations across 16 tag groups, `x-access` mirroring `ApiRoutePolicy.Access` verbatim |
| `scraper/src/test/java/ar/scraper/security/LiveRoutes.java` | Created | A | Package-private route scanner, extracted verbatim from `RouteCoverageTest` |
| `scraper/src/test/java/ar/scraper/security/OpenApiRouteCoverageTest.java` | Created | A | Bidirectional guard, 5 `@Test` methods, no Spring context |
| `scraper/src/test/java/ar/scraper/security/RouteCoverageTest.java` | Modified | A | Assertion-free extraction: delete 2 helpers, add 2 delegating one-liners, add 1 import |
| `docs/API_REFERENCE.md` | Modified | B | 1094 → 703 lines; mechanical per-endpoint prose retired, all rationale (including two facts relocated from CLAUDE.md) preserved |
| `CLAUDE.md` | Modified | B | §API REST: 64 → ~13 lines, pointer-only |
| `docs/ARCHITECTURE.md` | Modified | B | +15-line index paragraph, mirroring the existing `DATABASE.md` pattern |
| `docs/FRONTEND_AUTH_CONTRACT.md` | Untouched | — | `git diff --stat` confirmed empty before and after commit B |

## TDD Cycle Evidence (Strict TDD)

| Task | RED | GREEN | REFACTOR |
|---|---|---|---|
| A1.1 `LiveRoutes` extraction | N/A (pure refactor, not new behavior) | `RouteCoverageTest` all 8 tests pass post-extraction | Diff-shape verified: delete-helpers / add-two-lines / add-import only, zero `@Test`/`@DisplayName`/assertion changed |
| A1.2–A1.4 Guard skeleton | `OpenApiRouteCoverageTest` run against stub `paths: {}` YAML: 2 of 5 tests fail (`theYamlParseIsNotVacuous` — 0 paths; `everyLiveRouteIsDocumented` — 74 unique live-but-undocumented routes reported) | N/A yet | N/A yet |
| A2.1–A2.16 Transcription | (RED baseline above is the worklist) | Full 76-operation transcription in one pass (used a scratch dump of `ApiRoutePolicy.resolver` per live route as an authoritative x-access oracle, deleted before commit); `OpenApiRouteCoverageTest` green in one run: 5/5 pass | Path-count reconciliation: `rg -c '^  /' docs/openapi.yaml` = 63 = 61 (scanner distinct paths) + 2 (`GET /`, `OPTIONS /**`, both invisible to the reflection scan by construction) |
| A3.2 Mutation self-test (a) | Injected bogus `/api/does-not-exist` GET. RED: `documented but denied: GET /api/does-not-exist — resolves to no ApiRoutePolicy row, so it would 403` | Reverted, confirmed 5/5 green again | N/A |
| A3.3 Mutation self-test (b) | Deleted `/api/mejores` entry. RED: `live but undocumented: GET /api/mejores — add paths./api/mejores.get to docs/openapi.yaml` | Reverted, confirmed 5/5 green again, path count still 63 | N/A |
| B1–B3 Doc retirement | N/A (docs-only) | Full suite green with `clean` both before commit (2013/0/0/7, matching A's close) and after (2013/0/0/7, unchanged — docs-only diff) | DOC-1 audit re-verified by grep: each of 7 facts removed from CLAUDE.md's blockquote has exactly one surviving copy (see below) |

## Work Unit Evidence

| Evidence | Commit A | Commit B |
|---|---|---|
| Focused test command and result | `mvn -f scraper/pom.xml test -Dtest=OpenApiRouteCoverageTest,RouteCoverageTest -Djvm=...java-21...` → `Tests run: 13, Failures: 0, Errors: 0` | Full suite (docs-only diff, `TEST-1` still requires it) |
| Runtime harness | N/A — test-only change, no `src/main` touched | N/A — docs-only |
| Full-suite close gate (`clean`) | `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7` — `BUILD SUCCESS`, zero `ERROR]`/`BUILD FAILURE` strings | `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7` — `BUILD SUCCESS`, unchanged from A's close (docs-only, no test count delta) |
| Rollback boundary | `git revert e86c7e3` (B) then `git revert 8c18a7d` (A); A alone reverts cleanly to a state with `docs/openapi.yaml`, `LiveRoutes.java`, `OpenApiRouteCoverageTest.java` gone and `RouteCoverageTest.java` restored to its pre-change body | `git revert e86c7e3` alone restores `API_REFERENCE.md`, `CLAUDE.md`, `ARCHITECTURE.md` verbatim; must precede reverting A |

Baseline before any change: `Tests run: 2008, Failures: 0, Errors: 0, Skipped: 7`.
2008 + 5 new `OpenApiRouteCoverageTest` tests = 2013, exactly matching both
post-commit full-suite runs.

## DOC-1 Audit (B2.2)

Every fact removed from `CLAUDE.md`'s API REST blockquote, and where its one
surviving copy now lives:

| Fact | Surviving copy |
|---|---|
| `denyAll()` / no-catch-all enforcement | `docs/API_REFERENCE.md` (POST /auth/login section, 🔒 note) |
| `authSession.js`/`authedFetch` single-point, role-aware UI | **Moved** to `docs/API_REFERENCE.md` § Postura de seguridad, en general (had no surviving home — `FRONTEND_AUTH_CONTRACT.md` never stated this) |
| Six-entry permit list | `docs/FRONTEND_AUTH_CONTRACT.md` (pre-existing "Las seis rutas abiertas" table) |
| "401 y 403 no son lo mismo" | `docs/FRONTEND_AUTH_CONTRACT.md` § 2 (pre-existing, matches `explore.md`'s cited lines 91-98) |
| Per-owner scoping (compiler-enforced, no unscoped variant) | **Moved** to `docs/API_REFERENCE.md` § Postura de seguridad, en general (had no surviving home) |
| Unscoped `DELETE /api/db/productos` favourites guard | `docs/API_REFERENCE.md` (DELETE /db/productos section — already preserved verbatim as one of the five named rationale clusters) |
| `UnownedRowsWarner` (`usuario_id IS NULL` invisibility) | **Moved** to `docs/API_REFERENCE.md` § Postura de seguridad, en general (had no surviving home) |

Verified by `rg -c` against all four docs for each fact's distinguishing
phrase: exactly one hit per fact, none at zero, none at two.

## Deviations from Design

1. **Refresh operations use `security: [{refreshCookie: []}]` rather than
   bare `security: []`.** `design.md` §ADR-3 says "operation-level
   `security: []`, exactly six occurrences; greppable against
   `thePermitListIsExactlyWhatWeExpect()`'s `hasSize(6)`." That test counts
   `ApiRoutePolicy.TABLE` **rows** (6), not concrete operations (7, since the
   refresh row covers both POST and DELETE). Documenting the two refresh
   operations as flatly public (`security: []`) would misrepresent them —
   they require the `refresh` cookie plus (usually) the `X-Refresh-CSRF`
   header, a real mechanism, not "no security at all." I used a distinct
   `refreshCookie` security scheme for those two operations instead, and
   `security: []` for the other five PERMIT operations (`GET /`,
   `OPTIONS /**`, login, password-reset request, password-reset confirm).
   This is not tested by any assertion in Commit A or in the pre-existing
   `RouteCoverageTest` (unchanged, still asserts `hasSize(6)` on the TABLE
   rows only) — it is a documentation-accuracy call, flagged here per the
   "note deviations, don't silently freelance" rule.
2. **`docs/openapi.yaml` came out at ~1150 lines** (design estimated
   900–1150) and **`docs/API_REFERENCE.md` landed at 703 lines** (task
   estimated ~644+40=684) — both within the design's stated ranges; the
   extra ~19 lines in API_REFERENCE.md are the three DOC-1-relocated
   paragraphs (§ Postura de seguridad, en general) that design.md's original
   estimate did not anticipate needing a new home.
3. **Found and left uncorrected**: `CLAUDE.md`'s pre-change API REST table
   claimed `GET /api/db/ml`; the live route is actually `DELETE /api/db/ml`
   (`ApiController.java:233`). `docs/openapi.yaml` documents the real,
   live `DELETE`. Fixing the stale claim was implicit in replacing the whole
   table with a pointer, so no separate action was needed, but noting it
   here since it's a real pre-existing doc/code mismatch this change
   incidentally corrects.

## Issues Found

None blocking. See Deviations above for the one documentation-accuracy
judgment call (refresh scheme) made where the design's stated count and
factual accuracy pulled in different directions.

## Workload / PR Boundary

- Mode: `single-pr` with `size:exception` (pre-accepted by the user for
  Commit A per the proposal Addendum, Q4).
- Both commits are on `feat/openapi-contract-and-drift-guard`, branched off
  `master`. **Not pushed. No PR opened** — per session instructions, pushing
  and PR creation were not authorized in this run.
- `git diff --stat master..feat/openapi-contract-and-drift-guard`: 7 files
  changed (4 in A, 3 in B — `docs/FRONTEND_AUTH_CONTRACT.md` untouched).

## Status

32/32 tasks complete. Ready for `sdd-verify`. Both commits are green with
`clean` individually (`TEST-1`); neither has been pushed or opened as a PR.

## Note on Engram persistence

This apply-phase agent's tool schema does not expose `mem_save`/`mem_update`
(confirmed — only `Read`, `Edit`, `Write`, `Bash` were available). This file
is the filesystem half of the `hybrid` artifact store contract. The Engram
half (`sdd/openapi-swagger-docs/apply-progress`) must be persisted by the
orchestrator, which does have Engram tool access.
