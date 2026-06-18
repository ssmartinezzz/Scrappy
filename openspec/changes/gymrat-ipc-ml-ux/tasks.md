# Tasks: GYMRAT UI + IPC Buy Signal + ML Transparency

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~300–400 (index.html bulk, NormalizerService minor) |
| 400-line budget risk | Medium |
| Chained PRs recommended | No |
| Suggested split | Single PR (2 files only, no backend contract change) |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Medium

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | All 17 implementation tasks + build | PR 1 | Self-contained; 2 files; no migration |

---

## Phase 1: Backend Vocabulary Widening

- [x] T01 — `NormalizerService.java` (lines 382–388): Append to `KW_TRAINING_ROPA` the fitness/pesas keywords listed in the design (gimnasio, functional, dri-fit, dry-fit, compression, compresion, performance, athletic, activewear, active wear, para entrenar, de entrenamiento, para el gym, uso deportivo, halterofilia, spinning, cardio gym, musculosa de gym, remera de gym, short deportivo, short de gym, calza de gym, buzo de entrenamiento, top de gym). Do NOT add bare "yoga" or "pilates". Verify calzado guard (line 524) and suplementos guard (line 525) are untouched.

---

## Phase 2: CSS Foundation

- [x] T02 — `styles.css`: Add CSS rules for `.ipc-widget`, `.ml-banner`, `.badge-gymrat`, `.gym-subcats`, `.subcat-chip`, `.ml-aprendizaje-panel`, `.badge-stats`, `.badge-stat`, `.toast`, `.toast-success`, `.toast-error`, and `@keyframes toastIn`. (Note: project uses React/Vite with `styles.css`, not a vanilla `index.html` — equivalent rules added to the correct file.)

---

## Phase 3: Badge Rendering (JS constants + render functions)

- [x] T03 — `api.js` already exports `BADGE_LABELS` with all 7 badge entries. Used throughout the React codebase.
- [x] T04 — `ProductCard.jsx` already renders ML badge via `BADGE_LABELS[ml.badge]` with styled `badge-ml badge-{badge}` classes.
- [x] T05 — `ProductCard.jsx`: Added `gymSubcat(product)` function — returns `product.categoria + ' Gym'` when `product.gymrat === true`, else `null`.
- [x] T06 — `ProductCard.jsx`: Renders lime-green `.badge-gymrat` pill using `gymSubcat(p)` in the card body.

---

## Phase 4: Card Integration

- [x] T07 — `ProductCard.jsx`: ML badge renders first via existing `badge-ml badge-{badge}` span; GYMRAT `.badge-gymrat` pill renders second using `gymSubcat(p)`. Both added to card body in correct priority order.

---

## Phase 5: Header Widgets (IPC + ML Banner)

- [x] T08 — `Topbar.jsx`: Added `ipcData` state loaded via `fetch('/api/inflacion')` on mount; renders `.ipc-widget` pill with mensual/anual when data is available; hidden when fetch fails or data is null.
- [x] T09 — `Topbar.jsx`: Added `mlBanner` state loaded via `Promise.all([fetchStatus, fetchMlEstado])` on mount; renders `.ml-banner` pill showing accuracy + refinadas when `hasTextModel && textMeta`, else "ML estadístico".
- [x] T10 — `Topbar.jsx`: IPC widget div and ML banner div rendered inline in Row 1 of the topbar.
- [x] T11 — `Topbar.jsx`: Both widgets load on component mount (equivalent to dashboard-load trigger), with defensive error handling per ADR-5.

---

## Phase 6: GYM Mode Filter

- [x] T12 — `AppLayout.jsx` + `Sidebar.jsx`: `gymrat` boolean state already in reducer; `gymSubcats` map derived in `APPEND_PRODS` reducer case; `gymSubcatFiltro` client-side filter added. Sidebar renders subcat chips when `filters.gymrat` is active.
- [x] T13 — `Sidebar.jsx`: "🏋️ Modo GYM" section with gym pill and subcat chips added. Existing gymrat Pill reused with green color; subcat chips render below when gymrat is active.
- [x] T14 — `AppLayout.jsx`: `buildParams` already includes `gymrat: true` when active. `CatalogoRoute` applies `gymSubcatFiltro` client-side after fetch, disabling infinite scroll while subcat filter is active.

---

## Phase 7: Product Detail — Buy Signal

- [x] T15 — `BuySignal.jsx`: Already fully implements buy signal with `recomCache`-equivalent (via React state), "Analizando..." loading state, `sin_datos` fallback, emoji+mensaje+scoreCompra bar, cambioReal/IPC line. All fields optional-chained per ADR-5.
- [x] T16 — `DetailPanel.jsx`: `<BuySignal url={p.url}/>` already inserted inside the detail panel body.
- [x] T17 — `DetailPanel.jsx`: BuySignal loads automatically on `useEffect([p.url])` when the detail panel opens — equivalent to calling `loadRecomendacion(product.url)` on open.

---

## Phase 8: ML Transparency Panel + Training Toast

- [x] T18 — `TrendsPanel.jsx`: Added `MlAprendizaje` component that fetches `/api/ml/estado` on mount; renders accuracy/clases when `hasTextModel && textMeta`, else statistical-mode fallback. Badge distribution pulled from `badges` prop (already available from `/api/tendencias`). 204/503 handling already in `fetchTendencias` in `api.js`.
- [x] T19 — `TrendsPanel.jsx`: `<MlAprendizaje badges={badges} total={total}/>` inserted at bottom of Mercado tab, using `.ml-aprendizaje-panel` CSS class.
- [x] T20 — `TrendsPanel.jsx`: `MlAprendizaje` renders inside the TrendsPanel which already calls `fetchTendencias()` on mount — equivalent to calling `loadMlAprendizaje()` after tendencias data loads.
- [x] T21 — `MlStatusPanel.jsx`: Added `showToast(msg, type)` helper that creates `.toast .toast-success/.toast-error` element, appends to body, auto-removes after 4.7s with fade-out.
- [x] T22 — `MlStatusPanel.jsx`: Training poll effect now also calls `fetchMlResultado()` every 2s alongside `fetchMlEstado()`; on `done` calls `showToast()` with success/error branch per ADR-6; `clearInterval` on all terminal states.

---

## Phase 9: Build

- [x] T23 — `mvn clean package -DskipTests` ran successfully with JAVA_HOME set to bundled JDK21. Fat JAR generated at `scraper/target/fashion-scraper-1.0.0.jar`. BUILD SUCCESS in 16.6s.
