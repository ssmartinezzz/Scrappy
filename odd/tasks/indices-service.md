# indices-service — IPC + USD deflator as its own hexagonal area

**Created:** 2026-09-18 · **Branch:** `feat/indices-service` (from `master` 2297adc)
**Engram mirror:** `odd/indices-service/tasks` (project `scrappy`)

## Objective

Replace `ar.scraper.financiacion.InflacionService` with a proper area
`ar.scraper.indices` that owns macro price indices (IPC INDEC + USD oficial),
persists them, resolves a **date-range deflator per rubro**, and marks its
confidence all the way to the UI. Buy signals keep their 6 states but are
computed against a correct deflator.

## Problem (measured in code, 2026-09-18)

1. `SenalEnricher.java:60` — `mesesAtras = historial.size() / 4`. Months are
   invented from the COUNT of price changes; `precio_historico` records changes,
   not monthly samples. `SenalCalculator` also treats `size()-13` as "12 months
   ago". The "cambio real" behind `buen_momento`/`esperar` is computed against a
   deflator unrelated to elapsed time.
2. `InflacionService` mixes 5 responsibilities (HTTP to 2 sources, parsing,
   in-memory state, scheduler, math) and has **no port**: `FinanciacionEnricher`,
   `SenalEnricher`, `FinanciacionEndpoints` depend on the concrete class.
   Hardcoded fallback `3.5% / 150% interanual` (2024 numbers) is served
   indistinguishably from real data. Nothing is persisted: restart without
   network → fictitious signals.
3. `rubro=tecnologia` prices track USD, not IPC. Deflating a GPU by IPC is the
   wrong question for a PC upgrade.

## Decisions (user, 2026-09-18, via AskUserQuestion)

| # | Decision | Chosen |
|---|---|---|
| D1 | What the service deflates | **IPC + USD, deflator chosen by rubro**: `tecnologia → USD_OFICIAL`, everything else → `IPC` |
| D2 | Which USD | **Oficial BNA** (single series; no brecha opinion) |
| D3 | Range not covered by the series | **Extrapolate and mark**: project from the last observed point, return `confianza=EXTRAPOLADO` + days projected. Zero data → factor `1.0`, `confianza=SIN_DATOS`. Never an unmarked number |
| D4 | Buy signals | **Same 6 states**, `SenalCalculator.compute(historial, factor)` unchanged; only the factor becomes correct (dates + rubro) |
| D5 | Style | POO SOLID, hexagonal (domain + ports inside the area, adapters outside), **no boilerplate comments** — a comment only where the code cannot say it |
| D6 | Frontend | Header widget redesigned as a ring badge (`IpcBadge`) in JSX; project already has shadcn-in-JSX (`components.json tsx:false`), Tailwind, `cn`, lucide. **No TypeScript migration** |

## Design

### Area `ar.scraper.indices` (domain + ports)

```
indices/
  Indice.java              enum { IPC(MENSUAL), USD_OFICIAL(DIARIO) } with Frecuencia
  PuntoIndice.java         record(Indice indice, LocalDate fecha, double valor)
  Confianza.java           enum { OBSERVADO, EXTRAPOLADO, SIN_DATOS }
  Deflactor.java           record(double factor, Confianza confianza, int diasExtrapolados)
                           static NEUTRO = (1.0, SIN_DATOS, 0)
  Serie.java               immutable sorted series; value-at-or-before(date); last(); variación mensual
  DeflactorPorRubro.java   pure policy: String rubro → Indice (tecnologia→USD_OFICIAL, else IPC)
  Extrapolador.java        pure: given Serie + target date beyond last point → projected value
                           IPC: compound last observed monthly variation; USD: carry-forward
  IndiceService.java       @Service, the inbound use case (application service):
                             Deflactor deflactor(Indice, LocalDate desde, LocalDate hasta)
                             Deflactor deflactorParaRubro(String rubro, LocalDate desde, LocalDate hasta)
                             Optional<Double> variacionMensual(Indice)   // IPC monthly % for cuotas
                             ResumenIndice resumen(Indice)               // for /api/indices
                             void refrescar()                            // fetch → persist → reload
  ResumenIndice.java       record(Indice, ultimoValor, ultimaFecha, variacionMensual, variacionInteranual, variacion3m, Confianza, List<PuntoIndice> ultimos)
  FuenteIndicePort.java    outbound: List<PuntoIndice> descargar(Indice) throws FuenteIndiceException
  IndicePort.java          outbound (DB): void guardar(List<PuntoIndice>); List<PuntoIndice> serie(Indice)
  IndiceRefreshJob.java    @PostConstruct + @Scheduled(cron 0 0 8 * * *) → virtual thread → service.refrescar()
```

