# Design: Outfit Per-Item Feedback + Global Botines Exclusion

## Technical Approach

Two independent, additive changes to the Gym armador, both extending — not reversing —
`outfit-recommendation-quality`. (1) Feedback granularity moves from per-OUTFIT to
per-ITEM: each rated slot card carries its own `liked`, persisted as one narrow row per
`(slot, url, liked)` event, and `buildFeedbackModel` collapses its existing
iterate-4-slots-per-row loop to iterate-1-row-per-item. Exclude/boost semantics
(`marca|categoria`, global, anonymous, dislike-wins, no decay — ADR-2 of the prior change)
are untouched. (2) Botines becomes permanently ineligible for the calzado slot under every
style via a single style-independent gate in `slotDe()`, decoupled from the Gym-only
`STYLE_RULES` whitelist; Borcego/Botas/Ojotas stay exactly as today.

## Architecture Decisions

### ADR-1: Per-item feedback uses a NEW narrow `outfit_feedback_item` table, not a reshape of the wide row

| Option | Tradeoff | Decision |
|--------|----------|----------|
| (A) Keep wide row, add per-slot `liked` columns (or JSON blob) | Awkward semantics ("MUST NOT mix one slot's verdict"); 4 nullable url+liked pairs is sparse; the existing row-level `liked` column still lies; `buildFeedbackModel` still iterates 4 slots/row | Rejected |
| (B-reshape) Reshape existing `outfit_feedback` to `(slot,url,liked)` | No migration framework here; reshaping a populated table means a manual recreate; strands legacy per-outfit rows | Rejected |
| (B-new) Additive NEW table `outfit_feedback_item(id, genero, slot, url, liked, created_at)`, one row per rated item | One row == one `(slot,url)` verdict — exactly what `buildFeedbackModel` wants; pure additive `CREATE TABLE IF NOT EXISTS`, zero touch to the old table; legacy wide rows simply stop being read | **Chosen** |

**Rationale.** The project has NO migration framework — schema lives in `crearTablas()` as
idempotent `CREATE TABLE IF NOT EXISTS` plus a `migrarColumna()` ALTER helper for additive
columns. Adding a sibling table is the safest additive move: it never recreates or mutates
the populated `outfit_feedback`, matches the proposal's "additive schema change, acceptable
to leave on rollback", and gives `buildFeedbackModel` a 1:1 row-to-verdict shape. Single
user, low volume — no index beyond `created_at` needed (an `idx` on `liked` mirrors the
existing table). `obtenerOutfitFeedback()` is repointed to the new table returning a new
`OutfitItemRow(slot, url, liked)`; the old `guardarOutfitFeedback(...)`/`OutfitFeedbackRow`
become dead code and are removed (per proposal: per-outfit flow is gone, not kept). On
rollback the new table is left in place (additive, harmless).

### ADR-2: Botines gate lives in `slotDe()`, before the style whitelist — not in `esCalzadoBase()`

| Option | Tradeoff | Decision |
|--------|----------|----------|
| Remove `"Botines"` from `esCalzadoBase()` | `esCalzadoBase` is the calzado-FAMILY predicate used by `slotDe`'s branch test AND by `esCalzadoElegible`'s null-rule path; dropping Botines makes it fall through to `CATEGORIA_SLOT.get("Botines")` → still `SLOT_CALZADO` (the map still lists it), so it leaks back in | Rejected |
| Add a hardcoded `if(cat=="Botines")` inside the Gym whitelist | Only fixes Gym; `DEFAULT_STYLE_RULE` (null whitelist) still admits Botines — the exact defect | Rejected |
| A `CALZADO_VETADO` set checked in `slotDe()` BEFORE the eligibility branch | Style-independent, brand-independent, runs ahead of both `esCalzadoElegible` and `CATEGORIA_SLOT`; Borcego/Botas/Ojotas untouched | **Chosen** |

**Rationale.** `slotDe()` is the single funnel every product passes through for slot
assignment — `armar()` calls nothing else for eligibility. A `private static final
Set<String> CALZADO_VETADO = Set.of("Botines")` checked as the first statement of
`slotDe()` (`if (CALZADO_VETADO.contains(cat)) return null;`) makes Botines unreachable for
ANY slot under ANY style, including styles not yet in `STYLE_RULES`, with no coupling to the
whitelist mechanism. `esCalzadoBase()` keeps listing Botines (it remains a true member of the
calzado taxonomy for `/api/data`, `/api/mejores`, `/api/facets`, ML — none of which call
`slotDe`), so those surfaces still surface Botines per the proposal's Out-of-Scope. The set
is the extension point if more permanent vetoes appear, without touching `STYLE_RULES`.

## Data Flow

