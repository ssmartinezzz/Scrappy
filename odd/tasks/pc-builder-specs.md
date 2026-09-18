# pc-builder-specs — phase 1 of the PC builder: TechSpecs parser + catalog measurement

**Created:** 2026-09-18 · **Branch:** `feat/pc-techspecs` (from `master` e60edac)
**Engram mirror:** `odd/pc-builder-specs/tasks` (project `scrappy`)

## Objective

Give the catalog a way to reason about PC-part compatibility. Phase 1 delivers
only the pure parser (`TechSpecs` from `nombre` + `categoria`) and a measured
coverage report over the real catalog. No builder, no persistence, no `Product`
field, no endpoint — those are phase 2+, and whether they are worth building
depends on the numbers this phase produces.

## Problem

`Product` has 19 fields and none says socket, DDR generation, form factor or
watts. A PC builder modelled on `SupplementCombo` (the assembler that works)
needs those as HARD vetoes, or it pairs an AM5 CPU with an AM4 board and is as
"regular" as the outfit builder. The names carry the data (sampled 2026-09-18):
`Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5`, `Procesador Amd Ryzen 9
7900 Am5`, `Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz CL40`,
`Fuente Antec 750W 80 Plus Bronze ATX 3.1`, `Gabinete Cooler Master Masterbox
NR200P White Mini ITX`, `Motherboard MSI Z890 GAMING PLUS WIFI DDR5 1851`.

Catalog (dev DB, `productos`, 2026-09-18): Gabinete 613 · Motherboard 516 ·
Cooler 474 · GPU 452 · RAM 372 · Fuente 343 · Monitor 329 · CPU 308 ·
Almacenamiento 277.

## Scope (authorized)

- New area `ar.scraper.pcs` with `TechSpecs` (record, fill-only, `EMPTY`,
  `""`/0 = abstained — same policy as `Product.VisualAttrs`) and
  `TechSpecsParser` (pure, static, package-private not required: the phase-2
  builder and the agent tool live in the same area).
- Register `ar.scraper.pcs..` in `BackendLayeringArchTest.areasSonSumideros`.
- Unit tests, TDD (RED observed before GREEN).
- A throwaway measurement (NOT committed) over a CSV exported from the dev DB;
  its results go into this document and, at close, into `CLAUDE.md`.

Out of scope: `Product` field, DB column, builder, endpoint, agent tool,
frontend. Fixing classifier misfires seen in the sample (`PC Intel Core i3
14100…` under `CPU`, a CPU+cooler combo under `GPU`) — record them, do not fix.

## Design

```
pcs/
  TechSpecs.java        record(String socket, String ddr, String formFactor,
                               int watts, int capacidadGb, String tipoMemoria)
                        EMPTY; socket ∈ {AM4, AM5, LGA1700, LGA1851, ""}
                        ddr ∈ {DDR3, DDR4, DDR5, ""}
                        formFactor ∈ {ITX, MATX, ATX, EATX, ""}
                        tipoMemoria ∈ {DIMM, SODIMM, ""}
  TechSpecsParser.java  static TechSpecs parse(String nombre, String categoria)
```

Per-category rules (only fill what the category can assert; everything else
stays abstained):

| categoria | socket | ddr | formFactor | watts | capacidadGb | tipoMemoria |
|---|---|---|---|---|---|---|
| CPU | explicit `AM4/AM5/LGA1700/LGA1851/1700/1851`, else derived from model: Ryzen 7xxx/8xxx/9xxx → AM5, Ryzen 3xxx/4xxx/5xxx → AM4, Core i3/i5/i7/i9 12xxx-14xxx → LGA1700, Core Ultra 2xx → LGA1851 | — | — | — | — | — |
| Motherboard | explicit socket token, else chipset: A620/B650/B840/B850/X670/X870 → AM5; A520/B450/B550/X570 → AM4; H610/B660/B760/Z690/Z790 → LGA1700; H810/B860/Z890 → LGA1851 | `DDR[345]` | `ITX`/`-I` → ITX; chipset with `M` suffix (`B650M`) or `mATX`/`micro` → MATX; `E-ATX` → EATX; explicit `ATX` → ATX; else "" | — | — | — |
| RAM | — | `DDR[345]` | — | — | total GB (`64GB (2x32GB)` → 64; `2x16GB` alone → 32) | `SODIMM` → SODIMM, else DIMM |
| Fuente | — | — | — | `(\d{3,4})\s*w` | — | — |
| Gabinete | — | — | `Mini ITX`/`ITX` → ITX; `micro ATX`/`mATX`/`M-ATX` → MATX; `E-ATX`/`EATX` → EATX; `ATX` → ATX; else "" | — | — | — |
| GPU / Cooler / Almacenamiento / Monitor | abstain entirely in phase 1 | | | | | |

Normalize with `AccentStripper` + lowercase before matching; word-bounded
tokens (`" am5 "` padded, see CLAUDE.md "the space IS the word boundary") —
`LGA1851` is also written bare `1851`, which must not match inside a model
number like `B860M` or `RTX 5070 12GB`.

TDD: **strict** (source: `.claude` orchestrator config). Runner:
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
(narrow with `-Dtest=TechSpecsParserTest` during the loop; full suite before close).

## Tasks

- [x] T1 `TechSpecs` record + `TechSpecsParserTest` RED (one test per row of the table above, plus abstention cases and the `1851`-inside-a-model negative).
- [x] T2 `TechSpecsParser` GREEN; register the area in `areasSonSumideros`.
- [x] T3 Measure: export `categoria,sitio,nombre` for the 6 parsed categories from the dev DB, run the parser over every row, report per category+field: filled / total / %, and the 10 most common unparsed names per field. Record here.
- [x] T4 Full backend suite green; findings + numbers in this file; `CLAUDE.md` gets a short "PC builder" section pointing here.