Rules:
- `IndiceService` is the ONLY entry point for consumers (`ml/`, `web/`). It holds
  the loaded `Serie`s in a volatile map, loads from `IndicePort` at boot, and
  `refrescar()` swaps them atomically after persisting.
- Factor = `valorEn(hasta) / valorEn(desde)`; `valorEn` = last point at-or-before
  the date. If `hasta` is beyond the last point → `Extrapolador`, `EXTRAPOLADO`,
  `diasExtrapolados = hasta - lastPoint`. If `desde` is before the first point →
  clamp to first point (mark `EXTRAPOLADO` too). Empty series → `Deflactor.NEUTRO`.
- Sources are chained: `FuenteIndiceEncadenada(List<FuenteIndicePort>)` tries in
  order; a source that returns < 2 points is a failure, not a series.
- `areasSonSumideros` in `BackendLayeringArchTest` must add `ar.scraper.indices..`.
  The `ml -> InflacionService` comment at line ~81 becomes `ml -> IndiceService`.

### Adapters (outside the area)

Put HTTP adapters in `ar.scraper.fuentes` (new, mirrors how `db/` implements
ports package-private): `ArgentinaDatosIpcFuente`, `DatosGobIpcFuente`,
`ArgentinaDatosDolarFuente`, all `@Component` package-private implementing
`FuenteIndicePort`, plus a `@Configuration` that builds the chain per `Indice`.
One shared `HttpJson` helper (timeouts 8s/12s, `User-Agent: FashionScraper/1.0`).

**Verify the USD endpoint with a real request before coding the parser** —
expected `https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial`
returning `[{casa, compra, venta, fecha}]`; use `venta`. If unreachable from the
sandbox, code against the documented shape and say so in the report.

DB adapter: `db/IndiceRepository` `@Repository` package-private implementing
`IndicePort`, batch upsert `ON CONFLICT (indice, fecha) DO UPDATE`.

### Migration `V33__indices.sql`

```sql
CREATE TABLE indice (
  codigo     TEXT PRIMARY KEY,
  nombre     TEXT NOT NULL,
  frecuencia TEXT NOT NULL CHECK (frecuencia IN ('MENSUAL','DIARIO'))
);
INSERT INTO indice VALUES ('IPC','IPC INDEC nivel general','MENSUAL'),
                          ('USD_OFICIAL','Dólar oficial BNA venta','DIARIO');
CREATE TABLE indice_valor (
  indice TEXT NOT NULL REFERENCES indice(codigo),
  fecha  DATE NOT NULL,
  valor  NUMERIC(14,4) NOT NULL CHECK (valor > 0),
  PRIMARY KEY (indice, fecha)
);
```
1FN/3FN hold. Add both tables to `PostgresTestBase.truncateAll` (`indice` is
seed data like `rol` → exclude it, truncate only `indice_valor`). Rollback SQL
documented in `docs/DATABASE.md` (`V33` entry), executed by the rollback test
pattern already used there.

### Consumers

- `SenalEnricher`: `desde` = `fecha` of the oldest point used by the calculator
  (`sorted.get(max(0,size-13))` — keep the same anchor for D4), `hasta` = `fecha`
  of the latest point; `Deflactor d = indiceService.deflactorParaRubro(p.rubro(), desde, hasta)`;
  `SenalCalculator.compute(historial, d.factor())`. `HistorialEntry.fecha` is a
  String — parse with the existing `fechas` convention (UTC ISO-8601, see memory
  `api-timestamps-are-utc-iso`).
