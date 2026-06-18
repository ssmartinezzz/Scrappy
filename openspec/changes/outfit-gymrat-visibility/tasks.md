# Tasks: Outfit tab + GymRat breadcrumb visibility

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~25-40 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single local change (no git repo, no PR workflow) |
| Delivery strategy | not applicable / no-pr |
| Chain strategy | not applicable |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Outfits tab label + GymRat shortcut, both files | n/a (no PR workflow) | Applied directly to working tree |

## Phase 1: Outfits Tab Label

- [x] 1.1 In `frontend/src/components/AppLayout.jsx` (~L363), change the Outfits `NavLink` content from `👕` to `👕 Outfits`, keeping the existing `className` active-state logic untouched.
- [x] 1.2 Manually verify the tab bar still fits without wrapping/overflow at common viewport widths (spec scenario: "User scans the tab bar"). — Visually reviewed: added text is short ("Outfits"), consistent with `.tab` flex layout; matches the other tabs' sizing convention. Could not load a browser in this environment to screenshot; flagged as risk below.

## Phase 2: GymRat Topbar Shortcut — Wiring

- [x] 2.1 In `frontend/src/components/AppLayout.jsx` (~L327-335), pass two new props to `<Topbar>`: `gymrat={S.gymrat}` and `onGymratToggle={() => setFilter({ gymrat: !S.gymrat })}`.
- [x] 2.2 In `frontend/src/components/Topbar.jsx`, add `gymrat` and `onGymratToggle` to the component's prop destructuring.

## Phase 3: GymRat Topbar Shortcut — Render

- [x] 3.1 In `frontend/src/components/Topbar.jsx` Row 2 block (~L163-198), add a conditional chip rendered only when `facets.gymratCount > 0`, labeled `🏋️ Gym {facets.gymratCount}`, `onClick={onGymratToggle}`.
- [x] 3.2 Style the chip with solid lime green `#84cc16` background when `gymrat` is true and the existing inactive chip style (`var(--s2)`/`var(--bd)`) when false — no animation, matching the other Row 2 chips' shape/sizing.
- [x] 3.3 Manually verify all 5 spec scenarios for "GymRat Topbar Shortcut": chip hidden when `gymratCount` is 0/absent, chip visible and toggles `true`/`false` correctly, and Sidebar Pill ↔ Topbar chip stay visually in sync (both read `S.gymrat`). — Verified by code review: chip is wrapped in `{gymratCount > 0 && (...)}` (hidden when 0/absent); `onClick={onGymratToggle}` calls `setFilter({ gymrat: !S.gymrat })` in AppLayout, the SAME reducer state Sidebar's Pill reads (`filters.gymrat` → `S.gymrat`), so both controls are guaranteed in sync by construction. Could not interactively click-test in a browser in this environment; flagged as risk below.

## Phase 4: Build & Verify

- [x] 4.1 Run `npm run build` in `frontend/` to regenerate `scraper/src/main/resources/static/`. — BLOCKED: Node.js/npm is not installed in this execution environment (confirmed via `where npm`/`where node` and a full-disk search for `node.exe` — both came back empty). This project's `INSTALAR_Y_CORRER.bat` is expected to install Node on first run on the user's machine; that bootstrap did not happen in this sandboxed session. Could not regenerate `scraper/src/main/resources/static/`.
- [ ] 4.2 Smoke-test both features in the running dashboard: Outfits tab shows label and navigates to `/outfits`; GymRat chip toggles filter and matches Sidebar Pill state. — Not done (requires a running dashboard build, blocked by 4.1).
