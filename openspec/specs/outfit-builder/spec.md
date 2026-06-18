# Outfit Builder Specification

## Purpose

Assemble a complete, coherent gym outfit (torso + piernas + calzado, optional accesorio) from the existing catalog on demand, filtered by `genero`, with a re-roll action. Other outfit categories (Casual, Formal, ...) are placeholder-only in this slice. No ML, no persistence of generated outfits — generation is stateless per request.

## Requirements

### Requirement: Slot Taxonomy

The system MUST map each known `categoria` value to exactly one outfit slot: `torso`, `piernas`, `calzado`, or `accesorio`, using the taxonomy in the proposal/explore (`Torso`: Puffer, Campera, Sweater, Buzo, Musculosa, Camisa, Remera, Chomba, Casaca, Chaleco, Saco, Traje, Piloto; `Piernas`: Calza, Baggy, Jean, Jogging, Short, Bermuda, Pollera, Pantalón; `Calzado`: any `Zapatilla*`, Botines, Borcego, Botas, Ojotas, Sneaker; `Accesorio`: Mochila, Bolso, Riñonera, Billetera, Cinturón, Bufanda, Guantes, Gorro, Gorra, Lentes, Medias). `categoria` values outside this taxonomy MUST NOT be assigned to any slot and MUST be excluded from outfit candidates.

#### Scenario: Known categoria maps to a slot
- GIVEN a product with `categoria = "Musculosa"`
- WHEN slot lookup runs
- THEN the product is eligible for the `torso` slot

#### Scenario: Unknown categoria is excluded
- GIVEN a product with `categoria = "Vestido"` (not in the gym slot taxonomy)
- WHEN slot lookup runs
- THEN the product is not eligible for any gym outfit slot

### Requirement: Gym Outfit Source Filtering

For the `torso` and `piernas` slots, the system MUST only consider products with `gymrat == true`. For the `calzado` slot, the system MUST only consider products that are both genero-eligible per the Genero Matching Policy AND eligible under the active style's calzado rule (see Style-Aware Slot Eligibility), regardless of `gymrat` value, because footwear is structurally excluded from `gymrat` by `NormalizerService.esGymrat()`. The system MUST NOT modify `esCalzado()` or `esGymrat()` to make footwear gymrat-eligible.

#### Scenario: Footwear matched without gymrat flag
- GIVEN a product with `categoria = "Zapatilla Running"` and `gymrat = false`
- WHEN the calzado slot is sampled for the Gym style
- THEN the product is eligible as a calzado candidate

#### Scenario: Non-gymrat torso excluded
- GIVEN a product with `categoria = "Remera"` and `gymrat = false`
- WHEN the torso slot is sampled
- THEN the product is not eligible as a torso candidate

#### Scenario: Dress footwear excluded from Gym calzado
- GIVEN a product with `categoria = "Botines"` and `gymrat = false`
- WHEN the calzado slot is sampled for the Gym style
- THEN the product is not eligible as a calzado candidate, even if no other calzado candidates exist for that price band

### Requirement: GET /api/outfits

The system MUST expose `GET /api/outfits?genero=X` where `genero` is one of `hombre`, `mujer`, `unisex`, or omitted. The response MUST contain one product per required slot (`torso`, `piernas`, `calzado`) and MAY contain one product for `accesorio`. Each call MUST produce an independently sampled combination (re-roll = calling the endpoint again).

#### Scenario: Successful generation for a given genero
- GIVEN the catalog has eligible products for torso, piernas, and calzado for `genero=hombre`
- WHEN the client calls `GET /api/outfits?genero=hombre`
- THEN the response is 200 with one product each for torso, piernas, calzado, and optionally accesorio

#### Scenario: Re-roll produces a different combination
- GIVEN a prior call to `GET /api/outfits?genero=hombre` returned outfit A
- WHEN the client calls `GET /api/outfits?genero=hombre` again
- THEN the system SHOULD return a different combination when more than one eligible candidate exists per slot

#### Scenario: Missing genero defaults to unisex-eligible
- WHEN the client calls `GET /api/outfits` without a `genero` parameter
- THEN the system MUST treat the request as unisex-eligible (per the Genero Matching Policy requirement) and respond 200 with a generated outfit if eligible products exist

#### Response shape (pinned, matches design.md)

```json
{ "genero": "hombre", "partial": false,
  "slots": [ {"slot": "torso", "sitio": "", "nombre": "", "precio": 0,
              "url": "", "img": "", "categoria": "", "marca": ""}, ... ] }
```

