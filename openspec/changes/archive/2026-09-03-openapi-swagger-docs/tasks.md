# Tasks: OpenAPI Contract Documentation & Bidirectional Drift Guard

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | Commit A ~1030–1300 · Commit B ~566 · Total ~1600–1870 |
| 400-line budget risk | High |
| Chained PRs recommended | No — `size:exception` already accepted for Commit A (proposal Addendum, Q4) |
| Suggested split | Single PR containing both commits, in order A → B |
| Delivery strategy | `single-pr` |
| Chain strategy | `size-exception` |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: size-exception
400-line budget risk: High

**Why no further decision is needed**: the user already accepted `size:exception`
for the YAML+guard slice (Commit A) because direction 2 of the guard fails
unless every live route is documented — the YAML is atomic and cannot be
sub-sliced without shipping a temporarily weakened guard. Commit B (~566
lines) is a straightforward deletion once A lands and fits comfortably.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| A | Add `docs/openapi.yaml` + bidirectional guard, additive only | Same PR, commit A | `... -Dtest=OpenApiRouteCoverageTest,RouteCoverageTest` (see toolchain cmd below) | N/A — test-only change, no `src/main` touched, nothing to run manually | `git revert` removes the new YAML, `LiveRoutes.java`, `OpenApiRouteCoverageTest.java`, and the two-line `RouteCoverageTest` delegation; no `src/main` change so no jar rebuild |
| B | Retire mechanical prose from the three markdown docs | Same PR, commit B | Full suite (docs-only, but `TEST-1` requires green on each commit) | N/A — docs-only | `git revert` restores `API_REFERENCE.md`, `CLAUDE.md`, `ARCHITECTURE.md` verbatim; revert B before A |

Toolchain (split JDK, `TEST-1`, `clean` mandatory before closing each commit):
```
JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 \
  mvn -f scraper/pom.xml clean test \
  -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
```

---

## Commit A — `feat(docs): add the OpenAPI contract and its bidirectional drift guard`

### Phase A1: Guard-first, RED before transcription (Strict TDD, `TEST-2`)

- [x] A1.1 Extract `ar.scraper.security.LiveRoutes` (package-private, test tree)
  with the `Ruta` record, `todas()`, `concretar()` copied verbatim from
  `RouteCoverageTest.rutasDeLaAplicacion()`/`concretar()`. Replace those two
  private methods in `RouteCoverageTest.java` with one-line delegating
  wrappers and an import of `LiveRoutes.Ruta`.
  **Verify (diff-shape, `CODE-2`)**: `git diff scraper/src/test/java/ar/scraper/security/RouteCoverageTest.java`
  shows only delete-helpers / add-two-delegating-lines / add-import. Confirm
  by inspection that zero `@Test` bodies, zero `@DisplayName`, zero assertions
  changed. Run `-Dtest=RouteCoverageTest`: all eight tests still pass.
- [x] A1.2 Create stub `docs/openapi.yaml`: `openapi: 3.1.0`, `info` (including
  the guard-limit sentence — placement 2 of 3, see A1.4), empty `components`
  skeleton, `paths: {}`.
- [x] A1.3 Write `ar.scraper.security.OpenApiRouteCoverageTest` (~190–220
  lines, no `@SpringBootTest`): `new Yaml(new SafeConstructor(new LoaderOptions()))`
  reader; `theControllerScanIsNotVacuous` (`LiveRoutes.todas().size() > 40`);
  `theYamlParseIsNotVacuous` (`paths` keys > 40 **and** flattened ops > 40);
  direction 1 (documented → live via `ApiRoutePolicy.coincide`, catches
  documented-but-denied); direction 2 (live → documented via `LiveRoutes`,
  catches live-but-undocumented, scanning controllers not `ApiRoutePolicy.TABLE`
  because ten policy patterns are wildcards); an `x-access` parity assertion
  against `ApiRoutePolicy.Access` verbatim. Every check collects into a
  `List<String>` asserted `isEmpty()` so all offenders print together, in the
  four exact message forms from `design.md` §Guard design.
  Class javadoc states the guard's limit — placement 1 of 3: **path, method
  and access level only, never response-shape accuracy.**
- [x] A1.4 Run `OpenApiRouteCoverageTest` against the stub YAML. **Capture the
  RED output verbatim** — it is the complete transcription worklist, derived
  from the code, and drives Phase A2's checkpoints.

