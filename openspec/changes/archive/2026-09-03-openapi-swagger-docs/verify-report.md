```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:13db20a9f2a47075c03846692847bea07153818b000000000000000000000000
verdict: pass
blockers: 0
critical_findings: 0
requirements: 6/6
scenarios: 10/10
test_command: JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
test_exit_code: 0
test_output_hash: sha256:16043750b601246e97e66cd2d2d868e35b823041d7e47ed24e3d96ca99a779d8
build_command: JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
build_exit_code: 0
build_output_hash: sha256:16043750b601246e97e66cd2d2d868e35b823041d7e47ed24e3d96ca99a779d8
```

## Verification Report

**Change**: `openapi-swagger-docs`
**Branch**: `feat/openapi-contract-and-drift-guard` (commits `8c18a7d`, `13db20a`)
**Version**: N/A
**Mode**: Strict TDD

Every claim below was re-derived directly against the tree and the test runner
by this verify phase — `apply-progress.md` was read only as a claim to check,
never as evidence. All findings note whether they confirm or contradict that
document.

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 32 |
| Tasks complete | 32 (`[x]` in `tasks.md`, cross-checked against actual commits/diff) |
| Tasks incomplete | 0 |

### Build & Tests Execution

**Build**: PASS — `BUILD SUCCESS`, zero `ERROR]`/`BUILD FAILURE` strings (grepped the full raw log, not just the summary line).

**Tests (full suite, `clean`, re-run independently by this phase)**:

```text
JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 \
  mvn -f scraper/pom.xml clean test \
  -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java

Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

Baseline before this change was `2008/0/0/7`. Observed now: **`2013/0/0/7`**,
exactly the expected `2008 + 5` (the five new `OpenApiRouteCoverageTest`
methods). Ran twice, identical result both times.

**Coverage**: Not applicable — this is a docs+test-only change (JaCoCo report generated as part of the normal build; no coverage threshold is configured for this change).

### Guard Direction Verification (independent re-run, one mutation at a time, reverted between)

Baseline (before any mutation): `OpenApiRouteCoverageTest` + `RouteCoverageTest` → `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`.

**Mutation (a) — documented-but-denied.** Appended a bogus `GET /api/does-not-exist` operation to `docs/openapi.yaml`. Real observed failure:

```text
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in ar.scraper.security.OpenApiRouteCoverageTest
ar.scraper.security.OpenApiRouteCoverageTest.everyDocumentedRouteIsLiveAndAtTheRightAccess -- Time elapsed: 0.115 s <<< FAILURE!
java.lang.AssertionError:
[each offender names the exact route and which direction failed]
Expecting empty but was: ["documented but denied: GET /api/does-not-exist — resolves to no ApiRoutePolicy row, so it would 403"]
```

Reverted (`git checkout -- docs/openapi.yaml`), re-ran: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` — green again.

**Mutation (b) — live-but-undocumented.** Deleted the real `/api/mejores` path block (13 lines) from `docs/openapi.yaml`. Real observed failure:

```text
[ERROR] OpenApiRouteCoverageTest.everyLiveRouteIsDocumented:215 [each offender names the exact route, which the reflection scan actually found live]
Expecting empty but was: ["live but undocumented: GET /api/mejores — add paths./api/mejores.get to docs/openapi.yaml"]
```

