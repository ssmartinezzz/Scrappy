# Spring Boot 4.1 (Spring Framework 7.0.9)

**Objective:** clear the two CRITICAL advisories the Dependency Audit gate reports on
`spring-webmvc 6.2.19` (GHSA-pc63-qcmh-9cmg, GHSA-j9f9-w8pj-32f8, CVSS 9.8).

**Problem:** the 6.2 line has no fixed release (6.2.19 is the last one on Maven Central and is
`last_affected`); the fix is Framework 7.0.9, which ships with Spring Boot 4.1.1. The audit was
green on 2026-10-05 with the same pom; it went red when the advisories were re-scored, so it
blocks every open PR (first seen on #293).

**Exposure today (code read, not a pentest):** `XsltView` is unused; SSE (`EventsController`)
uses `SseEmitter` without fragment rendering. Not exploitable as far as the code shows — the
upgrade is still required because the gate is the policy (OWASP chain #289–#291).

**Scope:** `scraper/pom.xml` and whatever source/test changes Boot 4 forces. No behavior change.
**Constraints:**
- CODE-2: a pure upgrade — existing tests pass without editing their assertions. Mechanical
  edits forced by moved APIs (package renames, annotation moves) are allowed and listed.
- API JSON contract must not change (field names, date format, nulls). `docs/openapi.yaml` and
  the e2e suite are the contract.
- Flyway migrations are byte-frozen.

**TDD:** on (CONTRIBUTING CODE-1), but an upgrade has no new behavior: the RED is the audit
failure and the GREEN is the full suite + boot + audit. Runner (TEST-2):
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`

## Tasks

- [x] B1 Bump parent to 4.1.1, fix the build (starters renamed/split, moved packages).
- [x] B2 Full backend suite green with `clean`; list every test file touched and why.
- [x] B3 JSON contract unchanged: compare real responses of the main endpoints before/after.
- [x] B4 Real boot against the dev DB (Flyway validate, `Started App`, no WARN regressions).
- [x] B5 e2e (`tests/e2e/run-e2e.sh`) and Docker Smoke (dispatch by hand: pom-only changes).
- [ ] B6 PR, CI green incl. Dependency Audit, merge; then update #293 and merge it.

## Evidence

Measured on branch build/spring-boot-4 (uncommitted), 2026-10-07.

- B1 Build: parent 4.1.1; `starter-web` -> `starter-webmvc`, `flyway-core` -> `starter-flyway`,
  `spring-boot-starter-webmvc-test` + `starter-security-test` added, Testcontainers 2 artifact names,
  overrides for tomcat/postgresql/aspectj/testcontainers dropped, `jackson-2-bom.version` 2.21.7 kept,
  Lombok kept at 1.18.38 (1.18.46 emits `zscore` next to `zScore`).
- B2 Suite (clean): `Tests run: 3552, Failures: 0, Errors: 0, Skipped: 7` (3549 + 3 new in
  `HttpJsonBodyTest`). Master HEAD baseline measured the same way: 3549 tests; that run also showed
  one `ScrapeRunIndexBenchmarkTest.medirLasTresRamas` failure (timing flake; green on the migrated
  tree). Compiler output with `showDeprecation`/`showWarnings`: zero warnings.
  Test files touched (mechanical, no assertion edited): DatabaseBootRetryConfigTest,
  TransactionWiringTest (flyway/jdbc package moves); SecurityFilterChainIT,
  AuthEndpointsBootstrapCsrfTest, AuthEndpointsMappingTest, AuthEndpointsMeTest, CorsConfigTest,
  OpenApiDocumentControllerTest, SecurityErrorBodyTest, SseRealPortTest (@MockitoBean,
  @WebMvcTest package); CorsCredentialsTest, HttpCompressionRealPortTest (security autoconfig moves
  + excluding ServletWebSecurityAutoConfiguration / UserDetailsServiceAutoConfiguration);
  AgentControllerTest (JacksonJsonHttpMessageConverter); PostgresTestBase (Testcontainers 2
  PostgreSQLContainer); TestTransactions (TransactionManager overload); ApiExceptionHandlerTest
  (isContentTooLarge); RequiredEnvVarsGuardTest (javadoc link only).
- B3 JSON contract: ~50 requests against the master jar vs the migrated jar (dev DB, crons off).
  Statuses and headers equal; bodies byte-identical except endpoints that are non-deterministic on
  master itself (grupos, mejores, data, marcas, outfits, outfit_builder, usuarios, csv, login/me
  ids). Real finding: Jackson 3 serialized Jackson 2 `JsonNode` as a bean on six endpoints; the
  suite was green anyway. Fixed with `LegacyJsonNodeSerializer` plus
  `spring.jackson.use-jackson2-defaults=true`. Only remaining byte difference: `/api/recomendacion`
  writes the emoji as raw UTF-8 instead of a `\uD83D\uDCCA` escape (equal once parsed).
- B4 Boot: `Started App`, `[DB] Conectado`, 44/44 Flyway history rows successful, WARN diff vs master
  is only the package name of `UserDetailsServiceAutoConfiguration` (same generated-password
  warning). `RequiredEnvVarsGuard` (now `org.springframework.boot.EnvironmentPostProcessor`) verified:
  boot without DATABASE_URL exits 1 naming it; normal boot starts.
- Dependency tree: spring-webmvc / spring-web / spring-core 7.0.9, security 7.1.1, Tomcat 11.0.26 (override, see below),
  Flyway 12.4.0, postgresql 42.7.13; no 6.x Spring artifact in the tree or in the packaged jar.
  `osv-scanner` not installed locally; the Dependency Audit gate must confirm in CI.

- B5 e2e (`tests/e2e/run-e2e.sh`, migrated jar, crons off): browser 31/31. API 48/51 at first —
  the 3 `test_password_reset.py` failures are pre-existing on master, not Boot 4: 6247f7f
  (2026-10-03, OWASP remediation) moved the reset link to DEBUG and the e2e suite (in no CI
  workflow) still read it at INFO. The harness now raises `ar.scraper.security.reset` to DEBUG
  (the package: an env var binds a lower-cased logger name, so the class name never matched).
  After: API 51/51. Docker Smoke: dispatched in CI with the PR.

- B6 first CI run (#294): Spring cleared, but the gate then reported what Boot 4.1.1 manages —
  Tomcat 11.0.24 (GHSA-9xv2-5v5q-p794 9.8, GHSA-h3x4-894j-xpx5 9.1, GHSA-gcx9-497g-6cp6 9.1;
  fixed 11.0.25) and Jackson 3.1.5 (five 7.5 advisories; fixed 3.1.7). Reproduced locally with
  CI's own osv-scanner v2.6.0 (same SHA-256, same threshold): exit 1 → overrides
  `tomcat.version` 11.0.26, `jackson-bom.version` 3.1.7 → "No HIGH/CRITICAL vulnerabilities.".
  Re-verified: suite 3552/0, packaged jar holds tomcat-embed-core-11.0.26 and
  jackson-databind-3.1.7, e2e API 51/51 (real boot). Docker Smoke (dispatched): success.

## Next step

B6: PR, CI green incl. Dependency Audit, merge; then update #293 and merge it.
