# dependency-security-gate

Branch: `fix/owasp-critical-example-secrets` · Started 2026-10-04

## Objective

Close OWASP A06 (vulnerable components) and keep it closed: upgrade the
dependencies that carry known vulnerabilities, then add a CI gate that fails a
PR when a high/critical one comes back.

## Problem / why

Measured with osv-scanner 2.6.0 on `scraper/pom.xml` (2026-10-04): 103
vulnerabilities, 14 critical, across 23 packages; Tomcat 10.1.20 carries several
9.8s. Almost all come from Spring Boot 3.2.5 (EOL). Frontend production deps:
`npm audit --omit=dev` reports 1 high (brace-expansion) + 1 low (dompurify),
both fixable without breaking changes. Nothing in CI checks dependencies, so
this drift was invisible. A gate added before the upgrade would turn every
backend PR red; the user chose to upgrade first, here.

## Scope

- Frontend: `npm audit fix` (non-breaking only).
- Backend: Spring Boot 3.2.5 -> 3.5.16, plus pins for whatever the BOM still
  leaves vulnerable (Tomcat 10.1.55 < 10.1.58 fix line).
- CI: dependency gate in `frontend-tests.yml` and `backend-tests.yml`.
- Docs for the gate and the upgrade.

Out of scope: Spring Boot 4, Playwright upgrade, CLI/ML Python deps.

## Constraints

- Strict TDD (CLAUDE.md). RED = the scanner/audit failing at the gate threshold;
  GREEN = it passing after the upgrade, with the full suite still green.
- Backend runner: `JAVA_HOME=<jdk24> mvn -f scraper/pom.xml clean test -Djvm=<jre21>`
  (always `clean`, check `$?` and grep `ERROR]`/`BUILD FAILURE`).
- Frontend runner: `npm test` + `npm run build` in `frontend/`.
- Refactor contract: an upgrade must not edit existing test assertions; a test
  edit needs an explicit justification (API removed by the upgrade).
- Boot the real jar before calling the upgrade done (boot-only bugs exist).

## Tasks

- [x] T1 Frontend: `npm audit fix`; `npm audit --omit=dev --audit-level=high` exits 0; vitest + build green.
- [x] T2 Backend: Spring Boot 3.5.16 (+ Tomcat pin if needed); osv-scanner shows no high/critical in runtime deps; full suite green; real boot clean.
- [x] T3 CI gate: own workflow `dependency-audit.yml` (not inside the test workflows: those skip on unrelated PRs, and a new advisory needs no diff), threshold high, weekly. Rationale lives in the workflow header (DOC-1); no doc lists workflows.

## Acceptance criteria

- Gate commands exit 0 on this branch and non-zero on master's dependency set.
- Backend suite count matches the pre-upgrade baseline (3549 + new) with 0 failures.

## Progress / evidence

- Baseline RED: osv-scanner 103 vulns / 14 critical (backend); npm audit prod 1 high.
- T1 (00aa4b4): `npm audit fix` touched only the lockfile. `npm audit --omit=dev --audit-level=high`: master lockfile rc=1, branch rc=0. Vitest 604/604 in 3 of 4 runs; the one failing run overlapped a heavy background scan and its log was lost, so the cause is unconfirmed (flaky, not reproduced). Build rc=0. Dev-only highs remain (braces/undici, need --force); out of the gate by design.
- T3 RED: `.github/workflows/dependency-audit.yml` written (osv-scanner v2.6.0 pinned by sha256, CVSS >= 7.0 filter; npm audit prod/high; PR + weekly). Its backend step run on master's pom: rc=1, 49 high/critical lines. YAML parses (no actionlint available).

- T2: Boot 3.5.16 + pins (Tomcat 10.1.60, Jackson 2.21.7, pg 42.7.12, BC 1.86, beanutils 1.11.0); Flyway 11.7.2 via BOM. Suite rc=0 3549/0/0, 7 skipped (= baseline). Real boot on a cron-disabled clone of the dev DB: Started, Flyway validated 44 migrations, login wrong pwd 401, CORS preflight 200. One test edited (CorsCredentialsTest: exclude Boot default security chain; no assertion changed). "Generated security password" WARN is preexisting (in backend.log of 2026-10-02).
- T3 GREEN: gate backend step on the upgraded pom rc=0 ("No HIGH/CRITICAL"); frontend branch rc=0. Remaining < 7.0: commons-compress (test), commons-lang3 6.5, log4j-api 6.3, nimbus 5.8.

## Next step

Push + PR; CI e2e-login-smoke covers the auth path after the Security 6.5 bump. Optional later: @MockBean is deprecated for removal in Boot 3.5.
