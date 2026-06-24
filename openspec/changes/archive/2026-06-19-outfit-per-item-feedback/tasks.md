# Tasks: Outfit Per-Item Feedback + Global Botines Exclusion

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~180-230 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Full change (DB + backend + OutfitService + frontend) | PR 1 | Below budget; ships as one reviewable diff |

## Phase 1: Database Layer (DatabaseService.java)

- [x] 1.1 In `crearTablas()` (after L203, `outfit_feedback` block), add `CREATE TABLE IF NOT EXISTS outfit_feedback_item(id INTEGER PRIMARY KEY AUTOINCREMENT, genero TEXT, slot TEXT NOT NULL, url TEXT NOT NULL, liked INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)` and `CREATE INDEX IF NOT EXISTS idx_ofi_liked ON outfit_feedback_item(liked)`. Leave old `outfit_feedback` DDL (L192-203) untouched.
- [x] 1.2 Add `OutfitItemRow(String slot, String url, boolean liked)` record near L865, replacing `OutfitFeedbackRow`.
- [x] 1.3 Add `guardarOutfitFeedbackItem(String genero, String slot, String url, boolean liked)` — single-row INSERT into `outfit_feedback_item`, following the commit/rollback pattern of `guardarOutfitFeedback` (L841-862).
- [x] 1.4 Repoint `obtenerOutfitFeedback()` (L874-894) to `SELECT genero, slot, url, liked FROM outfit_feedback_item`, returning `List<OutfitItemRow>`.
- [x] 1.5 Remove old `guardarOutfitFeedback(...)` (L841-862) and `OutfitFeedbackRow` record (L865-866) — dead per design ADR-1.

## Phase 2: Backend API (ApiController.java)

- [x] 2.1 Rewrite `buildFeedbackModel(...)` (L716-755) to accept `List<ar.scraper.db.DatabaseService.OutfitItemRow>` and iterate one row = one item: drop the `Arrays.asList(torsoUrl,piernasUrl,calzadoUrl,accesorioUrl)` inner loop (L727-739, L745-751); per row, resolve `row.url()` via `porUrl`, skip if delisted, then `liked` → `boostLikeCount.merge(key,1,Integer::sum)`, `!liked` → `exclude.add(key)`.
- [x] 2.2 Update the call site at L681-682 to pass the new `List<OutfitItemRow>` return type from `db.obtenerOutfitFeedback()` (type now flows automatically post Phase 1.4 — verify no manual cast needed).
- [x] 2.3 Rewrite `outfitFeedback()` (L757-784): parse body as `{genero, items:[{slot,url,liked}]}` — replace the single `liked = body.get("liked")` (L761) and `slots` shared-URL map (L763-775) with a loop over `body.get("items")` (`List<Map>`), extracting `slot`, `url`, `liked` per entry.
- [x] 2.4 Inside the loop, call `db.guardarOutfitFeedbackItem(genero, slot, url, liked)` once per item (replaces single L777-781 call). Skip entries missing `slot` or `url` (no error, just skip — mirrors existing null-guard style at L770).
- [x] 2.5 Keep `resp.put("ok", true)` response unchanged (L782-783).

## Phase 3: OutfitService — Global Botines Veto

- [x] 3.1 Add `private static final Set<String> CALZADO_VETADO = Set.of("Botines");` near `STYLE_RULES`/`DEFAULT_STYLE_RULE` (L61-67).
- [x] 3.2 In `slotDe()` (L132-139), add `if (CALZADO_VETADO.contains(cat)) return null;` as the first check after the null/blank guard (L134), before `esCalzadoBase(cat)` (L135). Do not touch `esCalzadoBase()` (L149-157) or `esCalzadoElegible()` (L166-169) — Borcego/Botas/Ojotas stay Gym-whitelist-scoped exactly as today.

## Phase 4: Frontend (OutfitsPanel.jsx + api.js)

- [x] 4.1 In `frontend/src/api.js`, no code change needed for `sendOutfitFeedback` (L222-229) — body shape change is caller-side only; add a one-line doc comment noting the new `{genero, items:[{slot,url,liked}]}` contract.
- [x] 4.2 In `OutfitsPanel.jsx`, replace `handleFeedback(liked)` (L117-126) with `handleFeedback(slot, url, liked)`: build body `{genero: outfit.genero, items:[{slot, url, liked}]}`, call `sendOutfitFeedback(body)`, and on success mark that slot as sent (track via a `Set` of sent slot keys in `GymTab` state, replacing the single boolean `feedbackSent` at L95).
- [x] 4.3 Pass the per-slot sent-state `Set` and `handleFeedback` down to `OutfitCard` (replace the `feedbackSent`/`onFeedback` props at L172-178) so each slot card can independently disable its own buttons once rated.
- [x] 4.4 In `OutfitCard` (L12-87), move the 👍/👎 buttons (currently outfit-level, L65-77) into each slot's card block (L19-47): render one 👍 and one 👎 per slot, calling `onFeedback(s.slot, s.url, true/false)`, disabled when that slot's key is in the sent-state `Set`. Remove the outfit-level pair and the shared "¡Gracias!" message, or scope the thank-you message per slot.

## Phase 5: Verification (manual — no test infra)

- [x] 5.1 Manual: POST dislike for calzado-only item; confirm `GET /api/outfits` (repeated calls) never returns that `marca|categoria` for any slot, while torso/piernas/accesorio pairs from the same outfit remain eligible. (User-confirmed in browser.)
- [x] 5.2 Manual: confirm `slotDe()` returns null for `categoria="Botines"` under `gym`, an unmapped style (`DEFAULT_STYLE_RULE`), and that Borcego/Botas/Ojotas remain eligible outside Gym. (User-confirmed in browser.)
- [x] 5.3 Manual: confirm `/api/data`, `/api/mejores`, `/api/facets` still surface Botines (regression guard — these never call `slotDe`). (User-confirmed in browser.)
- [x] 5.4 Rebuild and smoke-test: `cd frontend && npm run build` then `mvn -f scraper/pom.xml clean package -DskipTests`; verify the dashboard's Gym tab shows independent 👍/👎 per slot card. (Build verified green; visual Gym-tab click-through still needs a human in the browser — see apply report.)
