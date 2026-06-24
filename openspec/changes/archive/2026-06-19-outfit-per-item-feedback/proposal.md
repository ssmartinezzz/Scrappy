# Proposal: Outfit Per-Item Feedback + Global Botines Exclusion

## Intent

Two product-owner pain points remain after `outfit-recommendation-quality`, both in the Gym armador only:

1. **Feedback granularity is per-OUTFIT, not per-PRENDA.** `POST /api/outfits/feedback` (`ApiController` ~L757) takes ONE shared `liked` for the whole submission and stores all four slot URLs in one `outfit_feedback` row. `buildFeedbackModel` (~L716) then applies that single verdict to every slot's `marca|categoria` pair. So disliking an outfit because of the SHOES alone hard-excludes torso, piernas, calzado AND accesorio — over-punishing items the user never disliked. Likes have the mirror defect. The user wants an independent 👍/👎 per visible item.
2. **Botines are excluded only for Gym, not globally.** `STYLE_RULES` restricts calzado only for `"gym"`; `DEFAULT_STYLE_RULE` has `calzadoWhitelist()==null`, so under any other/unmapped style Botines become eligible again (`esCalzadoBase` lists `"Botines"`, `slotDe` routes it to calzado). The user never wants Botines in ANY outfit, ANY style — a hard, brand-independent, style-independent rule.

The exclude/boost mechanics (keyed by `marca|categoria`, global, anonymous, no time decay — ADR-2 of the prior change) stay unchanged; only feedback GRANULARITY and the Botines rule change.

## Scope

### In Scope
- **Per-item feedback recording**: each `outfit_feedback` write becomes one row per rated item — a `(slot, url, liked)` tuple — instead of one shared `liked` across all four slot URLs. The feedback model derives exclude/boost from per-item verdicts.
- **Per-item feedback UI** in `OutfitsPanel.jsx`: replace the single outfit-level 👍/👎 with an independent 👍/👎 control on each slot card; `sendOutfitFeedback` posts per-item.
- **Global Botines exclusion** in `OutfitService`: Botines (the base calzado taxonomy entry) is NEVER eligible for any slot under any style, including `DEFAULT_STYLE_RULE` — not via a Gym-only whitelist.

### Out of Scope
- Brand-list additions (Bulks, Fuark already added directly to `NormalizerService.MARCAS`).
- Any change to `/api/mejores`, `/api/data`, `/api/tendencias`, or non-armador surfaces — Botines stay visible there.
- Per-user/session personalization; feedback stays global/anonymous (ADR-2, unchanged).
- Casual/Formal rulesets (still UI placeholders).
- Time-decay weighting (rejected previously, stays rejected).
- Excluding Borcego/Botas/Ojotas globally — only Botines is the confirmed global rule; the rest stay Gym-whitelist-only as today.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `outfit-feedback`: feedback granularity becomes per-item. The request and persistence MUST record one `liked` verdict per individual `(slot, url)`, and the exclude/boost model MUST be derived per item — a dislike on one slot's pair MUST NOT exclude the other slots' pairs. Pair-level exclude/boost semantics (marca+categoria, global, no decay) otherwise unchanged.
- `outfit-builder`: Botines MUST be hard-excluded from every slot for every style (including the default/no-restriction style), independent of any feedback or brand. The existing Gym calzado whitelist behavior for Borcego/Botas/Ojotas is unchanged.

## Approach

- **Per-item write path**: change the POST body contract so each slot carries its own `liked` (e.g. `slots:[{slot,url,liked}]`), and persist per item. Two storage options for `sdd-design` to pin: (A) keep the wide `outfit_feedback` row but store only the slots that were rated with their own liked flags (needs schema rethink since `liked` is row-level today), or (B) write one narrow row per rated item. Either way `obtenerOutfitFeedback`/`buildFeedbackModel` must yield per-item `(pair, liked)` facts, not a shared verdict broadcast to 4 slots.
- **Global Botines rule**: make `esCalzadoBase`/`esCalzadoElegible`/`slotDe` treat `"Botines"` as never eligible — a single style-independent gate that runs before (and regardless of) the StyleRule whitelist, so `DEFAULT_STYLE_RULE` also excludes it. Decouples the permanent rule from the Gym-only `STYLE_RULES` entry.
- **Frontend**: render a 👍/👎 pair per slot card in `OutfitCard`, track per-slot sent state, post per item.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `frontend/src/components/OutfitsPanel.jsx` | Modified | Per-slot 👍/👎 controls + per-slot sent state; `handleFeedback(slot, url, liked)` |
| `frontend/src/api.js` | Modified | `sendOutfitFeedback` posts per-item body |
| `web/ApiController.java` | Modified | `POST /outfits/feedback` parses per-item `liked`; persist per item; `buildFeedbackModel` derives exclude/boost from per-item facts |
| `db/DatabaseService.java` | Modified | `outfit_feedback` per-item write/read (schema change likely — `liked` must be per item, not per row); `OutfitFeedbackRow` reshaped |
| `web/OutfitService.java` | Modified | Style-independent Botines exclusion in `esCalzadoBase`/`esCalzadoElegible`/`slotDe` |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| `outfit_feedback` schema change strands existing per-outfit rows | Med | Additive migration; treat legacy rows as best-effort or ignore (low volume, single user); `sdd-design` pins migration |
| Botines gate accidentally affects `/api/mejores` or `/api/data` | Low | Change is confined to `OutfitService` slot eligibility; other surfaces never call it |
| Per-item UI clutters the outfit card | Low | Small inline 👍/👎 per slot; reuse existing button styling |
| Removing the outfit-level control breaks the "rate the whole look" flow | Low | Confirmed product decision: per-item is the desired model |

## Rollback Plan

No VCS reliance in-app — revert by file. Restore `OutfitsPanel.jsx` + `api.js` (single outfit-level feedback), `ApiController.java` (shared-`liked` parse + broadcast `buildFeedbackModel`), `DatabaseService.java` (`outfit_feedback` row shape + `OutfitFeedbackRow`), and `OutfitService.java` (drop the global Botines gate, keep Gym-only whitelist). If a schema column was added, leave it (additive, harmless) or drop via `ALTER`/table recreate. Rebuild: `cd frontend && npm run build` then `mvn -f scraper/pom.xml clean package -DskipTests`.

## Dependencies

- Prior archived change `outfit-recommendation-quality` (ADR-1 calzado taxonomy, ADR-2 feedback exclude/boost, ADR-3 style-aware eligibility) — this change extends it, keeping exclude/boost semantics, narrowing only granularity + the Botines rule.
- Live catalog `getLastResult().productos()` for the url→`marca`/`categoria` join (unchanged).
- Existing specs `openspec/specs/outfit-feedback/spec.md` and `outfit-builder/spec.md` (both get deltas).

## Success Criteria

- [ ] Disliking only the calzado item excludes only that item's `marca|categoria`; torso/piernas/accesorio pairs stay eligible.
- [ ] Liking only one item boosts only that item's pair; other slots' pairs are unaffected.
- [ ] The outfit card shows an independent 👍/👎 per visible slot.
- [ ] Botines never appears in any generated outfit under any style, including the default no-restriction style, regardless of feedback or brand.
- [ ] `/api/mejores`, `/api/data`, `/api/tendencias` still surface Botines (untouched).
- [ ] No regression to global/anonymous, no-time-decay exclude/boost semantics.