### Phase A2: Transcribe tag-group by tag-group (16 groups, `CLAUDE.md` §API REST order)

Each task adds that group's paths/methods/`tags`/`x-access`/`security` to
`docs/openapi.yaml`, wiring shared `parameters`/`responses`/`securitySchemes`
under `components`. **After every group**, run only `OpenApiRouteCoverageTest`
(JRE 21) and check both: (a) the red count from A1.4's baseline decreases or
stays — **never rises**; (b) `rg -c '^  /' docs/openapi.yaml` reconciled
against the scan's cumulative distinct-path count for groups transcribed so
far, localising any indentation slip to the group just added.

- [x] A2.1 Auth: `POST /api/auth/login`, `POST`/`DELETE /api/auth/refresh`,
  `GET /api/auth/me`, `POST /api/auth/password-reset/request`, `.../confirm`.
  Also `GET /` (RootController liveness) and `OPTIONS /**` (CORS preflight,
  not reflection-visible — documented but excluded from direction-2 scan by
  construction; note this in a YAML comment).
- [x] A2.2 Usuarios: `GET`/`POST /api/usuarios`, `PUT /api/usuarios/{u}/rol`,
  `DELETE /api/usuarios/{u}`, `PUT /api/usuarios/{u}/activar` — all ADMIN.
- [x] A2.3 Scraping: `GET /api/status`, `POST /api/scrape`,
  `POST /api/scrape/cancel`, `GET /api/scrape/interrupted`,
  `POST /api/scrape/resume`.
- [x] A2.4 Catálogo: `GET /api/data`, `GET /api/facets`, `GET /api/csv`,
  `GET /api/producto/{key}`, `DELETE /api/data`. Shared 18 catalog filter
  `parameters` land here as reusable `components.parameters`.
- [x] A2.5 ML: `GET /api/tendencias`, `GET /api/historial`,
  `GET /api/ml/estado`, `GET /api/ml/resultado`, `POST /api/ml/aplicar`,
  `POST /api/ml/renormalizar`, `POST /api/ml/entrenar`.
- [x] A2.6 Comparador: `GET /api/grupos`, `GET /api/buscar-externo`.
- [x] A2.7 Financiación: `GET`/`POST`/`PUT`/`DELETE /api/financiacion/presets`,
  `GET /api/recomendacion`, `GET /api/inflacion`.
- [x] A2.8 Outfits: `GET /api/outfits`, `GET /api/outfits/builder`,
  `GET /api/suplementos/builder`, `GET /api/suplementos/tipos`,
  `POST /api/outfits/feedback`, CRUD `/api/outfits/saved`.
- [x] A2.9 Para ti: `GET /api/recomendados`, `POST /api/recomendados/feedback`,
  `POST`/`DELETE /api/recomendados/dismiss-categoria`.
- [x] A2.10 Favoritos: `GET`/`POST`/`DELETE /api/favoritos`.
- [x] A2.11 Picks/Marcas: `GET /api/mejores`, `GET /api/marcas-browser`.
- [x] A2.12 Sitios: `GET`/`POST`/`DELETE /api/sitios`.
- [x] A2.13 Config: `PUT /api/config`.
- [x] A2.14 Cron: `GET`/`POST /api/cron`, `GET`/`PUT`/`DELETE /api/cron/{id}`,
  `GET /api/cron/{id}/executions`, `POST /api/cron/{id}/run-now`.
- [x] A2.15 DB: `GET /api/db/export`, `POST /api/db/import` (410 Gone),
  `DELETE /api/db/productos` (409, unscoped-favourites guard — mark with a
  short YAML comment pointing to its rationale in `docs/API_REFERENCE.md`,
  not restated here), `GET /api/db/ml`.
- [x] A2.16 LLM Agent: `POST /api/agent/chat`, `POST /api/agent/apply`,
  `GET /api/agent/models`.

### Phase A3: Zero-red confirmation and the two mutation self-tests

- [x] A3.1 Confirm `OpenApiRouteCoverageTest` is fully green (red count = 0
  after A2.16), then run the whole suite with `clean` (toolchain command
  above). Record pass count.
- [x] A3.2 Mutation self-test (a), **run alone**: add a bogus
  `/api/does-not-exist` path+method to `docs/openapi.yaml`. Run
  `OpenApiRouteCoverageTest`. **Verify**: fails, direction 1
  ("documented but denied") names exactly that route in the recorded
  message. Revert the mutation before continuing.