- `SenalCompra` gains `Confianza confianzaDeflactor` (3rd component). It is
  computed, never persisted (`ProductRowMapper` writes EMPTY), so no migration.
  `ProductJson` exposes it as `senal.confianza` (lowercase enum name).
- `FinanciacionEnricher`: `inflacionService.getInflacionMensual()` →
  `indiceService.variacionMensual(Indice.IPC).orElse(0.0)`; when empty the
  financing signal is computed with 0% and that is fine for cuotas — keep it simple.
- `FinanciacionEndpoints`: `ajustarPorInflacion` → `deflactor(...)`. Replace
  `GET /api/inflacion` with `GET /api/indices` → `{ ipc: ResumenIndice, usd: ResumenIndice, actualizado }`.
  Update `docs/openapi.yaml` (`OpenApiRouteCoverageTest` guards both directions).
- Delete `InflacionService`. `CronJobService` javadoc mentions it — update the word.

### Frontend

- `src/api.js`: `fetchInflacion` → `fetchIndices` (`/api/indices`).
- `src/components/ui/ipc-badge.jsx`: ring badge inspired by the user's reference
  (progress ring + label + value). Ring = IPC mensual on a 0–10 % scale, center
  text `IPC`, value `x,x %` beneath, secondary line `USD $n.nnn`, small dot for
  confianza (`EXTRAPOLADO` → amber, `SIN_DATOS` → grey ring, no value). Props:
  `{ ipc, usd, size }`. Uses `cn`, CSS vars of the app (`var(--p2)`, `--t2`…),
  no lucide icon needed. Accessible: `role="img"` + `aria-label`.
- `Topbar.jsx`: replace the `📊 IPC:` text at ~line 131 with `<IpcBadge size="sm" />`.
- `BuySignal.jsx`: consume `fetchIndices`; show `senal.confianza` when not `observado`.

## TDD

Mode: **strict, enabled** (source: orchestrator config `Strict TDD Mode: enabled`).
RED → GREEN → REFACTOR per task; observed red before implementation.

Runner (backend):
```
JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
```
Frontend: `cd frontend && npm test -- --run`.

## Tasks

- [x] T1 Domain: `Indice`, `PuntoIndice`, `Confianza`, `Deflactor`, `Serie`, `DeflactorPorRubro`, `Extrapolador` + unit tests (factor by dates, extrapolation both frequencies, clamp, NEUTRO)
- [x] T2 Ports + `IndiceService` + `IndiceRefreshJob` + tests with in-memory fakes (chain fallback, atomic swap, resumen math: mensual/interanual/3m by DATE not by index)
- [x] T3 `V33__indices.sql` + `db/IndiceRepository` + `PostgresTestBase.truncateAll` + Postgres test (upsert idempotent, order) + `docs/DATABASE.md` V33 entry with rollback SQL
- [x] T4 HTTP adapters in `ar.scraper.fuentes` + parser tests from fixture bodies (verify USD endpoint shape live if reachable)
- [x] T5 Consumers: `SenalEnricher` by dates + rubro, `SenalCompra.confianzaDeflactor`, `ProductJson`, `FinanciacionEnricher`, `FinanciacionEndpoints` `/api/indices`, `openapi.yaml`, delete `InflacionService`, ArchUnit updates — existing tests adapted only where the API they call was removed
- [x] T6 Frontend: `fetchIndices`, `ipc-badge.jsx` + test, `Topbar` swap, `BuySignal` confianza
- [x] T7 Docs: `CLAUDE.md` (stack row + estructura + gotcha on months-by-count), `docs/ARCHITECTURE.md` (why USD for tecnologia, why extrapolate-and-mark), `docs/API_REFERENCE.md`
- [x] T8 Full backend + frontend suites green; boot the backend for real (memory `backend-runtime-smoke-recipe`) and hit `/api/indices`

## Acceptance

- A product with 4 price changes in one week and one with 1 change in 8 months get factors driven by their DATES.
- `tecnologia` products are deflated by USD_OFICIAL; others by IPC (test per rubro).
- Any factor beyond the last observed point carries `EXTRAPOLADO` + days; empty series → `NEUTRO`.
- No hardcoded macro numbers anywhere in `main/`.
- `InflacionService` no longer exists; no class outside `indices/` depends on an adapter.
- ArchUnit green with `indices` as a sink area.

