# Frontend Specification — gymrat-ipc-ml-ux

## Purpose

Specifies the visible dashboard behaviors added by this change: GYMRAT mode, IPC widget,
purchase-signal detail, ML badge coloring, ML transparency panel, and training toast.
All behaviors live in the single `resources/static/index.html` SPA.

## Requirements

### Requirement: GYMRAT Mode Toggle

The dashboard MUST expose a toggle button labeled "Modo GYM" that, when active, appends
`gymrat=true` to every `/api/data` fetch and restricts the product grid to gym-tagged items.

#### Scenario: Activate GYMRAT mode

- GIVEN the dashboard is showing products
- WHEN the user clicks "Modo GYM"
- THEN the grid refetches with `gymrat=true` and shows only gym-tagged products
- AND the toggle button renders in an active/highlighted state

#### Scenario: Deactivate GYMRAT mode

- GIVEN GYMRAT mode is active
- WHEN the user clicks "Modo GYM" again
- THEN the grid refetches without `gymrat=true` and shows all products
- AND the toggle button returns to its inactive state

#### Scenario: No gym products available

- GIVEN GYMRAT mode is active
- WHEN the API returns 0 products for `gymrat=true`
- THEN the grid shows an empty-state message (e.g., "No se encontraron productos gym")

---

### Requirement: GYMRAT Sub-Category Chips

When GYMRAT mode is active, the dashboard MUST display filter chips derived client-side
from `{categoria} + " Gym"` for each distinct gym-product category in the current result set.
Selecting a chip MUST further filter the grid to that sub-category.

#### Scenario: Sub-category chips rendered

- GIVEN GYMRAT mode is active and the API returned gym products across multiple categories
- WHEN the product grid is populated
- THEN chips appear for each sub-category (e.g., "Remera Gym", "Calza Gym") with item counts

#### Scenario: Sub-category chip selected

- GIVEN sub-category chips are visible
- WHEN the user selects "Short Gym"
- THEN the grid shows only products where `gymrat=true` AND `categoria == "Short"`

---

### Requirement: GYMRAT Badge on Product Cards

Product cards where `gymrat=true` MUST display a visual badge "Gym" (with a barbell/weight icon).
The badge MUST be positioned in the card's tag area, below the ML badge when both are present.

#### Scenario: Gym badge visible

- GIVEN a product with `gymrat=true` is rendered in the grid
- WHEN the card is displayed
- THEN a "Gym" badge is visible on the card

#### Scenario: No gym badge for non-gym products

- GIVEN a product with `gymrat=false`
- WHEN the card is displayed
- THEN no gym badge is shown

---

### Requirement: IPC Widget in Dashboard Header

The dashboard header MUST display a live IPC widget showing at minimum:
`IPC Mensual: X.X% | Interanual: Y% | Actualizado: {date}`.
The widget MUST fetch `/api/inflacion` once when the dashboard first renders.
While data is loading, the widget MUST show a placeholder (e.g., "IPC: cargando...").

#### Scenario: IPC data loads successfully

- GIVEN the dashboard is rendering for the first time
- WHEN `/api/inflacion` responds with valid data
- THEN the widget shows monthly rate, annual rate, and last-updated date

#### Scenario: IPC data unavailable

- GIVEN `/api/inflacion` returns an error or empty payload
- WHEN the widget attempts to render
- THEN it shows a graceful fallback (e.g., "IPC: no disponible") with no JS error thrown

---

### Requirement: Purchase Signal in Product Detail Panel

When the user opens a product detail panel, the frontend MUST fetch `/api/recomendacion?url={url}`
lazily (one request per product, cached by URL in a client-side Map). The panel MUST display:
signal emoji, signal label, purchase score bar (0–100), and real price change percentage.
If no sufficient history exists, the panel MUST display "Sin historial suficiente para analizar".

#### Scenario: Detail panel opens with history

- GIVEN a product with price history
- WHEN the user opens its detail panel
- THEN a fetch to `/api/recomendacion?url={url}` is triggered (or cache is hit)
- AND the panel shows emoji, signal label, score bar, and real change %