```
POST /api/outfits/feedback        body: { genero, items:[{slot,url,liked}] }   (per-item NOW)
  ApiController.outfitFeedback()
    for each item: db.guardarOutfitFeedbackItem(genero, slot, url, liked)
                                                  └─► INSERT outfit_feedback_item (1 row/item)

GET /api/outfits?genero=…
  ApiController.outfits()
    rows = db.obtenerOutfitFeedback()             → List<OutfitItemRow(slot,url,liked)>
    feedback = buildFeedbackModel(rows, r.productos())   (url→Product join, iterate 1/row)
        like row  → boostLikeCount[ key(p) ]++
        dislike   → exclude.add( key(p) )         (dislike wins; per-item, no broadcast)
    outfit = armar(produtos, genero, "gym", feedback)
        slotDe(p, rule):  CALZADO_VETADO? → null   (Botines, any style)   ← ADR-2
                          else esCalzadoElegible / CATEGORIA_SLOT
        drop key(p) ∈ exclude  →  3-step fallback  →  weightedRandomPick(boost)
    ◄─ slots[] →  OutfitCard renders independent 👍/👎 per slot card        ← UI
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `db/DatabaseService.java` | Modify | Add `outfit_feedback_item(id,genero,slot,url,liked,created_at)` via `CREATE TABLE IF NOT EXISTS` + `idx_ofi_liked`; add `guardarOutfitFeedbackItem(genero,slot,url,liked)` (single-row INSERT); repoint `obtenerOutfitFeedback()` to new table returning `List<OutfitItemRow>`; remove old `guardarOutfitFeedback(...)` + `OutfitFeedbackRow` (dead). Leave old `outfit_feedback` table DDL in place (harmless) |
| `web/ApiController.java` | Modify | `outfitFeedback()`: parse `items:[{slot,url,liked}]`, loop `db.guardarOutfitFeedbackItem(...)` per item; `buildFeedbackModel(rows, produtos)`: iterate one `OutfitItemRow`/row, like→boost / dislike→exclude (drops the 4-slot `Arrays.asList` inner loop) |
| `web/OutfitService.java` | Modify | Add `CALZADO_VETADO = Set.of("Botines")`; first line of `slotDe()` returns null for vetoed cats. No other logic change |
| `frontend/src/components/OutfitsPanel.jsx` | Modify | `OutfitCard`: per-slot 👍/👎 controls + per-slot sent state (`Set` of slot keys); `handleFeedback(slot, url, liked)` posts one item |
| `frontend/src/api.js` | Modify | `sendOutfitFeedback` body shape `{genero, items:[{slot,url,liked}]}` (unchanged transport; doc only) |

## Interfaces / Contracts

```java
// DatabaseService — new narrow row + per-item write
record OutfitItemRow(String slot, String url, boolean liked) {}
void guardarOutfitFeedbackItem(String genero, String slot, String url, boolean liked);
List<OutfitItemRow> obtenerOutfitFeedback();   // now reads outfit_feedback_item

// OutfitService — unchanged FeedbackModel; new veto set
private static final Set<String> CALZADO_VETADO = Set.of("Botines");
```

POST `/api/outfits/feedback` request (CHANGED — `liked` per item):
```json
{ "genero": "hombre",
  "items": [ {"slot":"calzado","url":"…","liked":false},
             {"slot":"torso","url":"…","liked":true} ] }
```
Frontend posts ONE item per click (not a batch), so a single dislike on calzado yields
exactly one disliked-pair exclude, leaving torso/piernas/accesorio eligible. Response
`{ok:true}` unchanged. `GET /api/outfits` response shape unchanged.

## Testing Strategy

| Layer | What to Test | Approach |
|-------|--------------|----------|
| Unit | `slotDe` returns null for Botines under `gym`, `DEFAULT_STYLE_RULE`, and an unknown style; Borcego/Botas/Ojotas unchanged; `buildFeedbackModel` per-item: dislike on one pair excludes only that pair | No test infra — manual `main`/REPL over sample `Product` lists |
| Integration | POST dislike calzado-only → only that `marca|categoria` absent from repeated `GET /api/outfits`; like one item → that pair more frequent; others unaffected | Manual: run scraper, POST, inspect repeated GETs |
| Regression | `/api/data`, `/api/mejores`, `/api/facets` still surface Botines; empty `outfit_feedback_item` reproduces today's no-feedback outfit | Manual: query those endpoints + diff outfit output |

## Migration / Rollout

Additive only. New `outfit_feedback_item` table is created idempotently on boot; the legacy
`outfit_feedback` table is left untouched and unread (its rows are abandoned — acceptable,
single user, low volume, per proposal). No backfill of legacy per-outfit rows. Rollback:
revert the four edits; the new table can be left in place (additive, harmless) or dropped
manually. Rebuild: `cd frontend && npm run build` then
`mvn -f scraper/pom.xml clean package -DskipTests`.

## Open Questions

- [ ] None blocking. Both flagged decisions are pinned (ADR-1 new table; ADR-2 `slotDe` veto).
- [ ] Per-slot sent-state granularity in UI (`Set` of acted slots vs. per-slot disable) is a tasks-level detail; design fixes the contract (one POST per click), not the exact React state field name.