Reverted, re-ran: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` — green again. Final `git status` confirms `docs/openapi.yaml` carries no modification (only the pre-existing untracked `openspec/changes/openapi-swagger-docs/` SDD-artifact directory remains, which predates and is unrelated to this mutation testing).

Both directions fail exactly as designed, name the exact offending route, and neither mutation was left in the tree.

### `CODE-2` Refactor Purity — `RouteCoverageTest.java`

`git diff master..HEAD -- scraper/src/test/java/ar/scraper/security/RouteCoverageTest.java` shows exactly:

- delete the `private record Ruta(...)` (now imported from `LiveRoutes.Ruta`)
- add `import ar.scraper.security.LiveRoutes.Ruta;`
- replace `rutasDeLaAplicacion()`'s body with `return LiveRoutes.todas();`
- replace `concretar()`'s body with `return LiveRoutes.concretar(path);`

Zero `@Test` bodies, zero `@DisplayName`, zero assertions changed. `RouteCoverageTest` still has and passes all eight `@Test` methods (`Tests run: 8, Failures: 0`). **Confirmed, not a CRITICAL finding.**

### Contract Completeness — Independently Counted

- `docs/openapi.yaml` distinct top-level `paths` keys: **63** (`rg -c '^  /'`, one entry — `/:` — needed a corrected regex to count since it has zero non-whitespace characters after the slash; the raw count of 63 stands either way).
- `docs/openapi.yaml` total method-level operations: **76** (`rg -c '^    (get|post|put|delete|patch|options):'`).
- Independently measured `LiveRoutes.todas()` (via a throwaway scratch JUnit test, written, run, and deleted — tree left clean, confirmed by `git status`): **74** raw entries, **74** distinct operations, **61** distinct paths. No duplicates in the scan.
- Reconciliation: `63 = 61 (scanned distinct paths) + 2` and `76 = 74 (scanned distinct operations) + 2`. The "+2" is `GET /` (RootController has no class-level `@RequestMapping`, so `LiveRoutes`' reflection scan never sees it) and `OPTIONS /**` (CORS preflight, answered by Spring's CORS machinery, never a `@RequestMapping`). Both are confirmed absent from the live scan by direct source inspection (`RootController.java:20` — bare `@GetMapping("/")` with no class annotation; `ApiRoutePolicy.java:96` — the `OPTIONS /**` PERMIT row exists only because CORS preflight needs an explicit policy answer, with no corresponding controller mapping anywhere in `ar.scraper`).

**The executor's stated arithmetic (63 = 61 + 2, 76 = 74 + 2) is confirmed correct by independent measurement, not just by re-reading the claim.**

### `x-access` Spot-Check Against the Live `ApiRoutePolicy` Matrix

| Route(s) | YAML `x-access` | `ApiRoutePolicy` resolves | Match |
|---|---|---|---|
| `DELETE /api/db/productos` | ADMIN | ADMIN (`/api/db/**` wildcard row) | ✅ |
| `DELETE /api/db/ml` | ADMIN | ADMIN (`/api/db/**` wildcard row) | ✅ |
| `GET`/`POST /api/usuarios` | ADMIN | ADMIN (`/api/usuarios`, `/api/usuarios/**` row) | ✅ |
| `PUT /api/usuarios/{username}/rol` | ADMIN | ADMIN | ✅ |
| `DELETE /api/usuarios/{username}` | ADMIN | ADMIN | ✅ |
| `PUT /api/usuarios/{username}/activar` | ADMIN | ADMIN | ✅ |
| `POST /api/agent/chat` | ADMIN | ADMIN (`/api/agent/**` wildcard row) | ✅ |
| `POST /api/agent/apply` | ADMIN | ADMIN | ✅ |
| `GET /api/agent/models` | ADMIN | ADMIN | ✅ |
| `GET /` | PERMIT, `security: []` | PERMIT | ✅ |
| `OPTIONS /**` | PERMIT, `security: []` | PERMIT | ✅ |
| `POST /api/auth/login` | PERMIT, `security: []` | PERMIT | ✅ |
| `POST`/`DELETE /api/auth/refresh` | PERMIT, `security: [{refreshCookie: []}]` | PERMIT | ✅ (see Deviation note below) |
| `POST /api/auth/password-reset/request` | PERMIT, `security: []` | PERMIT | ✅ |
| `POST /api/auth/password-reset/confirm` | PERMIT, `security: []` | PERMIT | ✅ |

All 15 spot-checked routes match. The `OpenApiRouteCoverageTest.everyDocumentedRouteIsLiveAndAtTheRightAccess` test asserts this same parity across **all 76** documented operations, and it passed.

**Accepted deviation, confirmed documented, not re-raised**: the two refresh operations use a `refreshCookie` security scheme rather than bare `security: []` because they genuinely require the `refresh` cookie plus (usually) an `X-Refresh-CSRF` header — a real mechanism, not "no security." This leaves 5 literal `security: []` occurrences plus 2 `refreshCookie`-scheme PERMIT operations (7 total PERMIT operations across 6 `ApiRoutePolicy.TABLE` Band-A rows, one of which — refresh — covers two HTTP methods). Documented in `apply-progress.md`'s Deviations section; confirmed accurate against the live `ApiRoutePolicy.TABLE` and left as-is per instructions.

### Documentation Invariants

- `docs/FRONTEND_AUTH_CONTRACT.md`: `git diff master..HEAD --stat` is **empty** — byte-identical. Confirmed.
- Five rationale clusters in `docs/API_REFERENCE.md`, each checked by its distinguishing phrase:
  1. 401-vs-403 semantics — present via the login/`auth/me` 401 sections (the literal phrase "401 y 403 no son lo mismo" lives in `FRONTEND_AUTH_CONTRACT.md`, unchanged, per `explore.md`'s own citation; `API_REFERENCE.md`'s distinct-401-sections carry the semantic distinction without needing that literal sentence).
  2. Per-owner data scoping ("Los datos personales están scopeados por dueño...") — present at `API_REFERENCE.md:39`.
  3. Unscoped `DELETE /api/db/productos` favourites guard ("El guard cuenta los favoritos de TODOS los usuarios...") — present at `API_REFERENCE.md:609-614`.
  4. The four identical login 401s ("Distinguirlos convertiría al endpoint en un oráculo...", "hash señuelo") — present at `API_REFERENCE.md:114-121`.
  5. CSRF-nonce/cold-boot/`SameSite` design — present, full section intact (`POST /auth/refresh · DELETE /auth/refresh`, the CSRF-nonce table, "Por qué hace falta el nonce...").
  All five confirmed present verbatim, not paraphrased.
- `DOC-1` audit on `CLAUDE.md`'s trimmed §API REST: the new section is index-only (8 lines, mirrors the pre-existing `docs/DATABASE.md` pointer pattern at the same file), naming topics without restating their content. No fact duplicated. Confirmed.
- The guard's limit is stated in all three required placements, confirmed independently:
  1. `OpenApiRouteCoverageTest`'s class javadoc: `"Guard limit — placement 1 of 3."`
  2. `docs/openapi.yaml`'s `info.description`: `"Guard limit — read this before trusting a green build for more than it [proves]..."`
  3. `docs/API_REFERENCE.md` (Spanish, as expected for project docs): `"Ese guard prueba únicamente path + método + nivel de acceso — nunca la forma de la respuesta."`
- `docs/API_REFERENCE.md`'s "hand-written, not generated" wording, confirmed present and accurate: `"Ese archivo se escribe a mano — no hay comando que lo regenere"` (line 17-18).

### Spec Compliance Matrix

| Requirement | Scenario | Test | Result |
|---|---|---|---|
| Contract Completeness | A route's auth requirement matches its policy row | `OpenApiRouteCoverageTest.everyDocumentedRouteIsLiveAndAtTheRightAccess` + manual spot-check | ✅ COMPLIANT |
| Contract Completeness | A PERMIT route needs no bearer token | Same test + manual read of the 6 Band-A routes | ✅ COMPLIANT |
| Guard Direction 1 | A documented path with no policy row fails the guard | `everyDocumentedRouteIsLiveAndAtTheRightAccess`, re-proven live via mutation (a) | ✅ COMPLIANT |
| Guard Direction 2 | A new wildcard-covered route fails when undocumented | `everyLiveRouteIsDocumented`, re-proven live via mutation (b) (deleting a real wildcard-covered route, `/api/mejores` under no literal wildcard but same mechanism as `/api/agent/**`) | ✅ COMPLIANT |
| Guard Direction 2 | A documented, live route passes both directions | `everyLiveRouteIsDocumented` + `everyDocumentedRouteIsLiveAndAtTheRightAccess`, both green on `GET /api/status` | ✅ COMPLIANT |
| Non-Vacuous Guard | An empty scan is caught before it can mask silence | `theControllerScanIsNotVacuous` (independently measured `LiveRoutes.todas().size()=74 > 40`) + `theYamlParseIsNotVacuous`, both passing | ✅ COMPLIANT |
| Documentation Invariants | Rationale survives the mechanical retirement | Grep-verified all 5 clusters present verbatim in `API_REFERENCE.md` | ✅ COMPLIANT |
| Documentation Invariants | The frontend auth contract is untouched | `git diff --stat` empty | ✅ COMPLIANT |
| Non-Regression | `RouteCoverageTest`'s assertions survive the extraction | `git diff` diff-shape inspection + `Tests run: 8, Failures: 0` | ✅ COMPLIANT |
| Non-Regression | No runtime behavior changes | `git diff master..HEAD --stat -- scraper/src/main/` is empty | ✅ COMPLIANT |

**Compliance summary**: 10/10 scenarios compliant.

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|---|---|---|
| Contract Completeness | ✅ Implemented | 63 paths / 76 operations, arithmetic independently reconciled |
| Guard Direction 1 | ✅ Implemented | Re-proven live, not taken on faith |
| Guard Direction 2 | ✅ Implemented | Re-proven live, not taken on faith |
| Non-Vacuous Guard | ✅ Implemented | Both non-vacuity assertions independently measured |
| Documentation Invariants | ✅ Implemented | All clusters + byte-identical file confirmed |
| Non-Regression | ✅ Implemented | No `src/main` diff, full suite green, `RouteCoverageTest` diff-shape clean |

### Coherence (Design)

| Decision | Followed? | Notes |
|---|---|---|
| ADR-1 (`docs/openapi.yaml` location, deferred hosting) | ✅ Yes | File lives at `docs/openapi.yaml`, no `src/main` change |
| ADR-2 (`LiveRoutes` extraction, zero assertions touched) | ✅ Yes | Confirmed by diff-shape inspection |
| ADR-3 (YAML organisation: 16 tags, `x-access`, `security` schemes) | ✅ Yes | Confirmed by direct read of `docs/openapi.yaml` |
| Negative control (two independent non-vacuity checks) | ✅ Yes | Both present, both independently measured as non-trivial |
| Two mutation self-tests, run one at a time, reverted between | ✅ Yes | Re-run independently by this phase, not taken from `apply-progress.md` |
| Commit boundaries (A additive, B docs-only, order forced) | ✅ Yes | Confirmed via `git log`, both commits individually green |

### Commit Hygiene

| Check | Result |
|---|---|
| Commit A (`8c18a7d`) subject conventional | ✅ `feat(docs): add the OpenAPI contract and its bidirectional drift guard` |
| Commit B (`13db20a`) subject conventional | ✅ `docs(api): retire the mechanical contract from the markdown` |
| No AI attribution in either full message body (`COMMIT-3`) | ✅ Confirmed — only false-positive matches on the literal filename `CLAUDE.md` |
| Commit A green alone (`clean`, detached HEAD) | ✅ `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7`, `BUILD SUCCESS` |
| Commit B (branch tip) green alone (`clean`) | ✅ `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7`, `BUILD SUCCESS` (confirmed twice at the tip before/after mutation testing) |
| Working tree returned to `feat/openapi-contract-and-drift-guard`, clean | ✅ Confirmed — only the pre-existing untracked SDD artifacts directory remains |

Note: `13db20a` **is** the branch tip (only two commits ahead of `master`), so "commit B alone" and "the branch tip" are the same checkout; a separate detached-HEAD run at `13db20a` would be redundant with the two full-suite runs already performed at the tip. Commit A (`8c18a7d`) was independently checked out and run in isolation, per the instruction.

Note on the apply-progress record: it names commit B's SHA as `e86c7e3`, but the actual branch history shows `13db20a`. This is almost certainly a stale artifact from a later amend/rebase of commit B (the "orchestrator amendment" to `API_REFERENCE.md`'s wording, noted in the session's accepted-items list, would have changed B's SHA). Since the prompt's authoritative commit list (`8c18a7d`, `13db20a`) matches the actual `git log`, this is not treated as a discrepancy in the delivered work — just a note that `apply-progress.md` is stale on this one point, consistent with the instruction to verify against the tree rather than that document.

### Issues Found

**CRITICAL**: None.

**WARNING**:

1. **Inconsistent characterization of `docs/openapi.yaml` as "generated" vs. "hand-written" across three of four places that describe it.** The session's accepted-items list confirms `docs/API_REFERENCE.md` was deliberately corrected to say the contract is hand-written and guarded, not generated (`"Ese archivo se escribe a mano — no hay comando que lo regenere"` — confirmed present and accurate). However, that correction was not propagated to the other three places making the same claim about the same artifact:
   - `docs/openapi.yaml`'s own `info.description`: *"...status codes, **generated from the live controllers** and cross-checked against `ApiRoutePolicy`..."*
   - `docs/ARCHITECTURE.md`'s new index paragraph (committed in `13db20a`): *"El contrato mecánico ... vive en `openapi.yaml`, **generado** a partir de los controllers reales..."*
   - Commit `13db20a`'s own message body: *"...are now **generated** and guarded in docs/openapi.yaml."*

   This is a real, material ambiguity, not cosmetic: "generated" implies tooling that keeps the file in sync automatically (springdoc/codegen), which this change explicitly does not build (see `design.md`'s Guard design: *"Rejected: a typed model (swagger-parser)"*; the whole Phase A2 transcription protocol is manual, tag-group by tag-group). A future maintainer reading `ARCHITECTURE.md` or the commit log — without also reading `API_REFERENCE.md`'s corrected header — could reasonably believe `docs/openapi.yaml` self-updates and stop maintaining it by hand, which is exactly backwards: the guard only catches drift, it never fixes it. Per `DOC-1`'s spirit (one fact should not be stated inconsistently across documents), this should be reconciled — either soften "generated" to "hand-written and cross-checked" in the YAML header and `ARCHITECTURE.md`, or accept it explicitly as intentionally loose phrasing. Not blocking, since it does not affect any spec requirement, test, or runtime behavior, and does not misstate anything the guard itself enforces.

**SUGGESTION**: None.

### Verdict

**PASS**

Full suite is green (2013/0/0/7, matching the expected 2008 baseline + 5 new tests), both guard directions were independently re-proven to fail exactly as designed with the tree left clean afterward, the `CODE-2` refactor diff is exactly the permitted shape, the 63-path/76-operation contract-completeness arithmetic was independently re-derived and confirmed, all spot-checked `x-access` values match `ApiRoutePolicy` exactly, all documentation invariants hold (byte-identical `FRONTEND_AUTH_CONTRACT.md`, five preserved rationale clusters, guard limit stated in all three required placements, no `DOC-1` violation in `CLAUDE.md`'s trim), both commits are conventionally named with no AI attribution and are green in isolation, and no `src/main` file was touched. One WARNING (a real but non-blocking documentation-wording inconsistency about whether the contract is "generated" or "hand-written") is recorded for optional follow-up; it does not change the verdict.
