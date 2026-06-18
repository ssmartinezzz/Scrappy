# Delta for Dashboard Navigation

No existing spec covers tab-bar or breadcrumb chrome, so this delta introduces the domain's first two requirements (ADDED only — nothing is modified or removed).

## ADDED Requirements

### Requirement: Outfits Tab Label

The tab bar MUST render a text label alongside the existing 👕 emoji for the Outfits tab (`/outfits` route), so the tab is identifiable by name without relying on emoji recognition. All other tabs in the bar remain icon-only; no other tab MUST be changed.

#### Scenario: User scans the tab bar

- GIVEN the dashboard is loaded with the tab bar visible
- WHEN the user looks at the 7th tab
- THEN it MUST display both the 👕 emoji and a readable text label (e.g. "Outfits")
- AND clicking it MUST navigate to `/outfits` exactly as before

#### Scenario: Outfits tab active state

- GIVEN the user is on the `/outfits` route
- WHEN the tab bar renders
- THEN the Outfits tab MUST show the `active` style, consistent with how other tabs indicate the active route

### Requirement: GymRat Topbar Shortcut

The Topbar breadcrumb row (Row 2) MUST conditionally render a GymRat shortcut chip that toggles the same `gymrat` filter state already controlled by the Sidebar's "Modo GYM" Pill, keeping both controls in sync via shared state.

#### Scenario: GymRat products exist

- GIVEN `facets.gymratCount > 0`
- WHEN the Topbar breadcrumb row renders
- THEN it MUST display a shortcut chip labeled with a count, e.g. `🏋️ Gym {gymratCount}`

#### Scenario: No GymRat products

- GIVEN `facets.gymratCount` is `0` or absent
- WHEN the Topbar breadcrumb row renders
- THEN the GymRat shortcut chip MUST NOT be rendered

#### Scenario: Toggling the shortcut activates the filter

- GIVEN the GymRat shortcut chip is visible and the `gymrat` filter is currently `false`
- WHEN the user clicks the chip
- THEN the shared `gymrat` filter state MUST become `true`
- AND the Sidebar "Modo GYM" Pill MUST reflect the same active state, since both read the same state

#### Scenario: Toggling the shortcut deactivates the filter

- GIVEN the GymRat shortcut chip is visible and the `gymrat` filter is currently `true`
- WHEN the user clicks the chip again
- THEN the shared `gymrat` filter state MUST become `false`
- AND the Sidebar Pill MUST reflect the same inactive state

#### Scenario: Sidebar Pill remains the source-equivalent control

- GIVEN the user toggles the GymRat filter from the Sidebar "Modo GYM" Pill instead of the Topbar chip
- WHEN the state changes
- THEN the Topbar shortcut chip's visual active/inactive state MUST update to match, since both bind to `S.gymrat`
