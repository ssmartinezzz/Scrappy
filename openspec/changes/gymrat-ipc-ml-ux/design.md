# Technical Design: GYMRAT UI + IPC Buy Signal + ML Transparency

## 1. Architectural Approach

### Guiding principle: surface, don't recompute

The backend already calculates everything this change needs to display:

- GYMRAT classification (`Product.gymrat`, computed in `NormalizerService.esGymrat`).
- Buy signal adjusted by inflation (`/api/recomendacion`).
- Current inflation (`/api/inflacion`).
- ML model state and badges (`/api/ml/estado`, `MlScore.badge` per product).

The chosen architecture is therefore a **presentation-only expansion** of the existing vanilla SPA, plus a **single backend vocabulary widening** in `NormalizerService`. No new endpoints, no new SQLite tables, no schema/contract migration, no new dependency. This keeps the blast radius minimal and the rollback trivial (revert two files).

### Layering and boundaries

```
┌─────────────────────────────────────────────────────────────┐
│ Presentation  index.html (HTML + CSS + vanilla JS)           │
│   - render functions (cards, badges, widgets, panels)        │
│   - lazy client-side fetch + in-memory cache (recomCache)    │
│   - derived GYMRAT sub-label (computed in JS, not stored)    │
└───────────────▲─────────────────────────────────────────────┘
                │ HTTP (existing endpoints, unchanged contract)
┌───────────────┴─────────────────────────────────────────────┐
│ API           ApiController (UNCHANGED)                       │
│   /api/data /api/recomendacion /api/inflacion                │
│   /api/ml/estado /api/ml/resultado /api/status /api/tendencias│
└───────────────▲─────────────────────────────────────────────┘
                │
┌───────────────┴─────────────────────────────────────────────┐
│ Domain        NormalizerService (esGymrat keyword widening)   │
│               InflacionService (UNCHANGED — IPC sources)      │
│               PythonRunner / MlEnricher (UNCHANGED)           │
└──────────────────────────────────────────────────────────────┘
```

The boundary that matters: **the API contract does not change**. The GYMRAT sub-label is a pure projection over two fields already serialized in every `/api/data` product (`gymrat` and `categoria`). That is why it lives in the presentation layer.

## 2. Architecture Decision Records

### ADR-1: GYMRAT sub-label is derived in the frontend, not stored

**Decision:** Compute `gymSubcat(product)` in JS as `product.categoria + ' Gym'` (fallback `'Ropa Gym'`) when `product.gymrat === true`. Do not add a `gymSubcat` field to the `Product` record.

**Rationale:**
- The sub-label is a deterministic function of two already-serialized fields. Storing it would duplicate state and create a sync hazard.
- Adding a record field touches `Product.java`, `NormalizerService` construction, the DB upsert mapping, and the JSON serializer — a large surface for zero added information.
- Verified: `Product` (lines 5-19) already exposes `categoria` and `gymrat`; both are serialized in `/api/data`.

**Rejected alternative — add `gymSubcat` to `Product`:** Rejected. It forces a DB column or recomputation on load, a contract change, and a migration, all to encode information the client can derive in one line. Violates YAGNI and the "surface, don't recompute" principle.

**Consequence:** The sub-label taxonomy lives only in the frontend. If a canonical server-side taxonomy is ever needed (e.g. for `/api/facets`), it must be promoted to the backend then — but that is out of scope here.

### ADR-2: `/api/recomendacion` is fetched lazily on detail open, cached client-side

**Decision:** Call `/api/recomendacion?url=` only when the user opens a product's detail panel. Cache responses in an in-memory `Map` (`recomCache`) keyed by URL for the session.

**Rationale:**
- The endpoint runs a per-product historical computation (verified at lines 585-649: sorts history, computes min/max, inflation-adjusts, derives signal). Calling it for every card in a paginated grid would be N requests per page with heavy per-request work.
- The buy signal is only meaningful in the detail context where the user is deciding to buy.

**Rejected alternative — eager batch enrichment in `/api/data`:** Rejected. It would change the `/api/data` contract, add latency to the main grid load, and compute signals the user never looks at. Contradicts the lazy/on-demand risk mitigation already in the proposal.

**Consequence:** First detail open shows an "Analizando..." state; subsequent opens of the same product are instant from cache. Cache is session-scoped (lost on reload) — acceptable since prices change between runs anyway.

### ADR-3: Mode GYM is a frontend filter toggle reusing `/api/data?gymrat=true`

**Decision:** A toggle button sets `modoGymActivo`; when active, `buildFetchUrl()` appends `&gymrat=true` and the result set drives dynamically-derived sub-category chips.

**Rationale:**
- `/api/data` already accepts and filters by `gymrat=true` (confirmed in proposal; the param is wired server-side).
- Sub-category chips are derived from the returned products (group by `categoria + ' Gym'`), so they always reflect the real current result — no stale config.

**Rejected alternative — dedicated `/api/gymrat` endpoint with server-grouped sub-categories:** Rejected. Duplicates `/api/data` logic and pushes the derived taxonomy (ADR-1) into the backend for no gain.

### ADR-4: `KW_TRAINING_ROPA` widening stays in Java, hardcoded