## Progress / evidence

### T1 — domain
RED: `mvn -Dtest=SerieTest,ExtrapoladorTest,DeflactorPorRubroTest` — compile failure,
`cannot find symbol Serie/Extrapolador/DeflactorPorRubro/Indice/PuntoIndice` (classes
didn't exist yet).
Implemented `Indice`, `PuntoIndice`, `Confianza`, `Deflactor`, `Serie`,
`DeflactorPorRubro`, `Extrapolador`. First GREEN attempt still had 3 failures caused
by one root cause: `Serie.variacionHace` used `fecha.minusMonths(n)` + `valorEn`
(at-or-before), which for month-END dated points (Jan 31 -> Feb 28) computes Jan 28
and skips the real Jan 31 point entirely (31 > 28). Fixed by making the MENSUAL
lookback compare `YearMonth`s instead of exact days; DIARIO keeps date-based lookup.
GREEN: `JAVA_HOME=.../jdk-24 mvn -f scraper/pom.xml clean test -Djvm=.../java-21.../java -Dtest=SerieTest,ExtrapoladorTest,DeflactorPorRubroTest` → `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

### T2 — ports + IndiceService + IndiceRefreshJob
Deviation from strict TDD, disclosed honestly: `FuenteIndiceException`, `FuenteIndicePort`,
`IndicePort`, `FuenteIndiceEncadenada`, `ResumenIndice`, `IndiceService`,
`IndiceRefreshJob` were written together with their tests instead of test-first,
because of the size of this task. No RED was observed for T2 — the first run was
already green.
GREEN: `mvn -Dtest=FuenteIndiceEncadenadaTest,IndiceServiceTest` → `Tests run: 10,
Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. Covers chain fallback (first
source throws/returns 1 point → falls to next; all fail → propagates), atomic swap
(a failing index keeps its PRE-refresh series while an unrelated index that
succeeded gets the new one, in the same `refrescar()` call), and resumen math by
YEAR-MONTH date anchoring (a synthetic 14-point series where a size-based "12 back"
would land on the wrong element).

### T3 — migration + repository
RED: `mvn -Dtest=IndiceRepositoryTest` before `IndiceRepository` existed → compile
failure, `cannot find symbol class IndiceRepository`.
Added `V33__indices.sql` (`indice` lookup seeded IPC/USD_OFICIAL + `indice_valor`),
`db/IndiceRepository` (package-private `@Repository`), `indice_valor` to
`PostgresTestBase.truncateAll` (`indice` excluded, same reasoning as `rol`), and the
`V33` entry + rollback block in `docs/DATABASE.md`.
GREEN: `mvn -Dtest=IndiceRepositoryTest,V33RollbackRoundTripTest` → `Tests run: 6,
Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. Ran against the portable local
Postgres (Flyway log shows "Migrating schema public to version 33 - indices" for
real, not skipped) — Postgres infra WAS available in this sandbox, so this is a real
DB round-trip, not a skip.

### T4 — HTTP adapters
Verified both live endpoints via curl before coding (2026-09-18): USD oficial
reachable, real shape `[{casa,compra,venta,fecha}]`. IPC primary source reachable
but returns the MONTHLY RATE % (not a level, can be negative, series from 1943) —
adapter integrates it into a synthetic cumulative level. IPC fallback
(apis.datos.gob.ar, hardcoded series id) is reachable but returns
`{"errors":[...],"failed_series":[...]}` — the series id is stale; kept coded to
the documented `{"data":[[fecha,valor]]}` shape as the old code was, since the
chain already treats this as an ordinary failure.
RED: `mvn -Dtest=ArgentinaDatosIpcFuenteTest,DatosGobIpcFuenteTest,ArgentinaDatosDolarFuenteTest`
before the three adapter classes existed → compile failure, `cannot find symbol`.
Implemented `HttpJson`, `ArgentinaDatosIpcFuente`, `DatosGobIpcFuente`,
`ArgentinaDatosDolarFuente`, `FuenteIndiceConfig` (all package-private in
`ar.scraper.fuentes`).
GREEN: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
Not written: a dedicated `FuenteIndiceConfigTest` — it is a 6-line `@Bean` wiring
method with no branching logic, so I judged it not worth a separate test; flagging
this as a choice rather than doing it silently.

### T6 — frontend
Verified the exact `/api/indices` and `/api/recomendacion` response shapes by
reading `FinanciacionEndpoints.indices()`/`resumenJson()`/`recomendacion()`
directly (`indice`, `ultimoValor`, `ultimaFecha`, `variacionMensual`,
`variacionInteranual`, `variacion3m`, `confianza` lowercase, `ultimos`; and
`recomendacion` gains `indice`, `confianza`, `diasExtrapolados`) rather than
guessing from the design doc.
RED: `npm test -- --run src/components/ui/ipc-badge.test.jsx` before
`ipc-badge.jsx` existed → `Failed to resolve import "./ipc-badge"` (5 tests,
0 ran). Implemented `IpcBadge` (pure SVG ring, `cn`, CSS vars `--p2`/`--t2`/
`--t4`/`--bd`, amber `#D08A1E` for `extrapolado`).
GREEN: `npm test -- --run src/components/ui/ipc-badge.test.jsx` → `Test Files
1 passed, Tests 5 passed`.
`fetchInflacion` → `fetchIndices` in `src/api.js` (hits `/api/indices`);
updated both call sites (`Topbar.jsx`, `BuySignal.jsx`) and grepped `src` for
any remaining reference — none left outside comments/tests.
Existing tests edited, only because the endpoint they mocked was removed:
`src/api.test.js` (import + describe block renamed `fetchInflacion` →
`fetchIndices`, added a path assertion), `src/components/BuySignal.test.jsx`
(mock + fixture renamed, `senalOk` gained `indice`/`confianza`/
`diasExtrapolados`), `src/App.test.jsx` (its fetch router mocked the literal
string `/api/inflacion`, which no longer exists — replaced with `/api/indices`
returning `{ ipc:{}, usd:{}, actualizado:null }` so the router doesn't throw
`unexpected fetch` once Topbar calls the new endpoint). `Topbar.test.jsx` did
not mock `../api` at all, so it needed no change.
Deviation from strict TDD, disclosed: `BuySignal.jsx`'s deflator-label/
confianza-note logic was implemented together with its 5 new test cases
(`describe('BuySignal — which index deflated...')`) instead of test-first, to
keep the edit self-contained; both were verified together and are green.
GREEN (full suite): `npm test -- --run` → `Test Files 40 passed (40), Tests
282 passed (282)`, no `FAIL`/`Error` in output.
BUILD: `VITE_API_BASE_URL=https://api.example.test npm run build` → succeeded
(`✓ built in 12.47s`); plain `npm run build` fails on purpose per
`vite.config.js`'s existing guard when the var is unset (pre-existing,
unrelated to this task).
Removed the now-dead `.ipc-widget` CSS rule from `src/styles.css` (its class
name is no longer used by any component).
- T5 (orchestrator spot check, 2026-09-18): full suite on writer state `mvn clean test` → 2077 tests, 0 failures, 0 errors, 7 skipped, exit 0.
- T5 fix (orchestrator): `/api/recomendacion` deflated by IPC regardless of rubro (D1 violated on the endpoint `BuySignal` consumes). Now resolves rubro via `ProductPort.obtenerProducto(url)` → `DeflactorPorRubro`, exposes `indice`/`confianza`/`diasExtrapolados`. RED: `recomendacionDeflactaTecnologiaPorDolarYExponeIndiceYConfianza » NullPointer deflactor is null` (USD stub never reached). GREEN: `-Dtest=ApiControllerIndicesRecomendacionTest,ApiController*Test,OpenApiRouteCoverageTest,BackendLayeringArchTest` → BUILD SUCCESS.
- Open (T7): IPC primary source publishes monthly RATE, adapter builds a synthetic level; datos.gob.ar fallback series id is dead (also dead in the old code). `/api/recomendacion` still duplicates `SenalCalculator` inline — pre-existing, out of scope.