`slot` MUST be one of `torso`, `piernas`, `calzado`, `accesorio`. Field names MUST match this shape exactly — `img` (not `imagenUrl`), `slots` (not `items`).

### Requirement: Genero Matching Policy

The system MUST treat products with an empty or missing `genero` value as unisex-eligible. A request for `genero=hombre` or `genero=mujer` MUST match products whose `genero` equals the requested value, OR equals `unisex`, OR is empty/missing. A request for `genero=unisex` (or omitted) MUST match products whose `genero` is `unisex`, empty/missing, OR any gendered value.

#### Scenario: Gendered request includes unisex and empty genero products
- GIVEN candidate products with `genero` values `"hombre"`, `"unisex"`, and `""`
- WHEN the client requests `genero=hombre`
- THEN all three products are eligible candidates

### Requirement: Price-Band Coherence

The system SHOULD constrain sampled slot candidates to a shared price band so the assembled outfit is visually/economically coherent, before falling back per the Fallback Policy requirement.

#### Scenario: Outfit candidates within a coherent price range
- GIVEN eligible torso, piernas, and calzado candidates exist within a bounded price band
- WHEN an outfit is generated
- THEN all selected slot products fall within that price band

### Requirement: Fallback Policy

When a required slot (`torso`, `piernas`, `calzado`) has zero matching candidates after applying style eligibility (Style-Aware Slot Eligibility) and feedback hard-exclude (per the `outfit-feedback` Feedback-Driven Sampling requirement), the system MUST apply fallback steps in order: (1) relax the price band constraint and retry the slot query; (2) if still zero matches, relax `genero` matching to unisex-only (all genders) and retry; (3) if still zero matches after both relaxations, the response MUST include a partial-result flag and a message indicating insufficient products, and MUST NOT return a slot with a null/fabricated product silently. Style eligibility and feedback hard-exclude MUST both be applied as pre-filters before this fallback sequence runs, and MUST NOT themselves be relaxed by any fallback step.

#### Scenario: Price band relaxation resolves a sparse slot
- GIVEN the calzado slot has zero candidates within the initial price band but has candidates outside it
- WHEN the outfit is generated
- THEN the system relaxes the price band and returns a complete outfit without a partial flag

#### Scenario: All fallback steps exhausted
- GIVEN the calzado slot has zero candidates under any price band or genero relaxation
- WHEN the outfit is generated
- THEN the response MUST set a partial-result flag and include a human-readable message stating insufficient products, and MUST NOT include a fabricated calzado product

#### Scenario: Style exclusion empties a slot and fallback still cannot recover it
- GIVEN the only calzado candidates for `genero=hombre` within any price band are Botines/Borcego/Botas (all style-ineligible for Gym)
- WHEN a Gym outfit is generated
- THEN price-band and genero fallback steps run but do not relax the style filter, and the response sets a partial-result flag with no fabricated calzado product

#### Scenario: Feedback exclusion empties a slot and fallback recovers it
- GIVEN all calzado candidates within the initial price band are hard-excluded by a dislike pair, but eligible candidates exist outside that price band
- WHEN a Gym outfit is generated
- THEN the system relaxes the price band (without relaxing the feedback exclusion) and returns a complete outfit without a partial flag

### Requirement: Style-Aware Slot Eligibility

The system MUST define slot eligibility per outfit style using an extensible style-keyed structure (e.g. a `STYLE_RULES` map), so that adding a new style's eligibility rules MUST NOT require rewriting the core outfit-assembly flow (`armar()`). For the `Gym` style, the calzado rule MUST restrict eligible candidates to athletic footwear: `categoria` values matching the `Zapatilla*` prefix family, or `categoria = "Sneaker"`. The `Gym` style's calzado rule MUST exclude `Botines`, `Borcego`, `Botas`, and `Ojotas`, even though these categorias remain part of the general Slot Taxonomy's `calzado` slot. Only the `Gym` style ruleset MUST be implemented in this change; other styles remain unimplemented placeholders.

#### Scenario: New style can be added without rewriting armar()
- GIVEN a future change adds a `Casual` entry to the style-keyed eligibility structure
- WHEN `armar()` is invoked with the `Casual` style
- THEN the core assembly flow resolves eligibility via the style-keyed structure without requiring changes to `armar()`'s control flow

#### Scenario: Athletic footwear eligible for Gym
- GIVEN a product with `categoria = "Zapatilla Urbana"`
- WHEN the calzado slot is sampled for the `Gym` style
- THEN the product is eligible as a calzado candidate

#### Scenario: Sneaker eligible for Gym
- GIVEN a product with `categoria = "Sneaker"`
- WHEN the calzado slot is sampled for the `Gym` style
- THEN the product is eligible as a calzado candidate

