# frontend-perf

## Objective
Make the catalog stop re-rendering every card on unrelated state changes, and
take framer-motion + Radix Dialog out of the entry chunk.

## Problem (verified 2026-09-28)
- `ProductCard` is `memo`, but `AppLayout.jsx:277-279` passes fresh arrow
  handlers and `ProductGrid.jsx:165` builds a per-card `onDelete` closure, so
  memo never short-circuits: up to 300 cards re-render on every dispatch
  (filter keystroke, favorite toggle, 1.8 s scrape-status poll).
- `AppLayout.jsx:15,18` eagerly imports `DetailPanel` (Radix Dialog) and
  `AgentChatPanel` (framer-motion). Entry chunk `index-*.js` = 576 KB.

## Scope
Frontend only. Out of scope: virtualization, duplicate `readStatus()`,
request caching (candidates for a follow-up once measured).

## TDD
Mode: strict, on (source: global CLAUDE.md "Strict TDD Mode: enabled").
Runner: Vitest (`cd frontend && npm test`). Jest is not added: Vitest is the
repo's runner and exposes the same API.

## Tasks
- [x] T1 Render-count test (RED): with React `<Profiler>` or a render spy,
      assert that a state change unrelated to a card (e.g. toggling favorito
      on product A) re-renders only card A, not the other N cards.
- [x] T2 Stabilize handlers (GREEN): `useCallback` in AppLayout; `onDelete`
      receives the product instead of a per-card closure.
- [x] T3 Lazy-load `DetailPanel` and `AgentChatPanel` with `React.lazy` +
      `Suspense`, mounted only when needed. Test: existing suite stays green.
      — one pre-existing test needs a decision, see Evidence.
- [x] T4 Measure: entry chunk size before/after (`npm run build`), render
      counts before/after from T1.

- [x] T5 Hand RootGate's status to AppLayout (router state) so a cold load
      to `/` drops the AppLayout-side re-read of `/api/status`. Direct `/catalogo`
      loads (no handed status) keep reading it. — see Evidence for the real
      before/after count (not the 2→1 assumed at task-writing time).
- [x] T6 Radix Dialog still in entry via `ui/sheet` (NavDrawer + CatalogoFilterBar,
      both eager). Measured its share first — not worth lazy-loading, see Evidence.

## Acceptance
- T1 test red on base, green after T2. ✅ (`src/components/ProductGrid.memo.test.jsx`)
- Entry chunk no longer contains framer-motion / Radix Dialog markers.
  Partially: DetailPanel's Radix Dialog and AgentChatPanel's framer-motion are
  both out (0 hits from those two), but 2 of the original 3
  `is-prop-valid|DismissableLayer` hits remain — traced to `ui/sheet.jsx`
  (also built on `@radix-ui/react-dialog`), pulled in eagerly by
  `CatalogoFilterBar.jsx`, which `CatalogoRoute` renders unconditionally.
  Out of scope for T3 (only named DetailPanel/AgentChatPanel); reported per
  instructions, not chased.
- Full `npm test` and `npm run build` green. `npm run build` is green.
  `npm test`: 383/384 green, 1 pre-existing failure — see Evidence.

## Evidence
Baseline: `dist/assets/index-DQrMOzuU.js` 576 KB (du), 3 hits for
`is-prop-valid|DismissableLayer`.

After T2+T3 (`VITE_API_BASE_URL=<placeholder> npm run build`):
- Entry chunk: `dist/assets/index-CGlTjMd8.js` — 258,982 B raw (du -b),
  81,152 B gzip. Baseline was ~589,824 B (576 KiB) raw → ~56% smaller.
- `grep -c "is-prop-valid|DismissableLayer" dist/assets/index-*.js` → 2
  (was 3); the remaining 2 are `ui/sheet.jsx` via `CatalogoFilterBar.jsx`
  (see Acceptance note above).
- `DetailPanel-*.js` (16.36 kB / 5.26 kB gzip) and `AgentChatPanel-*.js`
  (14.75 kB / 5.38 kB gzip) are now their own chunks, absent from entry.