- [x] A3.3 Mutation self-test (b), **run alone, after (a) is reverted and
  green again**: delete one real `paths` entry from `docs/openapi.yaml`. Run
  `OpenApiRouteCoverageTest`. **Verify**: fails, direction 2
  ("live but undocumented") names exactly that route. Revert the mutation.
  Re-confirm green before moving on — the two self-tests must never overlap,
  so neither can mask the other (`design.md` §Negative control).
- [x] A3.4 Guard-limit placement 2 of 3: confirm `docs/openapi.yaml`'s
  `info.description` states the limit (path/method/access parity only, never
  response-shape accuracy) — already written in A1.2/A2, verify it survived
  transcription unedited.
- [x] A3.5 Full suite green with `clean` one more time as the commit-A close
  gate (`TEST-1`). Stage exactly: `docs/openapi.yaml`,
  `scraper/src/test/java/ar/scraper/security/LiveRoutes.java`,
  `scraper/src/test/java/ar/scraper/security/OpenApiRouteCoverageTest.java`,
  `scraper/src/test/java/ar/scraper/security/RouteCoverageTest.java`. Commit
  with a conventional subject naming the behaviour (`COMMIT-1`/`COMMIT-2`),
  no AI attribution (`COMMIT-3`).

---

## Commit B — `docs(api): retire the mechanical contract from the markdown`

### Phase B1: `docs/API_REFERENCE.md`

- [x] B1.1 Delete the ~450 mechanical path/method/status-code sections
  (the per-endpoint headers listed under "Índice de endpoints" and below).
  **Preserve, unedited, the five rationale clusters** (`explore.md` line
  refs): 401-vs-403 semantics (throughout + near login), per-owner data
  scoping, the unscoped `DELETE /api/db/productos` favourites guard
  (~919–930), the four identical login 401s (~107–133), the CSRF-nonce/
  cold-boot/`SameSite` design (~155–277). Add a short header pointing to
  `docs/openapi.yaml` as the mechanical contract, restating the guard's
  limit — **placement 3 of 3**.
  **Verify**: file shrinks from ~1094 to ~644+40 lines; each of the five
  rationale paragraphs is still present verbatim (grep each cluster's
  distinguishing phrase).
- [x] B1.2 Verify `docs/FRONTEND_AUTH_CONTRACT.md` is byte-identical:
  `git diff --stat -- docs/FRONTEND_AUTH_CONTRACT.md` must be empty.

### Phase B2: `CLAUDE.md` §API REST (lines 186–250)

- [x] B2.1 Replace the section (mechanical table + embedded rationale
  blockquote) with ~8 navigational lines pointing to `docs/openapi.yaml`
  (mechanical contract) and `docs/API_REFERENCE.md` (rationale) — the
  `docs/DATABASE.md` pointer pattern this file already uses for the database
  topic.
- [x] B2.2 **`DOC-1` audit**: for every fact removed from CLAUDE.md's
  blockquote (401-vs-403, per-owner scoping, the unscoped DELETE guard, the
  six-entry permit list), confirm exactly one surviving copy across
  `docs/API_REFERENCE.md` / `docs/openapi.yaml` / `docs/ARCHITECTURE.md` —
  never zero, never two. Where a fact has no surviving equivalent, move
  (not delete) that paragraph into `docs/API_REFERENCE.md` before finishing
  B2.1, rather than losing it.

### Phase B3: `docs/ARCHITECTURE.md`

- [x] B3.1 Add a ~12-line index paragraph pointing to `docs/openapi.yaml` and
  `docs/API_REFERENCE.md`, mirroring the exact pattern already at
  `ARCHITECTURE.md:5` (`## Base de datos → [DATABASE.md]`). Do **not** absorb
  the rationale bucket here — index only, per `proposal.md`'s explicit
  rejection of doubling `ARCHITECTURE.md`'s size.

### Phase B4: Close

- [x] B4.1 Full suite green with `clean` (toolchain command above) — docs-only
  diff, but `TEST-1` requires green on **each** commit.
- [x] B4.2 Stage exactly `docs/API_REFERENCE.md`, `CLAUDE.md`,
  `docs/ARCHITECTURE.md`. Commit with a conventional subject naming the
  behaviour (`COMMIT-1`/`COMMIT-2`), no AI attribution (`COMMIT-3`), and
  confirm `docs/FRONTEND_AUTH_CONTRACT.md` was not staged.
