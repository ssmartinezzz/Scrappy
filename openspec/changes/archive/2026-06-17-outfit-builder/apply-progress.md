# Apply Progress: Outfit Builder (outfit-builder + outfit-feedback)

## Mode
Standard (not Strict TDD) — `openspec/config.yaml` has `apply.tdd: false`, no JUnit/test infra exists in the project. Verification was manual per tasks.md.

## Batch 1 (this batch): Backend — Phase 1, 2, 3

### Completed Tasks

- [x] 1.1 Created `scraper/src/main/java/ar/scraper/web/OutfitService.java` (`@Service`) with static `categoria → slot` map (torso/piernas/calzado/accesorio)
- [x] 1.2 Footwear whitelist (`Zapatilla*` prefix, Botines/Borcego/Botas/Ojotas/Sneaker) implemented independent of `gymrat`, per ADR-1 — `esCalzado()`/`esGymrat()` in `NormalizerService.java` untouched
- [x] 1.3 Genero eligibility matching implemented (`requested == value OR unisex OR empty/missing`)
- [x] 1.4 Price-band filter implemented: ±30% around median of eligible pool (`PRICE_BAND_PCT = 0.30`, documented as a comment in `OutfitService.java`)
- [x] 1.5 Weighted random pick per slot (inverse-distance-to-band-center weighting)
- [x] 1.6 3-step fallback per slot implemented: relax price band → relax genero to unisex-only → mark `partial=true` and skip slot (never fabricates a product)
- [x] 1.7 Best-effort `accesorio` pick (no fallback); `Outfit{slots[], genero, partial}` result assembled
- [x] 2.1 `outfit_feedback` DDL added to `DatabaseService.crearTablas()` (`CREATE TABLE IF NOT EXISTS` with id/genero/liked/slots_json/created_at) + `idx_outfit_fb_liked` index
- [x] 2.2 `guardarOutfitFeedback(genero, liked, slotsJson)` added to `DatabaseService.java`, mirroring the `guardarFavorito` insert pattern
- [x] 3.1 `OutfitService` injected into `ApiController.java` constructor
- [x] 3.2 `GET /api/outfits?genero=X` added — reads `service.getLastResult().productos()`, calls `OutfitService.armar(...)`, serializes the pinned response shape (`genero`, `partial`, `slots[]` with `slot`/`sitio`/`nombre`/`precio`/`url`/`img`/`categoria`/`marca`); returns 204 via existing `noContent()` idiom when no catalog loaded
- [x] 3.3 `POST /api/outfits/feedback` added — parses `{genero, liked, slots[{slot,url}]}`, calls `db.guardarOutfitFeedback(...)`, responds `{"ok":true}`
- [x] 3.4 Manual verification performed (see Verification Results below)

### Files Changed

| File | Action | What Was Done |
|------|--------|----------------|
| `scraper/src/main/java/ar/scraper/web/OutfitService.java` | Created | New `@Service`: static `categoria→slot` taxonomy map, footwear whitelist independent of `gymrat`, genero eligibility, ±30% price-band filter, weighted random sampler, 3-step fallback, best-effort accesorio, `Outfit`/`SlotPick` records |
| `scraper/src/main/java/ar/scraper/db/DatabaseService.java` | Modified | Added `outfit_feedback` table DDL + `idx_outfit_fb_liked` index inside `crearTablas()`; added `guardarOutfitFeedback(genero, liked, slotsJson)` method mirroring `guardarFavorito` |
| `scraper/src/main/java/ar/scraper/web/ApiController.java` | Modified | Injected `OutfitService` via constructor; added `GET /api/outfits?genero=X` and `POST /api/outfits/feedback` endpoints using existing `ObjectNode`/`ArrayNode`/`safe()`/`noContent()` idioms |

### Deviations from Design

- **Calzado slot whitelist breadth**: design.md's File Changes section described the footwear whitelist narrowly (`Zapatilla*` + `Sneaker`), but spec.md's `Slot Taxonomy` requirement (RFC2119 MUST) explicitly lists the full footwear set: `Zapatilla*` (prefix), Botines, Borcego, Botas, Ojotas, Sneaker. Implemented per spec.md's broader MUST list (matches `NormalizerService.esCalzado()`'s existing categoria set exactly, just applied independently of `gymrat` per ADR-1). This is a spec-design reconciliation, not a freelance deviation — spec.md is the RFC2119 source of truth and was followed.
- **Price-band width**: chose ±30% (`PRICE_BAND_PCT = 0.30`) as the concrete value per tasks.md 1.4's suggestion, documented inline as a comment in `OutfitService.java`.
- No other deviations — implementation otherwise matches design.md's architecture, ADRs, and the pinned response/DDL shapes from design.md and spec.md.

### Issues Found

- The original `OutfitService.java` draft had a Javadoc comment containing the literal substring `Zapatilla*/Botines`, where `*/` was parsed by `javac` as the comment terminator, causing 12 compilation errors. Fixed by rewording the comment to avoid `*/` mid-sentence. Caught and fixed during the build-verification step before reporting completion.