**Decision:** Append the new fitness/pesas vocabulary to the existing `KW_TRAINING_ROPA` array (lines 382-388). Do not externalize to config.

**Rationale:**
- The array is a small, code-reviewed classification asset that ships with the JAR and is consumed only by `esGymrat`. Externalizing adds a config-loading path and a failure mode for no operational benefit (these terms change rarely).
- Keeping it in code means the change is covered by the same build and review as the classifier logic.

**Guard preserved:** Do NOT add bare `"yoga"` or bare `"pilates"` — too generic, they capture non-gym apparel. Only gym/sport-qualified phrases. The existing calzado guard (`esCalzado(cat) → false`, line 524) and suplementos guard (line 525) remain untouched, so calzado and supplements stay excluded even if a name matches a new keyword.

### ADR-5: All ML/IPC reads are defensive against missing/partial payloads

**Decision:** Every new fetch tolerates absent fields and non-200 responses. Never assume a field exists.

**Rationale (verified against the controllers):**
- `/api/ml/estado` (lines 757-782): `hasTextModel` is always present, but `textMeta` is `set` only **if `text_meta.json` exists**, and its inner shape (`accuracy`, `clases`) comes from a file written by `ml_pipeline.py` — not guaranteed by the controller. So `ml.textMeta?.accuracy` must be optional-chained, with a "modo estadístico" fallback.
- `/api/status` (lines 71-105): `mlRefinadas` is present **only when `lastResult != null`**. Use `status.mlRefinadas || 0`.
- `/api/tendencias` (lines 407-440): can return **204 No Content** (ran but unusable), **503** (`{"error":"ml_failed"}`), or a 200 deep-copy of the pipeline's `tendencias` node. `badgeCounts` is whatever `ml_pipeline.py` emitted — not contract-guaranteed. Use `tend.badgeCounts || {}` and handle 204/503 by showing the statistical-mode message, not by throwing.
- `/api/recomendacion` (lines 585-649): returns `{senal:"sin_datos", mensaje}` when there is no history. Confirmed field names for the signal case: `senal`, `emoji`, `mensaje`, `scoreCompra`, `cambioReal`, `inflacionMensual`, `tendencia`, `precioMin`, `precioMax`, `pctDelMin`.

**Consequence:** The UI degrades gracefully: no model → "modo estadístico"; no history → "Sin historial suficiente"; tendencias 204/503 → panel shows statistical fallback. No uncaught exceptions break the dashboard.

### ADR-6: Training completion uses polling of `/api/ml/resultado`, not a new push channel

**Decision:** Poll `/api/ml/resultado` every 3s while a training run is active; on `done === true` show a success toast and refresh the ML banner.

**Rationale:**
- `/api/ml/resultado` (lines 798-808) already exposes `running`, `phase`, `pct`, `msg`, and a computed `done = !running && phase != 'idle'`. Polling reuses this with zero backend change.
- A WebSocket/SSE channel would be new infrastructure for a once-per-run event.

**Correction over the draft snippet:** The terminal/error branch should key off `done` and `phase`. Since `done` becomes true for any non-idle finished state, treat `done && phase === 'error'` (or `msg` indicating failure) as the error toast, and `done` otherwise as success. Always `clearInterval` on any terminal state to avoid leaking the poller.

## 3. Component Map

### Backend (single edit)

| Component | Change | Detail |
|-----------|--------|--------|
| `NormalizerService.KW_TRAINING_ROPA` | Append keywords | Fitness/pesas ES-AR vocabulary; preserve yoga/pilates guard |

All other backend classes are explicitly **unchanged**: `InflacionService`, `ml_pipeline.py`, `Product.java`, `ApiController.java`, `DatabaseService.java`, `MlEnricher`, `PythonRunner`.

### Frontend (`index.html`) component additions

| Component (JS) | Responsibility | Data source |
|----------------|----------------|-------------|
| `gymSubcat(product)` | Derive gym sub-label | `product.gymrat`, `product.categoria` |
| `renderGymratBadge(product)` | Lime pill on card | derived sub-label |
| `BADGE_MAP` + `renderBadgeML(ml)` | Colored ML badge on card | `product.ml.badge` |
| `loadIpcWidget()` | Header IPC pill | `/api/inflacion` |
| `loadMlBanner()` | Header model-state pill | `/api/status` + `/api/ml/estado` |
| `toggleModoGym()` + `renderGymSubcats()` | GYM filter + sub-category chips | `/api/data?gymrat=true` |
| `loadRecomendacion(url)` + `renderRecomendacion(d)` | Detail-panel buy signal (lazy, cached) | `/api/recomendacion` |
| `loadMlAprendizaje()` | "¿Qué aprendió la IA?" panel | `/api/ml/estado` + `/api/tendencias` |
| `startTrainingPoll()` + `showToast()` | Training-done notification | `/api/ml/resultado` |

## 4. Data Flow

### 4.1 Card render (grid)

