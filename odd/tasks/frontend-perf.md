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