## Acceptance

- Parser is pure, no I/O, abstains rather than guesses; every rule in the
  table has a test naming a real product form.
- Coverage table exists with real numbers; the unparsed top-10 per field is
  recorded so phase 2 can decide which gaps are worth closing.

## Progress / evidence

**T1–T3 (2026-09-18).** RED: `TechSpecsParserTest` written against the record
only — first run fails at compilation (parser class missing). GREEN:
`Tests run: 49, Failures: 0` in `TechSpecsParserTest`; `BackendLayeringArchTest`
19/19 with `ar.scraper.pcs..` registered. The writer session was cut by a
rate limit after the measurement; the orchestrator verified the files and
logs and recorded the evidence here.

**T4 (2026-09-18).** Full backend suite: `run=2130 fail=0 err=0 skip=7`
(surefire aggregate; skips are the pre-existing infra-conditional ones), exit 0.
`CLAUDE.md` got the "Armador de PCs — fase 1" section plus `odd/tasks/` and
`pcs/` in the file tree. Not committed — the user decides.

**Next step (phase 2, not started):** `PcBuilder` with hard vetoes where both
sides are known (CPU↔Mother socket, RAM↔Mother DDR, PSU watts ≥ estimated
draw), abstention = no veto; derive Mother DDR from AM4/AM5/LGA1851 chipsets;
decide whether `TechSpecs` rides on `Product` or is computed at build time
from the in-memory snapshot (it is pure and cheap, so the latter needs no
migration).

### Deviations from the design table (accepted)

- **Tokenizer instead of padded substrings.** The parser splits on every
  non-alphanumeric run and compares tokens whole, so `1851` can never match
  inside `B860M`; `B850M-E` becomes `b850m` + `e`.
- **Chipset suffixes are letters, not just `M`.** `X670E`/`X870E`/`B650E`
  (Extreme tier) and compounds `B650EM`/`A620AM` exist in the catalog; a
  naive 4-or-5-char match missed every one. Suffix containing `i` → ITX,
  containing `m` → MATX.
- **A recognized chipset with no size suffix and no explicit keyword defaults
  to `ATX`** (the table said abstain). Full-size is the modal form for a bare
  chipset name and no counter-example was found in 519 rows; the measured
  formFactor coverage for Motherboard (97.9%) depends on it.
- `tipoMemoria` defaults to `DIMM` whenever the row is a RAM (table said so;
  worth remembering it is a default, not an observation).

### Coverage over the dev catalog (2157 rows, 5 categories, 2026-09-18)

| categoria | rows | field | filled | % |
|---|---|---|---|---|
| CPU | 310 | socket | 252 | 81.3 |
| Motherboard | 519 | socket | 508 | 97.9 |
| Motherboard | 519 | ddr | 405 | 78.0 |
| Motherboard | 519 | formFactor | 508 | 97.9 |
| RAM | 372 | ddr | 367 | 98.7 |
| RAM | 372 | capacidadGb | 372 | 100 |
| RAM | 372 | tipoMemoria | 372 | 100 (default) |
| Fuente | 343 | watts | 336 | 98.0 |
| Gabinete | 613 | formFactor | 43 | **7.0** |

### What the unparsed rows are

- **CPU socket 58 misses are mostly not CPUs**: DDR5/DDR4 memory modules
  carrying "AMD EXPO & Intel XMP" sit in `CPU` (classifier misfire —
  `intel`/`amd` keywords, record for the taxonomy, not for the parser), a
  Lenovo Legion Go console, and legitimately old parts (`i3-10100F S.1200`).
- **Motherboard socket 11 misses**: all `LGA1200`/`S1200`/`LGA2011` boards
  (H370/H510/B560/X99) — sockets outside phase-1 scope — plus two sweaters
  named "Canguro Mother" classified as motherboards (classifier misfire).
- **Motherboard ddr 114 misses**: the name states socket but not DDR
  (`Motherboard MSI B550M A Pro AM4`, `Mother MSI PRO H810M-E`). Phase 2 can
  derive DDR from chipset when it is unambiguous: AM5 and LGA1851 chipsets
  are DDR5-only; AM4 is DDR4-only; LGA1700 chipsets are genuinely mixed and
  must stay abstained.
- **RAM ddr 5 misses**: typo `Dddr4`, a DDR2 module, and names with no DDR
  token at all.
- **Fuente watts 7 misses**: watts only inside a model code (`Jt-450`,
  `Snp550-gs`, `Tuf 650b`), a PoE injector, and two racing wheels whose
  name contains "Fuente de Origen Nacional" (classifier misfire).
- **Gabinete formFactor 570 misses**: the name almost never says the form
  factor. `Mid-tower`/`Full tower` appear sometimes and would map to
  ATX/EATX; the rest needs a source other than the name (site breadcrumbs or
  a brand/model table). **This is the number that shapes phase 2**: the
  Gabinete ⊇ Motherboard veto cannot run on names alone; the builder should
  treat an abstained case form factor as "no veto" (same policy as
  `VisualCoherence`) and only veto when both sides are known.

### Classifier misfires seen (not fixed here)

RAM modules under `CPU` (~10, "AMD EXPO / Intel XMP"), `Sweater Canguro
Mother` ×2 under `Motherboard`, Logitech racing wheels under `Fuente`
("Fuente de Origen Nacional"), pre-built `PC ...` rows under `CPU`/`Gabinete`,
a CPU+watercooler combo under `GPU`, a dust filter under `Gabinete`.