```
fetchData() → /api/data[?gymrat=true if modoGymActivo]
  → products[]
  → renderCards(products):
       for each p:
         renderBadgeML(p.ml)      // colored ML badge (first visible element)
         renderGymratBadge(p)     // lime GYMRAT pill if p.gymrat
         ...name, price...
  → if modoGymActivo: renderGymSubcats(products)  // chips grouped by gymSubcat
```

### 4.2 Detail panel (lazy buy signal)

```
user clicks card → open detail panel(url)
  → loadRecomendacion(url):
       if recomCache[url] → renderRecomendacion(cache)   // instant
       else → show "Analizando..." → /api/recomendacion?url=
              → cache + renderRecomendacion(d)
                 senal === 'sin_datos' → "Sin historial suficiente"
                 else → emoji + mensaje + scoreCompra bar + cambioReal/IPC line
```

### 4.3 Header widgets (dashboard load)

```
dashboard load →
  loadIpcWidget()  → /api/inflacion        → IPC mensual/anual/fecha pill
  loadMlBanner()   → /api/status + /api/ml/estado
                     hasTextModel+textMeta → "ML activo — X% accuracy | N refinadas"
                     else                  → "ML: modo estadístico"
```

### 4.4 Tendencias "¿Qué aprendió la IA?"

```
open Tendencias panel →
  loadMlAprendizaje() → /api/ml/estado + /api/tendencias
     model info (accuracy/clases) or statistical-mode line
     + badge distribution from tend.badgeCounts (defensive {} default)
     handle /api/tendencias 204/503 → statistical fallback, no throw
```

### 4.5 Training lifecycle

```
training started (existing flow) → startTrainingPoll()
  every 3s → /api/ml/resultado
     done && phase==='error' → toast error  + clearInterval
     done                    → toast success + loadMlBanner() + clearInterval
```

## 5. Integration Points

| Endpoint | Method | New consumer | Contract change |
|----------|--------|--------------|-----------------|
| `/api/data` | GET | grid + Mode GYM (`gymrat=true`) | None (param already supported) |
| `/api/inflacion` | GET | header IPC widget | None |
| `/api/recomendacion` | GET | detail panel (lazy) | None |
| `/api/ml/estado` | GET | header banner + learning panel | None |
| `/api/status` | GET | header banner (`mlRefinadas`) | None |
| `/api/tendencias` | GET | learning panel (`badgeCounts`) | None |
| `/api/ml/resultado` | GET | training poll | None |

All integration is read-only over existing endpoints. The only write path touched is the offline classification (keyword widening), which flows through the existing scrape → normalize → upsert pipeline.

## 6. Validation Notes (verified against current code)

These are confirmations and one correction the implementation must honor:

1. **`Product` serializes `gymrat` and `categoria`** — confirmed (`Product.java` lines 5-19). ADR-1 is safe.
2. **`esGymrat` guards** for calzado (line 524) and suplementos (line 525) run BEFORE keyword matching — so widening `KW_TRAINING_ROPA` cannot pull calzado/supplements into GYMRAT. ADR-4 guard intact.
3. **`textMeta` is conditional** on `text_meta.json` existing, and its inner fields are pipeline-authored — must be optional-chained (ADR-5).
4. **`mlRefinadas` only exists when `lastResult != null`** — default to 0 (ADR-5).
5. **`/api/tendencias` has three response shapes** (200/204/503) and `badgeCounts` is not contract-guaranteed — handle all three, default badges to `{}` (ADR-5).
6. **`/api/ml/resultado` exposes `done`** as `!running && phase != 'idle'` — drive the toast off `done`, branch error via `phase`/`msg`, and always `clearInterval` on terminal (ADR-6 correction).

## 7. Files to Modify

| File | Change type |
|------|-------------|
| `scraper/src/main/java/ar/scraper/aggregator/NormalizerService.java` | Append to `KW_TRAINING_ROPA` |
| `scraper/src/main/resources/static/index.html` | CSS + JS render/fetch additions (header, cards, filters, detail, tendencias, toast) |

Explicitly NOT modified: `InflacionService.java`, `ml_pipeline.py`, `Product.java`, `ApiController.java`, `DatabaseService.java`.

## 8. Risks and Open Questions

| Risk | Severity | Mitigation |
|------|----------|------------|
| Over-classification from broader GYMRAT keywords | Med | Keep calzado/suppl guards; no bare yoga/pilates; review a real sample post-scrape |
| `badgeCounts` not emitted by `ml_pipeline.py` | Med | Learning panel degrades to model-info-only via `|| {}`; not a hard dependency |
| `textMeta` shape differs from assumed `accuracy`/`clases` | Med | Optional-chain every field; fallback to "modo estadístico" |
| Visual noise from too many badges per card | Med | Hierarchy: ML badge first, max 1 GYMRAT pill; no stacking of low-value badges |
| Training poller leak | Low | Single-flight guard + `clearInterval` on every terminal state |

**Open question (non-blocking for tasks):** the exact key names inside `text_meta.json` and inside the pipeline's `tendencias.badgeCounts` are pipeline-authored and not visible from the controller layer. The defensive ADR-5 design means the UI works regardless, but if the displayed accuracy/clases or badge distribution come up empty in practice, the fix is to align the JS key names with whatever `ml_pipeline.py` actually writes — without changing the contract.
