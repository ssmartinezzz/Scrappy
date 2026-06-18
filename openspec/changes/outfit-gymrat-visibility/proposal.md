# Proposal: Outfit tab + GymRat breadcrumb visibility

## Intent

Two existing features are effectively hidden in the dashboard. (1) The Outfit picker (`/outfits`, `OutfitsPanel`) is the 7th tab in an icon-only tab bar (🛍 🏆 🏷 ⚖ 📈 ⭐ 👕); its bare 👕 emoji is indistinguishable from the other tabs and the product owner cannot find it. (2) The GymRat filter exists only as a `Pill` buried inside the Sidebar's "🏋️ Modo GYM" section, so users have no high-level shortcut to toggle it. This change makes both discoverable with minimal, contained UI tweaks — no new behavior, data, or dependencies.

## Scope

### In Scope
- Add a visible text label next to the Outfits tab emoji in the tab bar (e.g. `👕 Outfits`), so it reads as a named tab.
- Add a GymRat shortcut button to the Topbar breadcrumb row (Row 2, site filter chips), conditionally rendered only when `facets.gymratCount > 0`.
- The button toggles the SAME `gymrat` boolean state AppLayout already manages (`S.gymrat` via `setFilter`); it shows a count badge like the existing site chips (`🏋️ Gym <count>`) using the existing lime green `#84cc16`, solid color, no animation.
- Thread `gymrat` state and an `onGymratToggle` callback from `AppLayout.jsx` into `Topbar.jsx` (currently not passed); read `facets.gymratCount` (already on the `facets` prop).

### Out of Scope
- Removing or changing the existing Sidebar "Modo GYM" section — it stays exactly as is.
- Redesigning the tab bar or breadcrumb row beyond these two additions.
- Repositioning the Outfits tab, adding color/badge/animation to it, or reusing the ML-training pulse animation.
- Any backend, API, DB, or `ml_pipeline.py` change. No new npm dependencies.

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- None (pure frontend presentation change; no spec-level requirement or contract changes to `outfit-builder` or `outfit-feedback`).

## Approach

- **Outfits tab**: add a text label beside the 👕 emoji in the `NavLink` tab bar in `AppLayout.jsx` (~L354-364). Cosmetic only — same route, same component, same position.
- **GymRat shortcut**: in `Topbar.jsx` Row 2 (~L163-198), render an extra chip button when `facets.gymratCount > 0`. Wire a new `gymrat` prop + `onGymratToggle` callback from `AppLayout.jsx` (~L327-335) backed by existing `S.gymrat`/`setFilter({ gymrat: !S.gymrat })`. Active/inactive state reuses lime `#84cc16`; badge shows `facets.gymratCount`. The Sidebar Pill and the Topbar button drive the same reducer state, so they stay in sync automatically.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `frontend/src/components/AppLayout.jsx` | Modified | Outfits tab label; pass `gymrat` + `onGymratToggle` to Topbar |
| `frontend/src/components/Topbar.jsx` | Modified | New GymRat shortcut chip in breadcrumb row; accept new props |
| `frontend/src/components/Sidebar.jsx` | Unchanged | Existing "Modo GYM" Pill kept as-is |
| `scraper/src/main/resources/static/` | Rebuilt | Vite build output (no manual edit) |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Outfits label overflows narrow tab bar | Low | Short label; verify responsive width vs other tabs |
| Topbar/Sidebar GymRat state drift | Low | Both bind the same `S.gymrat` reducer state — single source of truth |
| Stale Vite build served | Low | Rebuild `frontend` into `static/` and reload |

## Rollback Plan

Revert `AppLayout.jsx` and `Topbar.jsx` (two-file diff), rebuild the frontend (`npm run build` in `frontend/`, output to `scraper/src/main/resources/static/`). No data, schema, or API migration to undo.

## Dependencies

- Existing reducer state `S.gymrat` and `setFilter` in `AppLayout.jsx`.
- Existing `facets.gymratCount` already supplied to `Topbar`/`Sidebar`.

## Success Criteria

- [ ] The Outfits tab shows a readable text label and is findable without inspecting code.
- [ ] A `🏋️ Gym <count>` shortcut appears in the Topbar breadcrumb row only when `gymratCount > 0`.
- [ ] Clicking the Topbar shortcut toggles the same filter as the Sidebar Pill; both reflect the same state.
- [ ] The Sidebar "Modo GYM" section is unchanged; no new dependencies or backend changes.
