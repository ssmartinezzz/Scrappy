# Proposal: Armador de Outfits (Outfit Builder)

## Intent

The catalog holds every garment needed to dress a person head-to-toe, but the user must mentally assemble combinations by scrolling. There is no way to see a complete, coherent outfit. First slice targets the GYM use case (where `gymrat` tagging already exists): generate a full assembled outfit (torso + piernas + calzado, optional accesorio) on demand, split by `genero`, with a re-roll action. Ship an honest feedback-collection step so a future learning loop has real data, instead of faking ML on a guess.

## Scope

### In Scope
- New top-level "Outfits" tab/route with a nested sub-tab strip (Gym | Casual | Formal | ...).
- **Gym sub-tab**: generate a complete outfit (torso, piernas, calzado required; accesorio optional) via static slot-lookup + price-band-weighted sampling (explore Approach A), filtered by `genero` (hombre/mujer/unisex). Re-roll action regenerates a new combo.
- Backend `categoria → slot` lookup and slot-sampling service; new `GET /api/outfits?genero=X`.
- Like/dislike feedback on a generated outfit, persisted to a new `outfit_feedback` SQLite table; new `POST /api/outfits/feedback`.
- Non-gym sub-tabs render a static "Próximamente" placeholder (no logic).

### Out of Scope
- Counter-based co-occurrence weighting from feedback (explore recommendation 2) — fast-follow, no signal yet.
- ML-scored outfit coherence (explore Approach C); any change to `ml_pipeline.py`, `NormalizerService`, or scrapers.
- Persisting/sharing/exporting outfits; cart/checkout; outfit history.
- Making `esCalzado()` gymrat-aware (would break GYMRAT badge semantics).

## Capabilities

### New Capabilities
- `outfit-builder`: slot taxonomy, gym outfit assembly, fallback policy, re-roll, `genero` policy, `GET /api/outfits`.
- `outfit-feedback`: like/dislike collection, `outfit_feedback` table, `POST /api/outfits/feedback`.

### Modified Capabilities
- None (no existing specs; `/api/data` query plumbing is reused, not changed).

## Approach

- **Assembly (Approach A)**: hardcode `categoria → slot` map per explore taxonomy. Per request, sample one weighted product per required slot from `gymrat == true` for torso/piernas, and from a footwear-`categoria` whitelist (`Zapatilla*`) for calzado (footwear is structurally excluded from `gymrat`). Constrain to a price band for visual coherence.
- **`genero` policy**: empty/missing `genero` is treated as unisex-eligible; a gendered request matches that genero plus unisex plus empty.
- **Fallback order** when a slot has zero matches: (1) relax price band → (2) relax `genero` to unisex → (3) return a partial result flag and show "no hay suficientes productos".
- **Feedback scope**: log liked/disliked outfits ONLY (slot product URLs, `genero`, `liked` boolean, timestamp). No impressions — impressions add write volume and a delivery/identity contract for zero day-1 benefit; like/dislike already gives a usable training signal for the fast-follow. Collection is not wired into generation.
- **Frontend**: new `OutfitsPanel.jsx` (sub-tab strip + outfit card with re-roll and like/dislike), one new route in `App.jsx`/`Sidebar.jsx`. Reuse `FavoritosPanel.jsx` interaction patterns.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `web/OutfitService.java` (new) | New | Slot-lookup map + price-band-weighted sampling + fallback |
| `web/ApiController.java` | Modified | `GET /api/outfits?genero=X`, `POST /api/outfits/feedback` |
| `db/DatabaseService.java` | Modified | New `outfit_feedback` table (`CREATE TABLE IF NOT EXISTS`) + read queries per slot |
| `frontend/src/components/OutfitsPanel.jsx` (new) | New | Sub-tab strip, outfit card, re-roll, like/dislike, placeholders |
| `frontend/src/App.jsx`, `Sidebar.jsx` | Modified | One new "Outfits" route + nav entry |
| Scrapers / `NormalizerService.java` / `ml_pipeline.py` | Unchanged | No data-source, normalization, or ML changes |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Sparse slot+genero+price-band → empty outfits | High | 3-step fallback (price band → genero → "no hay suficientes productos") |
| Gym footwear excluded from `gymrat` by design | Med | Separate `Zapatilla*` `categoria` whitelist for calzado; do not touch `esCalzado()` |
| `genero` frequently empty in real data | Med | Treat empty as unisex-eligible |
| Promising "learning" with zero training data | Med | Collection-only now; weighting is documented fast-follow |

## Rollback Plan

No version control in this project; revert by file. Delete new files `web/OutfitService.java` and `frontend/src/components/OutfitsPanel.jsx`. Revert the additions in `ApiController.java` (both endpoints), `DatabaseService.java` (the `outfit_feedback` table + slot queries), and `App.jsx`/`Sidebar.jsx` (the new route/nav entry). The `outfit_feedback` table is additive (`CREATE TABLE IF NOT EXISTS`) and can be left in place harmlessly or dropped manually. Rebuild: `cd frontend && npm run build` (emits to `scraper/src/main/resources/static/`), then `mvn -f scraper/pom.xml clean package -DskipTests` to regenerate the fat JAR.

## Dependencies

- Existing `Product` fields: `categoria`, `genero`, `gymrat`, `precio`, `url`, `imagenUrl`, `marca`.
- Existing SQLite catalog (`productos`) and `DatabaseService` query plumbing.
- Frontend: React 18 + Vite 5 in `frontend/src/`; build to `resources/static/`.

## Success Criteria

- [ ] "Outfits" tab generates a complete gym outfit (torso + piernas + calzado) for a chosen `genero`.
- [ ] Re-roll returns a different valid combo.
- [ ] Sparse-inventory requests degrade via the documented fallback instead of crashing or returning blanks.
- [ ] Like/dislike persists a row to `outfit_feedback`.
- [ ] Non-gym sub-tabs show "Próximamente"; no scraper, normalization, or ML code changed.
