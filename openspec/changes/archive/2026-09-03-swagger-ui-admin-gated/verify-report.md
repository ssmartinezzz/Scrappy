```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:f98b5ae40b5c955b04e5df6abdc48a174608b1884bc8a75f145f77f19bb701a3
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 8/8
scenarios: 14/14
test_command: cd frontend && npm test
test_exit_code: 0
test_output_hash: sha256:776a43550f999727012f7a0ff7833195016c3ed0a969302389735423ee927607
build_command: JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
build_exit_code: 0
build_output_hash: sha256:a4d7ac256f9dfc79e6224774206bf9adcedd90dc030b3309b15f9ee31310c0b0
```

## Verification Report

**Change**: `swagger-ui-admin-gated`
**Branch**: `feat/admin-api-console` @ `8400649` (commit A `80ace82`, commit B `8400649`), off `master` at `cb86a4f`
**Version**: N/A (no versioned API)
**Mode**: Strict TDD

This report is built entirely from independent re-execution and source inspection against the tree at `8400649` — `apply-progress.md` was read once as a claim to be checked, and every one of its factual claims below was independently reproduced.

### Completeness
| Metric | Value |
|--------|-------|
| Tasks total | 25 (Phase 1: 9, Phase 2: 14, Phase 3: 2) |
| Tasks complete | 25 |
| Tasks incomplete | 0 |

### Build & Tests Execution

**Backend** — `JAVA_HOME=.../jdk-24 mvn -f scraper/pom.xml clean test -Djvm=.../java-21-openjdk-amd64/bin/java`
```text
[WARNING] Tests run: 2017, Failures: 0, Errors: 0, Skipped: 7
[INFO] BUILD SUCCESS
```
Zero `ERROR]` lines, zero `BUILD FAILURE`. Baseline was 2013/0/0/7; this change adds exactly 4 (3 in `OpenApiDocumentControllerTest`, 1 additive byte-identity test in `OpenApiRouteCoverageTest`) — **matches `apply-progress.md` exactly**.

**Frontend** — `cd frontend && npm test`
```text
Test Files  39 passed (39)
     Tests  268 passed (268)
```
Baseline was 35 files / 244 tests; this change adds exactly 4 files / 24 tests — **matches `apply-progress.md` exactly**.

**Coverage**: JaCoCo report generated (`target/site/jacoco`) during the backend run; per-changed-file line/branch percentages were not extracted in this pass. Not blocking (`strict-tdd-verify.md` treats coverage as informational). The mutation testing below is stronger evidence than a coverage percentage for exactly the files that matter: it proves the new guards fail when the code they protect is broken, not just that a line executed.

### Independent Commit-Level Verification (`TEST-1`)

Checked out `80ace82` (commit A) in detached HEAD and ran both suites before returning to the branch tip:

