# Tasks: Outfit Builder (outfit-builder + outfit-feedback)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~520-600 (1 new backend service ~150, 2 DB methods ~40, 2 endpoints ~70, 1 new React component ~220, 2 wiring files ~20, api.js ~20) |
| 400-line budget risk | High |
| Chained PRs recommended | No (no VCS in this project — N/A) |
| Suggested split | Split into 2 apply batches: Batch 1 = backend (Phases 1-3), Batch 2 = frontend (Phases 4-6) + build/verify (Phase 7) |
| Delivery strategy | N/A — no git/VCS in this project |
| Chain strategy | N/A — no git/VCS in this project |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: High

No version control exists in this project (confirmed not a git repo) — there is no PR/branch concept and no chained-PR strategy applies. The forecast above exists only to flag that the total diff is large enough that `sdd-apply` SHOULD run it as two separate apply batches (backend, then frontend) for manageability and easier manual verification between batches, not for review-tooling reasons.

### Suggested Work Units (apply batches, not PRs)

| Unit | Goal | Batch | Notes |
|------|------|-------|-------|
| 1 | Backend: slot map, sampler, fallback, DB table, 2 endpoints | Batch 1 | Verifiable standalone via curl against localhost:3000 |
| 2 | Frontend: panel, sub-tabs, card, routing/nav wiring | Batch 2 | Depends on Batch 1 endpoints being live |
| 3 | Build + manual end-to-end verification | Batch 2 (tail) | Runs after both prior units |

## Phase 1: Backend Foundation — OutfitService

- [x] 1.1 Create `scraper/src/main/java/ar/scraper/web/OutfitService.java` as `@Service` with static `categoria → slot` map per design.md taxonomy (torso/piernas/calzado/accesorio)
- [x] 1.2 Add footwear whitelist check (`Zapatilla*` prefix, `Sneaker`) independent of `gymrat`, per ADR-1 — do not touch `esCalzado()`/`esGymrat()`
- [x] 1.3 Implement `genero` eligibility matching (requested == value OR `unisex` OR empty/missing) per Genero Matching Policy requirement
- [x] 1.4 Implement price-band candidate filter (±X% around median of eligible pool; pick concrete X, e.g. 30%, document choice as a comment)
- [x] 1.5 Implement weighted random pick per slot from filtered candidates
- [x] 1.6 Implement 3-step fallback per slot: relax price band → relax genero to unisex-only → mark `partial=true` and skip slot (no fabricated product)
- [x] 1.7 Implement best-effort `accesorio` pick (no fallback) and assemble `Outfit{slots[], genero, partial}` result

## Phase 2: Backend Foundation — Feedback Persistence

- [x] 2.1 Add `outfit_feedback` DDL to `DatabaseService.crearTablas()` (`CREATE TABLE IF NOT EXISTS` with id, genero, liked INTEGER, slots_json TEXT NOT NULL, created_at TEXT NOT NULL) and the `idx_outfit_fb_liked` index, mirroring `favoritos`/`ml_output` style
- [x] 2.2 Add `guardarOutfitFeedback(genero, liked, slotsJson)` method to `DatabaseService.java` mirroring `guardarFavorito` insert pattern

## Phase 3: Backend Integration — API Endpoints

- [x] 3.1 Inject `OutfitService` into `ApiController.java`
- [x] 3.2 Add `GET /api/outfits?genero=X` reading `service.getLastResult().productos()`, calling `OutfitService.armar(...)`, serializing the pinned response shape (`genero`, `partial`, `slots[]` with `slot`/`sitio`/`nombre`/`precio`/`url`/`img`/`categoria`/`marca`); return 204 via existing `noContent()` idiom when no catalog loaded
- [x] 3.3 Add `POST /api/outfits/feedback` parsing `{genero, liked, slots[{slot,url}]}`, calling `db.guardarOutfitFeedback(...)`, responding `{"ok":true}`
- [x] 3.4 Manual verification: start app, `curl http://localhost:3000/api/outfits?genero=hombre` returns 3+ slots; re-call confirms different combo when candidates > 1; `curl -X POST .../api/outfits/feedback` with sample body returns `{"ok":true}` and inserts a row (inspect `scraper.db` via sqlite3 `outfit_feedback` table) — DONE WITH CAVEAT: no catalog was ever scraped in this environment, so `GET /api/outfits` correctly returned 204 (documented expected behavior per `getLastResult()==null`, not a bug) instead of 3+ slots. The 204 path, the `POST /api/outfits/feedback` 200+`{"ok":true}` response, and the DB row insertion (verified via JDBC query against `scraper.db`) were all confirmed. The "3+ slots populated" and "re-roll produces different combo" scenarios could not be exercised end-to-end without a real scraped catalog — flagged as a residual verification gap for the frontend batch or a future run with live data.

## Phase 4: Frontend — API Client