#### Scenario: Detail panel opens without history

- GIVEN a product with no price history
- WHEN `/api/recomendacion` responds with insufficient data
- THEN the panel shows "Sin historial suficiente para analizar" instead of the score bar

#### Scenario: Same product opened twice

- GIVEN a product detail was already opened (response cached)
- WHEN the user opens the same product again
- THEN no second fetch to `/api/recomendacion` is made

---

### Requirement: ML Badge Coloring on Product Cards

Every product card MUST render `ml.badge` as a colored pill using a fixed color map.
At most one ML badge MUST be shown per card (the most informative badge wins if multiple apply).

| Badge value | Color | Label |
|---|---|---|
| precio_historico_bajo | #0d7a3e (dark green) | "Minimo historico" |
| precio_bajo | #22c55e (green) | "Precio bajo" |
| oferta_real | #f97316 (orange) | "Oferta real" |
| tendencia | #3b82f6 (blue) | "Tendencia" |
| precio_bajando | #06b6d4 (cyan) | "Bajando" |
| precio_alto | #ef4444 (red) | "Precio alto" |
| descuento_cosmetico | #6b7280 (gray) | "Desc. cosmetico" |

#### Scenario: Badge rendered with correct color

- GIVEN a product card with `ml.badge = "oferta_real"`
- WHEN the card is rendered
- THEN an orange pill labeled "Oferta real" appears on the card

#### Scenario: Empty badge string

- GIVEN a product with `ml.badge = ""`
- WHEN the card is rendered
- THEN no ML badge pill is shown

---

### Requirement: ML Model Status Banner

The dashboard header MUST display an ML status banner sourced from `/api/ml/estado`.
When a model exists: show accuracy, number of trained categories, and last-run date.
When no model exists: show "ML: modo estadistico (sin modelo entrenado)".

#### Scenario: Model trained

- GIVEN `/api/ml/estado` returns `hasTextModel=true` with valid metadata
- WHEN the dashboard loads
- THEN the banner shows accuracy %, category count, and last-run date

#### Scenario: No trained model

- GIVEN `/api/ml/estado` returns `hasTextModel=false`
- WHEN the dashboard loads
- THEN the banner shows the "modo estadistico" fallback message

---

### Requirement: ML Transparency Panel in Tendencias

The Tendencias section MUST include a sub-panel "Que aprendio la IA" showing:
model accuracy (if available), number of trained classes, products refined in the last run,
and badge distribution (sourced from `/api/tendencias` badgeCounts field).

#### Scenario: Panel visible with model data

- GIVEN the Tendencias panel is open and `/api/ml/estado` returned model metadata
- WHEN the sub-panel renders
- THEN accuracy, class count, refined-product count, and badge distribution are shown

#### Scenario: Panel visible without trained model

- GIVEN `hasTextModel=false`
- WHEN the sub-panel renders
- THEN it shows "Entrenamiento no realizado — el sistema usa estadisticas de precio"

---

### Requirement: ML Training Completion Toast

While an ML training run is in progress, the frontend MUST poll `/api/ml/resultado` every 3 seconds.
On completion the frontend MUST display a success toast.
On failure the frontend MUST display a warning toast. Polling MUST stop after either outcome.

#### Scenario: Training completes successfully

- GIVEN an ML training run was triggered and is in progress
- WHEN `/api/ml/resultado` returns a completed status
- THEN a success toast appears (e.g., "Modelo ML actualizado — X categorias, Y% accuracy")
- AND polling stops

#### Scenario: Training fails

- GIVEN an ML training run is in progress
- WHEN `/api/ml/resultado` returns a failed status
- THEN a warning toast appears (e.g., "Entrenamiento ML fallo")
- AND polling stops

#### Scenario: No training in progress

- GIVEN no ML training was triggered in this session
- WHEN the dashboard is open
- THEN no polling to `/api/ml/resultado` occurs