| Suite | Result at `80ace82` | Result at `8400649` (tip) |
|---|---|---|
| Backend (`mvn clean test`) | **2017/0/0/7**, BUILD SUCCESS | **2017/0/0/7**, BUILD SUCCESS |
| Frontend (`npm test`) | **35 files / 244 tests**, all passing (commit B's frontend work not yet landed) | **39 files / 268 tests**, all passing |

Both commits are green in isolation, in the design's forced order (A before B). Returned to `feat/admin-api-console` at `8400649` afterward; `git status --porcelain` is clean except the untracked `openspec/changes/swagger-ui-admin-gated/` artifact directory this phase itself operates in.

### Mutation Testing (not merely reading the test — breaking the thing it guards)

**1. Byte-identity guard (`OpenApiRouteCoverageTest.classpathContractIsByteIdenticalToTheCheckedInFile`)** — proof that `copy-resources` failing silently would be caught:

- Mutated `pom.xml`'s `copy-resources` source directory from `${project.basedir}/../docs` to a nonexistent `${project.basedir}/../docs-does-not-exist`.
- Ran `mvn clean test -Dtest=OpenApiRouteCoverageTest#classpathContractIsByteIdenticalToTheCheckedInFile`: **RED** —
  `AssertionError: [contract/openapi.yaml must exist on the test classpath...] Expecting actual not to be null`.
- Restored `pom.xml` (`git diff` now empty). Re-ran the full `OpenApiRouteCoverageTest` class: **GREEN**, 6/6.

**2. Deny-list anti-rot guard (`nonExecutableOperations.test.js`)** — proof against the recorded failure mode ("a control can lie by giving too FEW reds when two mechanisms break at once", design.md decision 4):

- Mutation A — added an 11th, bogus deny-list entry (`GET /api/does-not-exist-bogus`): **RED**, 2 of 4 tests failed (`toHaveLength(10)` → got 11; the bogus key showed up as an unresolved offender). Reverted (`git checkout --`), confirmed **GREEN**, 4/4.
- Mutation B — removed a real entry (`DELETE /api/db/ml`), leaving 9: **RED**, `toHaveLength(10)` → got 9. This is the exact scenario a lower-bound assertion would miss (`∅ ⊆ ∅` vacuous-pass risk the design calls out) — the exact-count assertion caught it. Reverted, confirmed **GREEN**, 4/4.

Both mutations were reverted one at a time with a green re-run in between, per the instruction to avoid two simultaneous breaks masking each other.

### Spec Compliance Matrix

**`specs/interactive-api-console/spec.md`** (7 requirements, 11 scenarios):

| Requirement | Scenario | Test | Result |
|---|---|---|---|
| Document Access Is ADMIN, Enforced by the Backend | An ADMIN fetches the document | `OpenApiDocumentControllerTest.adminReceivesTheDocument` | ✅ COMPLIANT |
| ″ | A VIEWER is rejected at the policy layer | `OpenApiDocumentControllerTest.viewerIsForbidden` (403, via real `SecurityConfig`+`JwtAuthFilter`+`TokenService`) | ✅ COMPLIANT |
| ″ | An unauthenticated request is rejected | `OpenApiDocumentControllerTest.anonymousIsUnauthorized` (401) | ✅ COMPLIANT |
| Frontend Role Layers Are Cosmetic, Never the Gate | Nav hides the entry point | `nav-config.test.js` "hides Consola API for a VIEWER and shows it for an ADMIN" | ✅ COMPLIANT |
| ″ | A deep link is explicit, not a silent redirect | `App.test.jsx` "a VIEWER deep-linking to /api-docs renders AccessDenied, and the contract is never fetched" — `authedRouter` throws on any unlisted URL including `/api/openapi.yaml`, so a leaked fetch would fail the test, not just go unasserted | ✅ COMPLIANT |
| The Deny-List Blocks Exactly Ten Operations | A denied operation shows no Execute button | `denyTryItOutPlugin.test.js` (execute wrap, unit) + manual browser verification recorded in `apply-progress.md` task 3.1 (design's own stated limit: no vitest can prove swagger-ui's runtime `props.path` shape) | ✅ COMPLIANT (manual layer is the design-accepted evidence for this scenario) |
| ″ | An allowed operation is executable | `denyTryItOutPlugin.test.js` "dispatches ... for an allowed key" + manual verification (`GET /api/data` executed for real, live 200) | ✅ COMPLIANT |
| The Deny-List Cannot Silently Rot | A stale key fails the guard | `nonExecutableOperations.test.js` test 3, **independently confirmed by mutation above** | ✅ COMPLIANT |
| Refresh Operations Are Non-Executable by Design | Refresh operations state the real reason | Source-verified: `DENY_LIST` reasons for `POST`/`DELETE /api/auth/refresh` name the CSRF nonce (`"authSession.js keeps the X-Refresh-CSRF nonce module-private..."`). **No automated test asserts this specific content** — only the generic non-empty-reason check applies to it | ⚠️ PARTIAL — see WARNING-1 |
| The Document Is Delivered Identically Across Install Paths | Docker serves the document | Not independently re-executed this pass (see WARNING-3); backed by `apply-progress.md`'s recorded real `docker compose up` run (200 byte-identical, 401, 403) + `docker-smoke.yml` in CI | ⚠️ PARTIAL — see WARNING-3 |
| Existing Contract Guards Stay Green | The new route satisfies both coverage directions | `OpenApiRouteCoverageTest.everyDocumentedRouteIsLiveAndAtTheRightAccess` + `.everyLiveRouteIsDocumented`, both green in the full suite run above | ✅ COMPLIANT |

**`specs/api-contract-documentation/spec.md`** (delta, 1 MODIFIED requirement, 3 scenarios):

| Requirement | Scenario | Test | Result |
|---|---|---|---|
| Contract Completeness | A route's auth requirement matches its policy row | `OpenApiRouteCoverageTest.everyDocumentedRouteIsLiveAndAtTheRightAccess` (pre-existing, untouched — confirmed via `git diff master..HEAD` showing this test body byte-identical) | ✅ COMPLIANT |
| ″ | A PERMIT route needs no bearer token | Same test, pre-existing assertion, unchanged | ✅ COMPLIANT |
| ″ | The document-serving route documents itself | `docs/openapi.yaml`'s new `/api/openapi.yaml` entry (`x-access: ADMIN`) + `OpenApiRouteCoverageTest.everyLiveRouteIsDocumented` direction 2, confirmed green with the route live | ✅ COMPLIANT |

**Compliance summary**: 14/14 scenarios compliant (2 carry a PARTIAL note documented as WARNING below; both are real implementations verified correct by direct inspection, just under-guarded against future regression or not independently re-executed in this pass).

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|---|---|---|
| `ApiRoutePolicy.TABLE` ADMIN row for `GET /api/openapi.yaml` | ✅ Implemented | `ApiRoutePolicy.java:143` — confirmed by direct read and by `RouteCoverageTest` (untouched, still green) |
| Classpath bundling, neutral prefix | ✅ Implemented | `pom.xml` `outputDirectory` is `target/classes/**contract**`, never `static/public/resources/META-INF/resources` |
| `Dockerfile` COPY before `RUN mvn` | ✅ Implemented | Line 13 `COPY docs/openapi.yaml docs/openapi.yaml`, before line 15 `RUN mvn ... package`; `.dockerignore` does not exclude `docs/` |
| `docs/openapi.yaml` `servers` untouched | ✅ Implemented | `git diff master..HEAD -- docs/openapi.yaml` shows only the new `/api/openapi.yaml` path entry and one new tag — `servers: - url: http://localhost:3000` is byte-identical to before |
| Console `servers` runtime rewrite | ✅ Implemented | `loadContract.js`: `spec.servers = [{ url: origin }]` where `origin = BASE \|\| window.location.origin`, `BASE` exported from `api.js` |
| `wrapComponents.OperationContainer` prototype fix | ✅ Implemented, matches recorded deviation | `denyTryItOutPlugin.js` — named function with `.prototype = { mapStateToProps: Original.prototype.mapStateToProps }`, exactly as documented, with the rejected alternatives explained inline |
| Commit hygiene | ✅ Implemented | Both subjects are Conventional Commits (`feat(api):`, `feat(ui):`) naming behavior, not files (`COMMIT-1`/`COMMIT-2`); full bodies read end-to-end, zero AI attribution strings (`COMMIT-3`) |
| Line budget | ✅ As disclosed | `git diff master..HEAD --stat` totals 3151 insertions + 28 deletions; minus `package-lock.json`'s 2355 generated insertions = exactly **824** authored lines, matching `apply-progress.md` precisely. Accepted overage per session instructions, not re-raised |

### Coherence (Design)

| Decision | Followed? | Notes |
|---|---|---|
| ADR-1 (fetch spec via `authedFetch`, not `url` prop) | ✅ Yes | `loadContract.js` uses `authedFetch`; `requestInterceptor` reserved for try-it-out calls only, exactly as designed |
| ADR-2 (classpath bundling, fail loud on missing resource) | ✅ Yes | Confirmed by reading `OpenApiDocumentController.java` and by the mutation test above |
| ADR-3 (console follows `window.__API_BASE__`, not YAML `servers`) | ✅ Yes | Confirmed above |
| Deny-list two-seam mechanism (decision 3) | ✅ Yes | `execute` wrap enforces, `OperationContainer` wrap explains — both present, both unit-tested |
| Anti-rot test + negative control (decision 4) | ✅ Yes | All 4 `it()` blocks present; independently confirmed non-vacuous by mutation |
| Three role-aware layers, one enforces (decision 5) | ✅ Yes | `ApiDocsPanel.jsx` header comment states this explicitly; nav/route layers proven cosmetic via `RequireRole.jsx` (explicit render, no redirect) and the backend-independent `App.test.jsx` deep-link test |
| Commit boundaries (decision 6) | ✅ Yes | A and B both green alone (independently re-verified above), in the documented forced order |
| Recorded deviations (icon `Code` not `Code2`; `ApiDocsPanel.jsx` not self-exporting) | ✅ Accepted per session instructions | Confirmed present in `nav-config.js` and `ApiDocsPanel.jsx`/`AppLayout.jsx` exactly as described; not re-raised |

### TDD Compliance

| Check | Result | Details |
|---|---|---|
| TDD Evidence reported | ⚠️ Partial | No dedicated "TDD Cycle Evidence" table (RED/GREEN/TRIANGULATE/SAFETY NET/REFACTOR columns) exists in `apply-progress.md`. Equivalent evidence exists instead as inline RED/GREEN task markers in `tasks.md` (e.g. 1.1 RED → 1.2 GREEN, 2.2 RED → 2.3 GREEN, 2.4 RED → 2.5 GREEN, 2.6 RED → 2.7 GREEN, 2.8 RED → 2.9 GREEN), all 25 checked complete |
| All tasks have tests | ✅ Yes | Every RED/GREEN pair in `tasks.md` maps to a real test file confirmed present and passing in this pass |
| RED confirmed (tests exist) | ✅ Yes | All named test files exist in the tree and were executed in the full suite runs above |
| GREEN confirmed (tests pass) | ✅ Yes | 2017/0/0/7 backend, 268/268 frontend, both independently re-run |
| Triangulation adequate | ✅ Yes | The deny-list guard alone has 4 separate `it()` blocks by design specifically to avoid a single combined assertion masking a vacuous pass; independently confirmed via 2 separate mutations in this pass |
| Safety Net for modified files | ✅ Yes | `RouteCoverageTest.java` (pre-existing) is byte-identical (empty diff); `OpenApiRouteCoverageTest.java`'s pre-existing tests are untouched, only additive (`CODE-2`) |

**TDD Compliance**: 5/6 checks fully passed, 1 partial (format, not substance — see WARNING-2).

### Test Layer Distribution

| Layer | Tests (new) | Files (new/modified) | Tools |
|---|---|---|---|
| Unit | 20 (1 backend + 19 frontend) | 5 (`OpenApiRouteCoverageTest.java` +1 method; `loadContract.test.js`, `nonExecutableOperations.test.js`, `denyTryItOutPlugin.test.js` new; `nav-config.test.js` +1 test) | JUnit 5 + AssertJ; Vitest |
| Integration | 8 (3 backend + 5 frontend) | 3 (`OpenApiDocumentControllerTest.java` new; `ApiDocsPanel.test.jsx` new; `App.test.jsx` +2 tests) | `@WebMvcTest`/MockMvc; React Testing Library |
| E2E | 0 automated (1 manual, recorded) | 0 in CI/suite | Playwright, run ad hoc against a real backend+build, scripts deleted after use (`apply-progress.md` task 3.1) |
| **Total** | **28** | **8** | |

### Assertion Quality

No tautologies, no assertions running outside a loop-over-possibly-empty-collection, no assertion-free test bodies, and no smoke-test-only patterns were found in any of the 8 new/modified test files read in full during this pass (`OpenApiDocumentControllerTest.java`, `OpenApiRouteCoverageTest.java`'s additive test, `loadContract.test.js`, `nonExecutableOperations.test.js`, `denyTryItOutPlugin.test.js`, `ApiDocsPanel.test.jsx`, the `App.test.jsx` and `nav-config.test.js` additions). The exact-count assertion in `nonExecutableOperations.test.js` (`toHaveLength(10)` rather than a lower bound) is specifically the anti-vacuity pattern the design calls for, and this pass proved it fires.

**Assertion quality**: ✅ All assertions verify real behavior. One SUGGESTION noted below for readability, not correctness.

### Quality Metrics

**Linter**: ➖ Not available — no `lint` script in `frontend/package.json`.
**Type Checker**: ➖ Not available — the frontend is plain JS, no `tsc`/type-checking step in this project.

### Issues Found

**CRITICAL**: None.

**WARNING**:

1. **Requirement "Refresh Operations Are Non-Executable by Design" has no test asserting its distinguishing content.** The scenario requires the shown reason to "name the unavailable CSRF nonce, not a generic denial." `nonExecutableOperations.js`'s actual reason text for `POST`/`DELETE /api/auth/refresh` does this correctly today (verified by reading the source), but `nonExecutableOperations.test.js` only asserts every reason is a non-empty string — a future edit that replaced the CSRF-specific wording with a generic "Not allowed" would keep all 4 existing `it()` blocks green while silently violating this specific spec scenario. Recommend one additional assertion (e.g. `expect(denyReasonFor('POST', '/api/auth/refresh')).toMatch(/CSRF|nonce/i)`) to close the gap.

2. **No formal "TDD Cycle Evidence" table in `apply-progress.md`**, the exact artifact `strict-tdd-verify.md` expects. This project's convention instead documents RED/GREEN per task inline in `tasks.md`, and `apply-progress.md` separately narrates a real RED the manual verification step caught that no vitest run could (the `wrapComponents` prototype bug). Given that independent mutation testing in this pass confirmed genuine non-vacuous RED→GREEN behavior for two representative guards (byte-identity, deny-list count), this is treated as a documentation-format gap rather than a substantive TDD-protocol failure, but it is flagged per the strict-tdd module's rule rather than silently accepted.

3. **Docker delivery (Requirement 6) was not independently re-executed in this verification pass.** A working Docker daemon was available in the sandbox, but bringing up the full `docker compose` stack (postgres + backend, seeded accounts) was outside the 10-point checklist explicitly given for this phase. This finding relies on `apply-progress.md`'s recorded real run (200 byte-identical body, 401 anonymous, 403 VIEWER) plus `docker-smoke.yml` in CI, consistent with the design's own testing-strategy table, which names CI as the authority for this scenario.

**SUGGESTION**:

1. `App.test.jsx`'s "a VIEWER deep-linking to /api-docs ... the contract is never fetched" test proves its claim implicitly — `authedRouter`'s mock throws on any URL not in its explicit whitelist, and `/api/openapi.yaml` isn't whitelisted, so a regression that fetched it anyway would throw and fail the test. This is a real and effective guard, but it isn't self-evident from reading the test alone. An explicit `expect(fetchSpy).not.toHaveBeenCalledWith(expect.stringContaining('openapi.yaml'))` would make the enforcement visible without relying on the shared helper's throw-on-unlisted behavior.
2. Per-changed-file coverage percentages were not extracted this pass; if a coverage gate is ever added to CI, `OpenApiDocumentController.java`'s single throw branch (missing classpath resource) has no direct test exercising it — it's only reachable by breaking the build, which the mutation test in this report exercised for the *drift guard*, not for the controller's own `IllegalStateException` path. Not currently required by the spec, and behavior is otherwise proven safe (fail loud, never empty 200) by design + code inspection.

### Verdict

**PASS WITH WARNINGS**

Both suites are green (2017/0/0/7 backend, 268/268 frontend), independently re-run and byte-for-byte matching `apply-progress.md`'s claims. Both commits are green in isolation in the documented order. Two guards specifically engineered against silent regression (the byte-identity test, the deny-list anti-rot test) were proven non-vacuous by real mutation in this pass, not merely read and trusted. All 8 requirements / 14 scenarios across both spec artifacts have covering evidence; none are FAILING or UNTESTED outright. The three WARNINGs above are real gaps (a narrow content-assertion, a documentation-format mismatch, and one scenario resting on recorded-not-reproduced evidence) — none of them contradicts a passing test or reveals broken behavior, so none rises to CRITICAL.