### Verification Results

1. **Build**: `_tools\maven\bin\mvn.cmd -f scraper/pom.xml clean package -DskipTests` with `JAVA_HOME` set to `_tools\jdk21` (absolute path, confirmed as JDK home not a `bin` subfolder) — **BUILD SUCCESS**. Fat JAR produced at `scraper/target/fashion-scraper-1.0.0.jar`.
2. **App startup**: Started `java -jar scraper/target/fashion-scraper-1.0.0.jar` in background using the bundled JDK; app came up on port 3000 within ~1 second. `GET /api/status` returned `{"status":"IDLE","mensaje":"Listo","tieneData":false}`.
3. **`GET /api/outfits?genero=hombre`**: returned **HTTP 204** (No Content). This is the **expected/documented behavior** per task 3.4's explicit instruction — no catalog has ever been scraped in this environment (`service.getLastResult()` is `null`), so the `noContent()` early-return path in the endpoint correctly triggers. This is NOT a bug; it is the designed fallback for "no catalog loaded."
4. **`POST /api/outfits/feedback`**: sent a sample body (`{"genero":"hombre","liked":true,"slots":[{"slot":"torso","url":"..."},{"slot":"piernas","url":"..."},{"slot":"calzado","url":"..."}]}`) — returned **HTTP 200** with `{"ok":true}`.
5. **DB row verification**: no `sqlite3` CLI is bundled in `_tools/`. Performed a JDBC-based check instead: extracted `sqlite-jdbc-3.45.3.0.jar` and `slf4j-api-2.0.13.jar` from inside the fat JAR's `BOOT-INF/lib/`, compiled a small standalone Java checker, and queried `outfit_feedback` directly against `scraper.db`. Confirmed: **1 row inserted** — `id=1, genero=hombre, liked=1, slots_json=[the submitted array], created_at=2026-06-17 16:35:34`. Table creation, insert logic, and column mapping all verified correct.
6. **Process cleanup**: stopped the background app process (`taskkill /F /PID <pid>`) after verification; confirmed port 3000 no longer responds.

### Residual Verification Gap (flag for next batch / future live-data run)

Because no product catalog has ever been scraped in this dev environment, two spec.md scenarios could not be exercised end-to-end in this batch:
- "Successful generation for a given genero" (3+ populated slots) — `OutfitService.armar()` logic was code-reviewed against the spec scenarios but not observed live with real `Product` data.
- "Re-roll produces a different combination" — same constraint; needs a populated catalog with ≥2 candidates per slot to observe.

Recommend the frontend/Phase 4-7 batch (or a dedicated verify pass) run an actual scrape first (`POST /api/scrape`) before exercising `/outfits` end-to-end in the browser, per task 7.3-7.4's own plan, which already assumes a populated catalog.

### Workload / PR Boundary

- Mode: N/A — no git/VCS in this project (confirmed). Tasks.md's Review Workload Forecast flagged this as "no VCS — N/A," with the high-changed-lines forecast existing only to justify splitting apply into two batches for manageability, not for PR-review reasons.
- Current work unit: Unit 1 — "Backend: slot map, sampler, fallback, DB table, 2 endpoints" (per tasks.md Suggested Work Units table)
- Boundary: This batch starts from zero backend code and ends with both endpoints live, verified via curl + JDBC, and the app cleanly stopped. Frontend (Phase 4-7) is a separate, dependent batch.
- Estimated review budget impact: ~150 (OutfitService) + ~25 (DatabaseService DDL+method) + ~45 (ApiController DI+2 endpoints) ≈ 220 changed lines for this batch — within budget on its own; the full change's ~520-600 estimate requires the frontend batch to complete the total.

## Batch 2 (this batch): Frontend — Phase 4, 5, 6, 7

### Completed Tasks

