# Apply Progress: Outfit tab + GymRat breadcrumb visibility

**Mode**: Standard (TDD disabled for this project — no test infra configured)
**Batch**: 1 of 1 (no prior apply-progress existed)

## Completed Tasks

- [x] 1.1 Outfits `NavLink` now renders `👕 Outfits` instead of bare `👕` in `AppLayout.jsx` tab bar.
- [x] 1.2 Manual review of label width vs other tabs — consistent with existing `.tab` styling, no wrap risk visible in code/CSS review.
- [x] 2.1 `AppLayout.jsx` passes `gymrat={S.gymrat}` and `onGymratToggle={() => setFilter({ gymrat: !S.gymrat })}` to `<Topbar>`.
- [x] 2.2 `Topbar.jsx` destructures `gymrat` and `onGymratToggle` from props.
- [x] 3.1 `Topbar.jsx` Row 2 renders a `🏋️ Gym {count}` chip, guarded by `gymratCount > 0` (derived from `facets?.gymratCount || 0`, same pattern Sidebar already uses).
- [x] 3.2 Chip styled solid lime `#84cc16` + white text when `gymrat` is true; `var(--s2)` background + `var(--t4)` text when false. No animation. Matches the shape/sizing (`padding:'2px 9px'`, `borderRadius:12`, `fontSize:'.67rem'`) of the adjacent "Todas" and sitio chips in the same row.
- [x] 3.3 Code-level verification of all 5 GymRat spec scenarios (see below).

## Files Changed

| File | Action | What Was Done |
|------|--------|----------------|
| `frontend/src/components/AppLayout.jsx` | Modified | Outfits tab label (`👕` → `👕 Outfits`); pass `gymrat` + `onGymratToggle` props to `<Topbar>` |
| `frontend/src/components/Topbar.jsx` | Modified | Accept `gymrat`/`onGymratToggle` props; compute `gymratCount` from `facets?.gymratCount`; render conditional GymRat shortcut chip in Row 2 breadcrumb area |

## Spec Scenario Verification (code review, no browser available)

| Scenario | Result |
|----------|--------|
| User scans the tab bar → Outfits shows emoji + label, same route | Pass — `<NavLink to="/outfits" ...>👕 Outfits</NavLink>`, route/className logic untouched |
| Outfits tab active state | Pass — `className={({isActive}) => \`tab ${isActive?'active':''}\`}` unchanged |
| GymRat products exist → chip shows `🏋️ Gym {count}` | Pass — `{gymratCount > 0 && (<button>...🏋️ Gym <span>{count}</span></button>)}` |
| No GymRat products → chip not rendered | Pass — same `gymratCount > 0` guard, short-circuits to `false` (no render) |
| Toggling chip activates filter | Pass — `onClick={onGymratToggle}` → `setFilter({ gymrat: !S.gymrat })` (resets pagination, same as Sidebar Pill's `onFilter({ gymrat: !filters.gymrat })`) |
| Toggling chip deactivates filter | Pass — same toggle is symmetric (`!S.gymrat`) |
| Sidebar Pill ↔ Topbar chip stay in sync | Pass — both read from the single `S.gymrat` reducer field; Sidebar reads via `filters.gymrat` prop, Topbar reads via `gymrat` prop, both sourced from the same `AppLayout` state |

## Deviations from Design

None — implementation matches design and tasks.md exactly. One minor structural note (not a deviation, informational): the GymRat chip lives inside the `{sitios.length > 0 && (...)}` Row 2 block, per the design's explicit placement instruction ("Row 2, site filter chips"). If `gymratCount > 0` but `sitios.length === 0` (no scraped sites with products), the chip would not render. This edge case is not covered by the spec scenarios (which only condition on `gymratCount`) and is extremely unlikely in practice — gymrat products only exist after sites have been scraped. Flagged here for visibility, not treated as a defect.

## Issues Found / Risks

1. **Build verification blocked**: Node.js/npm is not installed in this execution sandbox (`where npm`, `where node`, and a full-disk search for `node.exe` all returned empty). Could not run `npm run build` in `frontend/` to regenerate `scraper/src/main/resources/static/`, and could not visually/interactively smoke-test the dashboard. This environment differs from the user's actual machine, where `INSTALAR_Y_CORRER.bat` installs Node on first run.
   - **Action needed**: the user (or a session with Node available) must run `cd frontend && npm run build` and do a quick visual/click check before this change is considered fully verified.
2. No other issues. No backend, API, DB, or `ml_pipeline.py` changes were made, matching the proposal's Out of Scope section.

## Remaining Tasks

- [ ] 4.1 — blocked by missing Node.js in this sandbox; needs to run on a machine with Node installed.
- [ ] 4.2 — blocked by 4.1 (requires a built/running dashboard).

## Workload / PR Boundary

- Mode: single local change (no git repo, no PR workflow) — per tasks.md Review Workload Forecast (~25-40 lines, Low risk, no chaining needed).
- Current work unit: Unit 1 — "Outfits tab label + GymRat shortcut, both files" — fully applied to the working tree.
- Boundary: starts and ends with the two-file diff (`AppLayout.jsx`, `Topbar.jsx`). No rollback complexity — revert both files.

## Status

6/8 tasks complete (all code tasks done; build + smoke-test tasks blocked by missing Node.js in this environment). Code changes are complete and ready for the user to build/verify on their own machine, or for `sdd-verify` to inspect statically.