#### Scenario: Dress/casual footwear ineligible for Gym
- GIVEN products with `categoria` values `"Botines"`, `"Borcego"`, `"Botas"`, and `"Ojotas"`
- WHEN the calzado slot is sampled for the `Gym` style
- THEN none of these products are eligible as calzado candidates

### Requirement: Combo Product Categorization

`NormalizerService.clasificar()` MUST detect when a single-SKU product represents a multi-piece/combo garment set, either because the product name contains a combo-indicating token (e.g. "conjunto", "combo", "set") or because the name simultaneously matches keyword triggers for both the `torso` block and the `piernas` block of the classifier. When either signal is detected, the system MUST NOT return the first-matched single categoria for that product. Instead it MUST resolve to a distinct, documented categoria value that is excluded from `OutfitService.CATEGORIA_SLOT`, so the product cannot be assigned to any outfit slot and cannot collide with a separately-picked `torso` or `piernas` item in the same generated outfit. This requirement governs categorization at scrape/normalization time only; it does NOT retroactively correct `categoria` values already persisted in `scraper.db` from prior scrapes — re-scraping (which re-normalizes every row via `ResultAggregator`) is the documented workaround, and no backfill migration is performed by this change.

The `KW_TRAJE` (suit) trigger is explicitly EXCLUDED from the `torso` block used by the dual-keyword check above. A suit always resolves to `Traje` (a torso categoria) regardless of whether its name also matches a `piernas`-block trigger, so that suits remain eligible for outfit recommendations rather than being silently excluded as a combo — removing suits from outfits entirely would be a product regression, not a fix.

This requirement is scoped under `outfit-builder` rather than as a separate `normalization` capability because its only externally observable effect is preventing a single combo product from occupying or colliding with an outfit slot; there is no existing or planned consumer of "normalization" as an independent capability, and splitting this single rule into its own spec would fragment one coherent slot-integrity guarantee across two documents without adding clarity.

#### Scenario: Name-based combo token excluded from slots
- GIVEN a product named "Conjunto Buzo + Jogging" with `categoria` otherwise resolvable to `Buzo` under sequential first-match
- WHEN `clasificar()` runs
- THEN the product is assigned a distinct combo categoria value, not `Buzo`, and that categoria is absent from `CATEGORIA_SLOT`

#### Scenario: Dual torso+piernas keyword hit without explicit combo token
- GIVEN a product name matches both a `KW_BUZO` (torso) trigger and a `KW_JOGGING` (piernas) trigger with no "conjunto"/"combo"/"set" token present
- WHEN `clasificar()` runs
- THEN the product is assigned the distinct combo categoria value rather than silently first-matching `Buzo`

#### Scenario: Traje (suit) is exempt from combo resolution
- GIVEN a product name matches the `KW_TRAJE` trigger alongside a `piernas`-block trigger
- WHEN `clasificar()` runs
- THEN the product is still assigned `Traje` (not the combo categoria), since `KW_TRAJE` is excluded from the dual-keyword combo check and suits remain eligible for outfit recommendations

#### Scenario: Single-piece product unaffected
- GIVEN a product named "Buzo Canguro Negro" matching only the `torso` block with no piernas-block trigger and no combo token
- WHEN `clasificar()` runs
- THEN the product is assigned `Buzo` as before, unaffected by this requirement

#### Scenario: Fix does not retroactively correct existing rows
- GIVEN `scraper.db` already contains a row with `categoria = "Buzo"` for a combo product scraped before this change
- WHEN the application starts after this change is deployed (no new scrape triggered)
- THEN that existing row's `categoria` remains `"Buzo"` until the next scrape re-normalizes it

### Requirement: Placeholder Outfit Categories

Non-gym outfit sub-tabs (Casual, Formal, and any other category besides Gym) MUST render a static "Próximamente" state in the frontend and MUST NOT issue any API call to `/api/outfits` or any other backend endpoint.

#### Scenario: Selecting a placeholder sub-tab
- GIVEN the user is on the Outfits tab
- WHEN the user selects the "Casual" sub-tab
- THEN the UI displays a "Próximamente" message and no network request is made

## Open Questions (resolved or carried to tasks)

- Exact price-band width: left to implementation (design specifies a ±X% window around the median; exact percentage to be picked during `sdd-tasks`/`sdd-apply`).
- `accesorio` resolved: best-effort only, no fallback (confirmed in design.md).
- Whether the fallback message/flag distinguishes "zero gymrat at all" vs "zero footwear only": not distinguished — the generic partial-result message covers both cases; acceptable for this slice.