Render counts (`src/components/ProductGrid.memo.test.jsx`, N=5 cards,
toggling favorito on card 3):
- RED (before T2, `ProductGrid.jsx:165`'s per-card `onDelete` closure):
  every card's render count went 1→2 on one card's favorito toggle (5 of 5
  re-rendered).
- GREEN (after T2): only the toggled card's count moved 1→2; the other 4
  stayed at 1 (1 of 5 re-rendered).
- Confirmed RED is caused by the real defect (not a test-harness artifact):
  temporarily reverted only `ProductGrid.jsx:165` with the harness otherwise
  unchanged (handlers `useCallback`-stabilized, matching AppLayout post-fix)
  → red again; restored → green.

`npm test` (full suite): 383 passed, 1 failed —
`App.test.jsx:95` ("an ADMIN sees ... the agent chat FAB") asserts
`screen.getByTitle('Ask Agent')` synchronously right after the `waitFor`
for "Catálogo". With `AgentChatPanel` now behind `React.lazy`+`Suspense`,
it mounts one tick later, so the synchronous query no longer finds it —
the FAB still appears for ADMIN, just asynchronously now. Fixing it means
changing that one assertion from `getByTitle` to `findByTitle` (awaited).
Per instructions, not edited here — this is a test-assertion change,
flagged for a decision rather than applied.

Resolution (2026-09-28): `App.test.jsx:95` changed to `await findByTitle`.
The assertion (ADMIN sees the FAB) is unchanged; only its timing, which the
intended lazy-load changes. Full suite after: 47 files, 384/384 green.
Spot check: with `ProductGrid.jsx` reverted, the memo test fails
(`expected 2 to be 1`); restored, it passes.

## Next step
Optional follow-up: `CatalogoFilterBar`'s eager `ui/sheet` (Radix Dialog),
duplicate `readStatus()`, grid virtualization. Measure in a real browser first.

## T5/T6 Evidence (2026-09-28)

### T5 — RootGate hands its status to AppLayout

RED test (`src/App.test.jsx`, describe "T5..."): counted `/api/status` fetches
on a cold load of `/` with `tieneData:true`. The task's assumed baseline
("today it's 2") undercounted a THIRD, independent reader: `Topbar.jsx:54`
(`fetchStatus()` inside a `Promise.all` for its own "ML banner" — unrelated to
AppLayout's data-loading gate, not named in T5's scope). Real baseline:
- Cold `/` with data: RootGate(1) + AppLayout(1) + Topbar(1) = **3**.
- Direct `/catalogo` (no RootGate involved): AppLayout(1) + Topbar(1) = 2.
- No-data path (`/` → splash): RootGate(1) + `SplashRoute`'s
  `useScrapeStatusPolling` mount read(1) = 2 — this is the pre-existing
  "duplicate readStatus()" the file's own Scope section already lists as out
  of scope; T5 leaves it untouched.

Fix: `App.jsx`'s `RootGate` now keeps the `status` it already read in state
and hands it through `<Navigate to="/catalogo" state={{ status }} replace/>`.
`AppLayout.jsx`'s mount effect checks `location.state` for a handed `status`
key; if present it skips its own `readStatus()` call and, in the same tick,
clears the state via `navigate(location.pathname+search+hash, {replace:true,
state:null})` — so it's consumed at most once. A direct `/catalogo` load (or
a refresh replaying the same history entry after that clear) always finds
`location.state` empty and reads status itself, same as before.

GREEN: cold `/` with data now makes exactly **2** `/api/status` calls
(RootGate + Topbar — AppLayout's own read is gone). Direct `/catalogo` and the
splash path are both unchanged at 2, confirmed by dedicated tests. Full
`npm test`: 47 files, 387/387 green (384 + 3 new T5 tests).

Files: `frontend/src/App.jsx`, `frontend/src/components/AppLayout.jsx`,
`frontend/src/App.test.jsx`.

### T6 — Radix Dialog share of `ui/sheet.jsx`

Measured before touching anything, per instructions. A naive stub-and-diff
(replacing `ui/sheet.jsx`'s Radix import with plain `<div>`s and rebuilding)
gave a **confounded** result — the entry chunk grew from 259 KB to 426 KB —
traced to the bundler's holistic automatic chunk-splitting: with `ui/sheet.jsx`
(eager) and `DetailPanel.jsx` (lazy) both importing `@radix-ui/react-dialog`,
several large shared vendor chunks (`tslib.es6-*` 133.84 kB, `utils-*` 20.17 kB,
`dist-*` 19.21 kB) exist ONLY because they're shared between a static and a
dynamic import boundary; removing that one shared edge changed the whole
graph's chunk boundaries and inlined those unrelated chunks into entry. That
delta is not attributable to Radix Dialog, so it was discarded and reverted
(`git diff` on `sheet.jsx` is empty).

Used the sourcemap-attribution method instead (also named in the
instructions): built once with `--sourcemap`, ran
`source-map-explorer dist/assets/index-*.js --no-border-checks [--gzip]`, and
attributed the entry chunk's 259,241 bytes to source files. Findings:
- Most of the Radix code already in entry (`@radix-ui/react-menu`,
  `react-dropdown-menu`, `react-menubar`, `@floating-ui/*` — tens of KB) comes
  from the **desktop nav** (`NavMenubar.jsx`/`UserMenu.jsx`), not from
  `ui/sheet.jsx`, and stays regardless of any Dialog change.
- `@radix-ui/react-dialog`'s dependencies (`react-focus-scope`,
  `react-dismissable-layer`, `react-portal`, `react-primitive`,
  `react-context`, `react-compose-refs`, `react-presence`, `react-id`,
  `react-use-controllable-state`) are the SAME top-level copies already
  required by `@radix-ui/react-menu` for the desktop nav — removing the Dialog
  import would not free them.
- What's actually exclusive to `@radix-ui/react-dialog`: its own module
  (4,034 B raw / 1,408 B gzip) plus `react-remove-scroll` + `aria-hidden`
  (Dialog-only deps, landed in the sourcemap's 4,567-byte unmapped bucket, a
  known esbuild/rolldown long-line sourcemap limitation).
- **Exclusive Radix Dialog share: ≈8.6 KB raw / ≈1.4–3 KB gzip** — even the
  generous upper bound of also moving `ui/sheet.jsx`'s own code (517 B gzip)
  and all of `NavDrawer.jsx` (648 B gzip) stays under 5 KB gzip total.
  `class-variance-authority` (594 B) is NOT exclusive either — `button.jsx`
  and `badge.jsx` also use `cva`.

**Decision: below the ~15 KB gzip bar — did not implement.** No source files
changed for T6.

Entry chunk, final (after T5, no T6 change):
`dist/assets/index-BiMARv0G.js` — 259,198 B raw (du -b), 81,235 B gzip.
(Was 258,982 B / 81,152 B before T5 — the tiny increase is T5's own added
logic, not a regression.)

## Verification (2026-09-28)
- `cd frontend && npm test`: 47 files, 387/387 passed.
- `cd frontend && VITE_API_BASE_URL=http://localhost:3000 npm run build`: green.
  Entry `dist/assets/index-BiMARv0G.js` — 259,198 B raw / 81,235 B gzip.
