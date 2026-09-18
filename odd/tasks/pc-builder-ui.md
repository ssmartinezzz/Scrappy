# pc-builder-ui — phase 3 of the PC builder: the frontend page

**Created:** 2026-09-18 · **Branch:** `feat/pc-builder-ui` — **PR #203** (stacked on `feat/pc-builder`, #202 → #201 → #200 → `master`)
**Engram mirror:** `odd/pc-builder-ui/tasks` (project `scrappy`)
**Phase 2:** [`pc-builder.md`](./pc-builder.md) — the builder and `GET /api/pcs/builder` this page consumes.

## Objective

A `/pcs` page on the mold of `SuplementosPanel`: budget, GPU toggle,
Generar/Regenerar with per-slot exclusion, one card per pick with its parsed
specs, and honest placeholders for `sinStock` and `sinCompatible`.

## Scope (authorized)

Frontend only — the endpoint and `docs/openapi.yaml` are done in phase 2.

- `frontend/src/api.js`: `fetchPcsBuilder({ presupuesto=0, conGpu=false, excluir=[] })`
  mirroring `fetchSuplementosBuilder` (204/!ok → `null`; `conGpu` only in the
  query when true) + tests in `api.test.js`.
- `frontend/src/components/PcsPanel.jsx` + `PcsPanel.test.jsx`.
- Route: lazy import + `PcsRoute` wrapper in `AppLayout.jsx`, exported as
  `PcsPanelRoute`; `<Route path="pcs">` in `App.jsx`; one line in
  `nav/nav-config.js` (Explorar group, `Cpu` icon).
- `CLAUDE.md`: route list + "Armador de PCs" pending list.

Out of scope: `saved_pcs`, agent tool `propose_pc`, `POST /api/pcs`, splash
tile, e2e spec.

## Design

No type picker: slots are fixed server-side (`mother cpu ram gabinete fuente
[gpu] almacenamiento`). Controls: `MoneyInput` presupuesto + a `conGpu`
checkbox + Generar (`generar(false)`, resets `vistos`) / Regenerar
(`generar(true)`, sends `excluir = Object.values(vistos).flat()`). `vistos`
is keyed by `slot`, and a slot's history resets to `[url]` when the server
repeats a URL — same recycle rule as the supplement panel. Call sites use
`onClick={() => generar(false)}`, never `onClick={generar}`.

Card: `img` with `onError` hide + `Cpu` icon fallback, slot pill, name,
marca · sitio, `$${fmt(precio)}`, and a specs line built only from non-empty
fields (`socket`, `ddr`, `formFactor`, `watts`→"650 W", `capacidadGb`→"1000
GB", `tipoMemoria`). Total line uses `totalEstimado`. `sinStock` → dashed
"Sin stock" card; `sinCompatible` → dashed "Sin compatible" card with a short
"ninguna opción compatible con la mother elegida" hint. Empty state when
`picks` is `[]` and both lists are empty.

TDD: **strict** (source: `.claude` orchestrator config). Runner:
`cd frontend && npx vitest run src/components/PcsPanel.test.jsx src/api.test.js`
(full: `npm test`).

## Tasks

- [x] T1 `fetchPcsBuilder` RED→GREEN in `api.js` / `api.test.js` (path, `presupuesto` only when > 0, `conGpu` only when true, `excluir` comma-joined, 204 → null).
- [x] T2 `PcsPanel.test.jsx` RED: first Generar sends `excluir: []`; Regenerar sends shown URLs keyed by slot; accumulates; Generar resets; recycle resets only that slot; `conGpu` forwarded; `sinStock`/`sinCompatible` render distinct placeholders; specs line omits empty fields.
- [x] T3 `PcsPanel.jsx` GREEN.
- [x] T4 Route + nav + `CLAUDE.md`; full `npm test` and `npm run build` green; evidence here.

## Acceptance

- `npm test` green with the new tests; `npm run build` green.
- `api.contract.test.js` still green (no bare `fetch('/api/...')` in the panel).

## Progress / evidence

**T1** — RED (`npx vitest run src/api.test.js`): 6 new `fetchPcsBuilder` tests
failed with `TypeError: fetchPcsBuilder is not a function` (26 pre-existing
passed). GREEN after adding `fetchPcsBuilder` next to `fetchSuplementosBuilder`
in `api.js`: 32/32 passed.

Note: `api.test.js` has no `fetchSuplementosBuilder` describe block to mirror
(only `fetchSuplementosTipos` is tested there — the builder is exercised
indirectly through `SuplementosPanel.test.jsx`'s mocked `@/api`). Wrote the new
`fetchPcsBuilder` tests against the file's own `global.fetch` + `calledUrl()`
pattern (used by `fetchData`/`fetchSuplementosTipos`) instead.

**T2** — RED (`npx vitest run src/components/PcsPanel.test.jsx`): failed to
resolve import `@/components/PcsPanel` — `Does the file exist?` (module not
found, as expected pre-implementation).

**T3** — GREEN (`npx vitest run src/components/PcsPanel.test.jsx`): 10/10
passed on first implementation. Combined run
`npx vitest run src/api.test.js src/components/PcsPanel.test.jsx`: 42/42
passed.

Full `npm test`: 41 files / 298 tests passed.
`npx vitest run src/api.contract.test.js`: 4/4 passed (PcsPanel.jsx calls only
`fetchPcsBuilder` from `api.js`, no bare `fetch('/api/...')`).

Deviation: none from the T1–T3 design. `resumenSpecs` and `SLOT_LABELS` were
added as small local helpers (not in the mold, since SuplementosPanel has no
specs line) — both are pure, colocated in `PcsPanel.jsx`, no new files.

**T4 (2026-09-18).** Lazy `PcsRoute` in `AppLayout.jsx` (exported as
`PcsPanelRoute`), `<Route path="pcs">` in `App.jsx`, `{ label: 'PCs', to:
'/pcs', icon: Cpu }` in `nav-config.js`, `CLAUDE.md` route list + fase 3
paragraph. Spot check: `npx vitest run src/components/PcsPanel.test.jsx` 10/10.
Full: `npm test` → 41 files / 298 tests passed; `npm run build` → built in
8.53s. Visual check in Chrome with a `fetch` stub (no backend): 1280×900 and
390×800 — picks grid, `sinStock` (gpu) and `sinCompatible` (gabinete)
placeholders, total, Regenerar all render; no console errors from the app.

**Next step:** `saved_pcs` + `POST /api/pcs`, then agent tool `propose_pc`.
