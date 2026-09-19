# pc-builder — phase 2 of the PC builder: compatible-parts assembler + endpoint

**Created:** 2026-09-18 · **Branch:** `feat/pc-builder` (stacked on `docs/pc-techspecs-measurement`, PR #201 → #200 → `master`)
**Engram mirror:** `odd/pc-builder/tasks` (project `scrappy`)
**Phase 1:** [`pc-builder-specs.md`](./pc-builder-specs.md) — the parser and the measured coverage this phase relies on.

## Objective

Assemble a PC from the in-memory catalog the way `SupplementCombo` assembles
a supplement combo: one pick per slot, best-effort, optional budget, and —
the point of the feature — **hard compatibility vetoes** wherever both sides
of a rule parsed. A part whose name did not state the attribute never vetoes
(abstention = no veto, the `VisualCoherence` policy), because the phase-1
numbers say the alternative would empty slots: Gabinete form factor parses in
7% of rows.

## Scope (authorized)

- `ar.scraper.pcs.PcBuilder` (package-private class, one public facade
  method) + `PcPick`/`PcBuild` records in the same area.
- `TechSpecs` computed at build time via `TechSpecsParser.parse(nombre,
  categoria)` — no `Product` field, no migration.
- `GET /api/pcs/builder` (`AUTHENTICATED`) in a new `web/PcsEndpoints`,
  routed from `ApiController`; `ApiRoutePolicy` row + `docs/openapi.yaml`
  entry (`OpenApiRouteCoverageTest` guards both directions).
- Unit tests for the builder and the endpoint, TDD strict (RED observed
  before GREEN).

Out of scope: frontend page, `saved_pcs`, agent tool `propose_pc`,
`POST /api/pcs` — later phases. Parser changes (phase 1 is frozen in #200):
anything the builder needs to infer lives in the builder.

## Design

Slots, in pick order — the anchor goes first because every veto references it:

| # | slot | categoria | required |
|---|---|---|---|
| 1 | mother | `Motherboard` | yes |
| 2 | cpu | `CPU` | yes |
| 3 | ram | `RAM` | yes |
| 4 | gabinete | `Gabinete` | yes |
| 5 | fuente | `Fuente` | yes |
| 6 | gpu | `GPU` | opt-in (`conGpu=true`) |
| 7 | almacenamiento | `Almacenamiento` | yes |

Vetoes — each fires **only when both sides are non-empty**:

| rule | left | right | veto when |
|---|---|---|---|
| socket | `cpu.socket` | `mother.socket` | differ |
| ddr | `ram.ddr` | `motherDdr` | differ |
| form factor | `gabinete.formFactor` | `mother.formFactor` | case smaller than board (`ITX < MATX < ATX < EATX`) |
| watts | `fuente.watts` | minimum for the build | `watts < min` |

`motherDdr` = `mother.ddr` if parsed, else derived from the socket:
`AM5`/`LGA1851` → `DDR5`, `AM4` → `DDR4`, `LGA1700` → `""` (mixed platform,
stays abstained — phase-1 finding). This closes the 22% Motherboard-DDR gap
without touching the parser.

Minimum watts: `WATTS_MIN_SIN_GPU = 450`, `WATTS_MIN_CON_GPU = 650`.
**Assumption, not a measurement** — GPU draw is not parsed in phase 1, so the
floor is a conservative constant per build shape. Recorded here so phase 3
can replace it with a per-GPU estimate if it ever parses TDP.

Pick within a slot (mirrors `SupplementCombo.elegirPick` minus the brand
tiers, which have no analogue in hardware yet): compatible candidates →
affordable under the remaining budget (`presupuesto > 0`), else the cheapest
compatible one → rank `-baseMlScore` desc, `precio` asc, `url` asc.
`excluir` (URLs) removes already-shown picks, falling back to the full pool
per slot if exclusion would empty it — same as the supplement combo.

A slot with no candidate at all → `sinStock`; candidates exist but every one
is vetoed → `sinCompatible`. Both are reported, neither aborts the build.

Endpoint `GET /api/pcs/builder?presupuesto=0&conGpu=false&excluir=` → 
```json
{ "picks": [ { "slot", "sitio", "nombre", "precio", "url", "img", "marca",
               "specs": { "socket", "ddr", "formFactor", "watts", "capacidadGb", "tipoMemoria" } } ],
  "sinStock": [], "sinCompatible": [], "presupuesto": 0, "totalEstimado": 0 }
```
`204` without a loaded catalog, like `/api/suplementos/builder`.

TDD: **strict** (source: `.claude` orchestrator config). Runner:
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
(narrow with `-Dtest=PcBuilderTest` during the loop; full suite before close).

## Tasks

- [x] T1 `PcBuilderTest` RED: one test per veto row (fires / abstains on either side), DDR derivation from socket incl. LGA1700 abstaining, form-factor ordering, GPU opt-in flips the watts floor, budget filter + cheapest fallback, `excluir` fallback, `sinStock` vs `sinCompatible`, ranking order.
- [x] T2 `PcBuilder` GREEN (`PcPick`, `PcBuild` records; `RecommendationService` for `baseMlScore`).
- [x] T3 `PcsEndpoints` + `ApiController` mapping + `ApiRoutePolicy` row + `docs/openapi.yaml` + `ApiControllerPcsBuilderTest` (204 without catalog, 200 shape, params). `OpenApiRouteCoverageTest` green.
- [x] T4 Full backend suite green; `CLAUDE.md` "Armador de PCs" section updated to fase 2; evidence here.

## Acceptance

- Every veto has a test that fires it and a test that shows abstention on
  each side does not fire it.
- `OpenApiRouteCoverageTest` and `BackendLayeringArchTest` green with the new
  route and no new package dependency from `pcs` to `web` or `db`.

## Progress / evidence

**T1–T3 (2026-09-18).** RED: `PcBuilderTest` written against `PcBuilder`/
`PcBuild`/`PcPick` before they existed — narrow run fails at compilation
(`cannot find symbol`, ~40 hits, `BUILD FAILURE`). GREEN: `PcBuilderTest`
`Tests run: 29, Failures: 0`; `BackendLayeringArchTest` 19/19 (`pcs` stays a
sink, depends on `outfits` for `baseMlScore` — no cycle);
`PcBuilderTest,ApiControllerPcsBuilderTest,OpenApiRouteCoverageTest,BackendLayeringArchTest`
59/0/0; `RoutePolicyShadowingTest` 8/8 after the new policy row.

**T4 (2026-09-18).** Full backend suite (surefire aggregate):
`run=2164 fail=0 err=0 skip=7`, no `BUILD FAILURE` (2130 before this phase;
the 7 skips are the pre-existing infra-conditional ones). `CLAUDE.md`
"Armador de PCs" section covers fase 2 and points here.

### Deviations from the design (accepted)

- `PcBuilder`, `PcPick`, `PcBuild` are `public`: the endpoint lives in
  `web/`, so package-private would not compile. One public entry point.
- `PcBuilder` is a plain collaborator built in `ApiController`'s constructor
  from the injected `RecommendationService`, not a Spring bean — same as
  `SupplementCombo`.
- `docs/openapi.yaml` has no checked-in resource copy to sync: Maven copies
  it into `target/classes/contract` at build time.

**Next step (phase 3, not started):** frontend page on the mold of
`SuplementosPanel`; then `saved_pcs` and the agent tool `propose_pc` +
`POST /api/pcs`. Replace the watts floor with a per-GPU estimate once GPU
draw parses.