- [x] 4.1 Add `fetchOutfit(genero)` to `frontend/src/api.js` calling `GET /api/outfits?genero=X`
- [x] 4.2 Add `sendOutfitFeedback(body)` to `frontend/src/api.js` calling `POST /api/outfits/feedback`

## Phase 5: Frontend — Outfits Panel

- [x] 5.1 Create `frontend/src/components/OutfitsPanel.jsx` with sub-tab strip (Gym | Casual | Formal)
- [x] 5.2 Implement Gym sub-tab: call `fetchOutfit(genero)` on mount/genero-change, render slot products
- [x] 5.3 Add `OutfitCard` sub-component rendering 3-4 slot products (img, nombre, precio, marca) plus re-roll button (re-invokes `fetchOutfit`) and 👍/👎 buttons calling `sendOutfitFeedback`
- [x] 5.4 Render partial-result message ("no hay suficientes productos") when `partial=true`, without fabricating a missing slot
- [x] 5.5 Implement placeholder state ("Próximamente") for Casual/Formal sub-tabs — no network call issued on selecting them

## Phase 6: Frontend — Routing & Navigation

- [x] 6.1 Modify `frontend/src/components/AppLayout.jsx`: add `lazy()` import for `OutfitsPanel`, `OutfitsPanelRoute` wrapper (mirroring `FavoritosPanelRoute`), export, and NavLink nav entry for "Outfits"
- [x] 6.2 Modify `frontend/src/App.jsx`: add `<Route path="outfits" element={<OutfitsPanelRoute/>}/>`

## Phase 7: Build & Manual Verification

- [x] 7.1 Run `cd frontend && npm run build` — confirm build emits to `scraper/src/main/resources/static/` without errors
- [x] 7.2 Run `mvn -f scraper/pom.xml clean package -DskipTests` — confirm fat JAR builds cleanly
- [x] 7.3 Launch app, open `http://localhost:3000/outfits` — confirm Gym tab renders a complete outfit (torso+piernas+calzado) for a chosen genero, no console errors — DONE WITH CAVEAT: no real browser available in this environment; verified via `GET /api/outfits?genero=hombre` returning 200 with populated slots against a live-scraped catalog (1051 products from freres+vcp), plus careful code review of `OutfitsPanel.jsx` (GymTab fetches on mount/genero-change, renders `OutfitCard` per slot). NOT visually confirmed in an actual browser DOM. The freres+vcp test catalog has zero `gymrat==true` products (boutique/streetwear sites, not gym-specific), so the live response is `partial:true` with only calzado+accesorio populated — this is the documented Fallback Policy behavior working correctly (3-step fallback exhausted for torso/piernas → partial flag set, no fabricated slot), not a complete (torso+piernas+calzado) outfit. A gym-focused catalog would be needed to observe the "complete outfit" scenario; the sparse-inventory/partial-flag scenario (7.7) is what was actually exercised and is itself a required spec scenario.
- [x] 7.4 Click re-roll — confirm a different combination appears when multiple candidates exist (spec scenario: re-roll produces a different combination) — verified via two consecutive `GET /api/outfits?genero=hombre` calls returning different calzado/accesorio picks (different `url`/`nombre` each time), confirming re-roll re-samples. Verified via API calls, not a real button click in a browser.
- [x] 7.5 Click 👍 or 👎 — confirm `outfit_feedback` row persists in `scraper.db` (spec scenario: like/dislike submission persists a row) — verified via `POST /api/outfits/feedback` (200, `{"ok":true}`) followed by a JDBC query against `scraper.db` confirming the new row (id=2) with correct `genero`/`liked`/`slots_json`/`created_at`. Verified via API + DB inspection, not a real button click.
- [x] 7.6 Switch to Casual/Formal sub-tabs — confirm "Próximamente" renders and no network request fires (check browser devtools Network tab) — DONE VIA CODE REVIEW ONLY (no real devtools/browser available): `OutfitsPanel.jsx` conditionally renders `{tab === 'gym' && <GymTab/>}` / `{tab === 'casual' && <PlaceholderTab label="Casual"/>}` / `{tab === 'formal' && <PlaceholderTab label="Formal"/>}`. `PlaceholderTab` has no imports beyond JSX, no hooks, no `fetch`/`api.js` calls of any kind — confirmed by grep across the component source. Selecting Casual/Formal unmounts `GymTab` (the only subtree that calls `fetchOutfit`) and mounts a static-string-only component, so a network call on placeholder tabs is structurally impossible, not just empirically absent.
- [x] 7.7 Test sparse-inventory scenario (e.g. filter to a genero with few products) — confirm fallback degrades gracefully and partial flag appears instead of crashing or returning blanks — verified live: the freres+vcp test catalog has zero `gymrat==true` products, so every `genero` tested (hombre/mujer/unisex/omitted) correctly returned HTTP 200 with `partial:true`, calzado+accesorio populated, torso/piernas correctly omitted (not fabricated). No crash, no 500, no blank/null response.
