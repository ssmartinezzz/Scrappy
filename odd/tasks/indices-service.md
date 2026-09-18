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

- [ ] T1 Domain: `Indice`, `PuntoIndice`, `Confianza`, `Deflactor`, `Serie`, `DeflactorPorRubro`, `Extrapolador` + unit tests (factor by dates, extrapolation both frequencies, clamp, NEUTRO)
- [ ] T2 Ports + `IndiceService` + `IndiceRefreshJob` + tests with in-memory fakes (chain fallback, atomic swap, resumen math: mensual/interanual/3m by DATE not by index)
- [ ] T3 `V33__indices.sql` + `db/IndiceRepository` + `PostgresTestBase.truncateAll` + Postgres test (upsert idempotent, order) + `docs/DATABASE.md` V33 entry with rollback SQL
- [ ] T4 HTTP adapters in `ar.scraper.fuentes` + parser tests from fixture bodies (verify USD endpoint shape live if reachable)
- [ ] T5 Consumers: `SenalEnricher` by dates + rubro, `SenalCompra.confianzaDeflactor`, `ProductJson`, `FinanciacionEnricher`, `FinanciacionEndpoints` `/api/indices`, `openapi.yaml`, delete `InflacionService`, ArchUnit updates — existing tests adapted only where the API they call was removed
- [ ] T6 Frontend: `fetchIndices`, `ipc-badge.jsx` + test, `Topbar` swap, `BuySignal` confianza
- [ ] T7 Docs: `CLAUDE.md` (stack row + estructura + gotcha on months-by-count), `docs/ARCHITECTURE.md` (why USD for tecnologia, why extrapolate-and-mark), `docs/API_REFERENCE.md`
- [ ] T8 Full backend + frontend suites green; boot the backend for real (memory `backend-runtime-smoke-recipe`) and hit `/api/indices`

## Acceptance

- A product with 4 price changes in one week and one with 1 change in 8 months get factors driven by their DATES.
- `tecnologia` products are deflated by USD_OFICIAL; others by IPC (test per rubro).
- Any factor beyond the last observed point carries `EXTRAPOLADO` + days; empty series → `NEUTRO`.
- No hardcoded macro numbers anywhere in `main/`.
- `InflacionService` no longer exists; no class outside `indices/` depends on an adapter.
- ArchUnit green with `indices` as a sink area.

## Progress / evidence

(filled per task)