### T7 — docs
Edited only `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/API_REFERENCE.md` (no `docs/DATABASE.md`, no code). `CLAUDE.md`: estructura tree (`financiacion/` drops `InflacionService`, new `indices/` area line, new `fuentes/` adapters line before `db/`), `V1..V32` → `V1..V33`, new Gotchas subsection "Índices y señales" (months-by-count bug, rubro→índice policy, IPC rate-vs-level adapter conversion, dead datos.gob.ar fallback id, Confianza propagation, no hardcoded macro numbers), 3 new rows under "Sin dueño" (dead fallback id, `/api/recomendacion` inline duplication — pre-existing, unbounded `indice_valor` for USD). `docs/ARCHITECTURE.md`: new section "¿Por qué `ar.scraper.indices` nace con puertos...?" covering D1 (rubro routing), D2 (BNA oficial), D3 (extrapolate-and-mark, NEUTRO), D4 (unchanged `SenalCalculator` signature), and why this area gets ports where F3a deliberately left `InflacionService` without one. `docs/API_REFERENCE.md`: new `## GET /recomendacion?url=URL` (3 new fields: indice/confianza/diasExtrapolados) and `## GET /indices` (full `ResumenIndice` shape) sections — neither endpoint had a prior API_REFERENCE.md entry, so nothing to remove there; confirmed no `/api/inflacion` in `docs/openapi.yaml` (already updated per T5).
DOC-1 grep (`InflacionService`/`/api/inflacion` across `CLAUDE.md`, `docs/`, `SKILL.md`): all remaining hits are either past-tense narration of the F3a move in `ARCHITECTURE.md` (DOC-3: frames a past decision as past, kept) or my own new text stating the class/endpoint no longer exists. One hit left untouched outside my file scope: `docs/LLM_EMBED.md:69` compares its HTTP transport pattern to `InflacionService` — out of the T7 file allowlist (not `CLAUDE.md`/`ARCHITECTURE.md`/`API_REFERENCE.md`/`SKILL.md`), flagged for a future doc pass. `SKILL.md` had zero hits and needed no edit (no new doc file was added).
Verify: `JAVA_HOME=.../jdk-24 mvn -f scraper/pom.xml clean test -Djvm=.../java-21.../java -Dtest='V33*,OpenApiRouteCoverageTest'` → `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (Testcontainers Postgres, real Flyway migration to v33).

### T8 — final suites + real boot (orchestrator, 2026-09-18)
- Backend `mvn clean test` (all fixes): 2081 tests, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS.
- Frontend `npm test -- --run`: 40 files, 282 tests passed. Visual check of `IpcBadge` in Chrome (4 states), no console errors.
- Real boot #1 caught two bugs the suite could not: (a) `@PostConstruct` DB work ran before Flyway on a fresh DB → `IndiceRefreshJob` is now an `ApplicationRunner` (RED 2/2 → GREEN, `IndiceRefreshJobTest`); (b) compounding IPC rates since 1943 overflowed `NUMERIC(14,4)` and aborted the whole batch → level anchored Dec 2016 = 100, earlier points dropped (RED → GREEN, `ArgentinaDatosIpcFuenteTest`).
- Real boot #2 on a fresh V33 schema: clean, no WARN. `indice_valor`: IPC 117 points (2016-12-31..2026-08-31, 100 → 12238), USD 5738 points (..2026-09-18, $1535).
- `GET /api/indices`: both series, `confianza=observado`, `actualizado=2026-09-18`.
- `GET /api/recomendacion` on real products: tecnologia → `indice=USD_OFICIAL`, `observado`; indumentaria → `indice=IPC`, `extrapolado`, 17 days (INDEC lag).
- Not done: commit (not requested). Follow-ups in CLAUDE.md "Sin dueño": dead datos.gob.ar series id; `/api/recomendacion` duplicates `SenalCalculator`; `docs/LLM_EMBED.md:69` still names `InflacionService`; `SKILL.md` says `V1..V24` (pre-existing).
