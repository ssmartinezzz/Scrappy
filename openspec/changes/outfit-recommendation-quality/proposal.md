# Proposal: Outfit Recommendation Quality

## Intent

The Gym outfit builder ships valid combinations but the recommendations are visibly wrong in three ways the product owner hits constantly: (1) like/dislike feedback is collected but never used — the dashboard *looks* like it learns yet `outfit_feedback` has zero read path (ADR-2 deliberately deferred weighting); (2) dress footwear (Botines/Borcego/Botas) shows up in "Gym" outfits because `esCalzadoWhitelist()` has no notion of style, only an implicit single hardcoded Gym pass via `gymrat`; (3) outfits visually show "two pants" because `NormalizerService.clasificar()` is a sequential first-match classifier with no combo detection — a single-SKU "Conjunto Buzo + Jogging" matches the torso block (`KW_BUZO`, ~L703) before the pants block (`KW_JOGGING`, ~L714) and is mislabeled `Buzo`. This change makes feedback actually influence recommendations, makes slot eligibility style-aware (Gym only, built for future extension), and stops combo products from being miscategorized at the source.

## Scope

### In Scope
- **Feedback-driven sampling** (reverses ADR-2): read `outfit_feedback` on `GET /api/outfits`. Dislike (👎) → HARD EXCLUDE that `marca`+`categoria` pair from all slots; Like (👍) → BOOST that pair's weight in `weightedRandomPick`. No time decay; global scope (genero-agnostic). Re-derive `marca`+`categoria` by joining each feedback row's `url` against the live catalog (`getLastResult().productos()`).
- **Style-aware slot eligibility**: introduce a style-keyed eligibility structure (e.g. `STYLE_RULES`) and implement ONLY the Gym ruleset — Gym calzado narrows to athletic footwear (`Zapatilla*`, `Sneaker`), excluding Botines/Borcego/Botas/Ojotas. Built so Casual/Formal can plug in later without rewrite.
- **Combo-product detection** in `NormalizerService.clasificar()`: detect multi-piece products ("conjunto"/"combo"/"set" names, or dual torso+piernas keyword hits) and treat as ambiguous rather than silently first-matching. Covers the general mechanism, not a narrow `KW_BUZO`/`KW_JOGGING` patch; also covers `KW_TRAJE` (~L698, same defect, smaller blast radius).

### Out of Scope
- Casual/Formal rule sets and any backend logic for them — they stay UI "Próximamente" placeholders.
- Per-user/session feedback personalization — feedback stays global (no user identity in this app).
- Backfilling/migrating existing `categoria` values in `scraper.db` — a re-scrape re-normalizes every row via `ResultAggregator`; no migration. Document re-scrape as the workaround.
- Any change to `outfit_feedback` schema/columns (already correct).
- Time-decay weighting (explicitly rejected for v1).

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `outfit-feedback`: reverse collection-only constraint — feedback MUST now influence `GET /api/outfits` sampling (hard-exclude on dislike, boost on like, global, no decay, url→catalog join).
- `outfit-builder`: slot eligibility becomes style-aware (Gym ruleset narrows calzado to athletic footwear); slot sampling honors feedback exclude/boost.

> Combo detection lives in scraper-side normalization (no existing `normalizer` spec). The spec phase decides whether to capture it under a new `normalization` capability or as a non-spec data-quality note; flagged for sdd-spec.

## Approach

- **Feedback read-path**: on each `armar()`, load `outfit_feedback` rows via `DatabaseService`, join `url`→live `Product` to recover `marca`+`categoria`. Build an exclude-set (any disliked pair) and a boost-map (liked pair → multiplier). Apply exclude as a hard filter alongside genero/price; fold boost into `weightedRandomPick` weights. Feedback on delisted URLs silently can't contribute (known limit).
- **Style rules**: replace the bare `esCalzadoWhitelist()` gate with a `STYLE_RULES` map keyed by style; Gym entry defines allowed calzado categorias. `armar()` takes/derives the active style (Gym only for now). No hardcoded assumptions that block adding styles later.
- **Combo detection**: in `clasificar()`, before first-match return, detect combo signals (name tokens conjunto/combo/set, or simultaneous torso+piernas keyword hits) and resolve to an unambiguous outcome (e.g. exclude from single-slot use or tag distinctly) so a multi-piece SKU never lands in `torso` and then collides with a real `piernas` pick.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `web/OutfitService.java` | Modified | Feedback exclude/boost in sampling; `STYLE_RULES` style-aware calzado eligibility |
| `db/DatabaseService.java` | Modified | New read query over `outfit_feedback` (no schema change) |
| `web/ApiController.java` | Possibly Modified | Wire feedback read into `GET /api/outfits` flow if not internal |
| `aggregator/NormalizerService.java` | Modified | Combo/multi-piece detection in `clasificar()` (~L643-741) |
| `resources/ml/ml_pipeline.py` | Modified | Skip-set guard so the ML re-classifier never overrides the new `"Conjunto"` categoria back to a single piece |
| `openspec/changes/outfit-builder/` (stale folder) | Note only | Leftover post-archive; flag for separate housekeeping cleanup, not in scope |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Hard-exclude empties a slot in small catalogs | Med | Existing 3-step fallback still runs after exclude; partial-result flag covers exhaustion |
| url→catalog join misses delisted products | High | Documented limitation; those rows simply don't contribute, no error |
| Combo heuristic over/under-matches legit single items | Med | Conservative signals (explicit combo tokens + dual-block hit); validate on real catalog names |
| Fix only affects new scrapes, old `categoria` stays wrong | High | Documented: re-scrape upserts/re-normalizes; no backfill in this change |
| Style refactor over-engineers for unbuilt Casual/Formal | Low | Ship Gym ruleset only; structure for extension without implementing other styles |

## Rollback Plan

No VCS in this project — revert by file. Restore `OutfitService.java` (remove feedback exclude/boost + `STYLE_RULES`, re-inline `esCalzadoWhitelist`), `NormalizerService.java` (remove combo detection), `DatabaseService.java` (remove the feedback read query), and any `ApiController.java` wiring. The `outfit_feedback` table and existing rows are untouched (read-only addition), so nothing to migrate back. Rebuild: `cd frontend && npm run build`, then `mvn -f scraper/pom.xml clean package -DskipTests`. Combo-detection rollback only affects future scrapes; already-scraped rows are unaffected either way.

## Dependencies

- Existing `outfit_feedback` table (id, genero, liked, torso_url, piernas_url, calzado_url, accesorio_url, created_at) — read-only.
- Live catalog `getLastResult().productos()` for the url→`marca`/`categoria` join.
- `Product` fields: `categoria`, `marca`, `genero`, `gymrat`, `precio`, `url`.
- Canonical specs `openspec/specs/outfit-builder/spec.md` and `outfit-feedback/spec.md` (both already reflect the genero-matching fix).

## Success Criteria

- [ ] Disliking a product's marca+categoria removes that pair from all future generated outfits (any genero).
- [ ] Liking a product's marca+categoria measurably increases its sampling frequency.
- [ ] Gym outfits never include Botines/Borcego/Botas; only athletic footwear (`Zapatilla*`, `Sneaker`).
- [ ] The style mechanism accepts a new style entry without rewriting `armar()` (Casual/Formal still placeholders).
- [ ] After a fresh scrape, a "Conjunto Buzo + Jogging"-type SKU no longer mislabels as a torso item that collides with a separate pants pick.
- [ ] No `outfit_feedback` schema change; no DB backfill required.