- [x] 4.1 Added `fetchOutfit(genero)` to `frontend/src/api.js` — `GET /api/outfits?genero=X`, returns `null` on 204 (no catalog), mirrors `fetchHistorial`/`fetchGrupos` 204-handling idiom
- [x] 4.2 Added `sendOutfitFeedback(body)` to `frontend/src/api.js` — `POST /api/outfits/feedback`, JSON body, mirrors `addFavorito`/`updateConfig` POST idiom
- [x] 5.1 Created `frontend/src/components/OutfitsPanel.jsx` with sub-tab strip (Gym | Casual | Formal), styled like `TrendsPanel.jsx`'s sub-tab bar (sticky, active-state border-bottom)
- [x] 5.2 `GymTab` sub-component: genero selector (hombre/mujer/unisex buttons), calls `fetchOutfit(genero)` on mount and on genero change via `useEffect`/`useCallback`
- [x] 5.3 `OutfitCard` sub-component: renders all returned slots (img/nombre/precio/marca per slot, styled like `FavoritosPanel`/`TrendsPanel`'s `TopDeals` card precedent), re-roll button (re-invokes `fetchOutfit` via `load('reroll')`), 👍/👎 buttons calling `sendOutfitFeedback` with `{genero, liked, slots:[{slot,url}]}`
- [x] 5.4 Partial-result banner rendered when `outfit.partial === true` (amber warning style matching `BADGE_COLOR` palette conventions); never fabricates a missing slot — renders only `outfit.slots` as returned by the backend
- [x] 5.5 `PlaceholderTab` sub-component for Casual/Formal — pure static string render, zero imports beyond JSX, zero hooks, zero `fetch`/`api.js` calls; selecting these tabs unmounts `GymTab` (the only subtree that calls `fetchOutfit`) via conditional rendering (`{tab === 'gym' && <GymTab/>}` etc.)
- [x] 6.1 Modified `frontend/src/components/AppLayout.jsx`: added `lazy()` import for `OutfitsPanel`, `OutfitsRoute` wrapper (no outlet context needed — component is self-contained, unlike `FavoritosPanelRoute`), exported as `OutfitsPanelRoute`, added `NavLink` nav entry (👕 icon) after Favoritos tab
- [x] 6.2 Modified `frontend/src/App.jsx`: imported `OutfitsPanelRoute`, added `<Route path="outfits" element={<OutfitsPanelRoute/>}/>` inside the `AppLayout` route group
- [x] 7.1 `cd frontend && ../_tools/node/npm.cmd run build` (with `_tools/node` prepended to `PATH` so the `npm.cmd` shim could resolve its own bundled `node.exe`) — **BUILD SUCCESS**, emitted `OutfitsPanel-CYjI067q.js` (5.15 kB) to `scraper/src/main/resources/static/assets/` alongside the other lazy-loaded panel chunks
- [x] 7.2 `_tools/maven/bin/mvn.cmd -f scraper/pom.xml clean package -DskipTests` with `JAVA_HOME=_tools/jdk21` — **BUILD SUCCESS**, fat JAR rebuilt at `scraper/target/fashion-scraper-1.0.0.jar` with the new frontend bundle embedded
- [x] 7.3 Launched app, exercised `/outfits` — see Verification Results below for the honest breakdown of what was API/code-verified vs NOT visually confirmed
- [x] 7.4 Re-roll verified via two consecutive API calls returning different slot picks
- [x] 7.5 Feedback persistence verified via API + JDBC query against `scraper.db`
- [x] 7.6 Placeholder no-network-call guarantee verified via code review (structural, not just empirical)
- [x] 7.7 Sparse-inventory/partial-flag fallback verified live against a real scraped catalog with zero `gymrat` products

### Files Changed

| File | Action | What Was Done |
|------|--------|----------------|
| `frontend/src/api.js` | Modified | Added `fetchOutfit(genero)` and `sendOutfitFeedback(body)` following existing fetch-helper conventions (204→null, POST+JSON body) |
| `frontend/src/components/OutfitsPanel.jsx` | Created | Sub-tab strip (Gym\|Casual\|Formal); `GymTab` (genero selector, fetch-on-mount/change, partial-flag banner, empty-state, error-state); `OutfitCard` (slot grid, re-roll, 👍/👎); `PlaceholderTab` (static, zero side effects) |
| `frontend/src/components/AppLayout.jsx` | Modified | Added `OutfitsPanel` lazy import, `OutfitsRoute`/`OutfitsPanelRoute` export, NavLink nav entry |
| `frontend/src/App.jsx` | Modified | Imported `OutfitsPanelRoute`, added `<Route path="outfits" .../>` |

### Deviations from Design

- **`OutfitsRoute` wrapper takes no outlet context props**, unlike `FavoritosPanelRoute`/`TrendsRoute`/etc. The design's File Changes table says "mirroring `FavoritosPanelRoute`" for the wrapper pattern (lazy import + wrapper + export + NavLink), which was followed structurally, but `OutfitsPanel` itself manages all its own state internally (genero, tab, outfit data) and has no cross-panel interactions (no `onOpenDetail`, no shared reducer state) — so the wrapper is a trivial pass-through `function OutfitsRoute() { return <OutfitsPanel/>; }` rather than threading `useOutletContext()` values. This matches the spec's stated scope (outfit generation is stateless per request, no shared catalog dispatch needed) — not a freelance deviation, just the minimal correct wrapper for a self-contained panel.
- **Genero selector UI**: design.md/spec.md don't specify the exact UI for choosing `genero` on the Gym tab; spec.md's `GET /api/outfits` requirement only pins the query param. Implemented as three toggle buttons (hombre/mujer/unisex) per this batch's explicit instruction ("give the user a genero selector"). No spec conflict.
- No other deviations — implementation matches design.md's File Changes table, the pinned response shape (consumed exactly as documented, confirmed against live `ApiController.java` source before coding), and spec.md's Placeholder Outfit Categories requirement.
