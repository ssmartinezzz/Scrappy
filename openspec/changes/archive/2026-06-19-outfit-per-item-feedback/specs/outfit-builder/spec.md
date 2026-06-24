# Delta for Outfit Builder

## MODIFIED Requirements

### Requirement: Slot Taxonomy

The system MUST map each known `categoria` value to exactly one outfit slot: `torso`, `piernas`, `calzado`, or `accesorio`, using the taxonomy in the proposal/explore (`Torso`: Puffer, Campera, Sweater, Buzo, Musculosa, Camisa, Remera, Chomba, Casaca, Chaleco, Saco, Traje, Piloto; `Piernas`: Calza, Baggy, Jean, Jogging, Short, Bermuda, Pollera, Pantalón; `Calzado`: any `Zapatilla*`, Botines, Borcego, Botas, Ojotas, Sneaker; `Accesorio`: Mochila, Bolso, Riñonera, Billetera, Cinturón, Bufanda, Guantes, Gorro, Gorra, Lentes, Medias). `categoria` values outside this taxonomy MUST NOT be assigned to any slot and MUST be excluded from outfit candidates. `Botines` remains part of the general `calzado` taxonomy bucket for slot-mapping purposes (i.e. it still routes to the `calzado` slot type), but its candidacy is hard-excluded at the eligibility layer per the Global Calzado Exclusion requirement below — taxonomy membership and final eligibility are distinct concerns.
(Previously: did not state any distinction between taxonomy membership and eligibility for `Botines`, since no style-independent exclusion existed.)

#### Scenario: Known categoria maps to a slot
- GIVEN a product with `categoria = "Musculosa"`
- WHEN slot lookup runs
- THEN the product is eligible for the `torso` slot

#### Scenario: Unknown categoria is excluded
- GIVEN a product with `categoria = "Vestido"` (not in the gym slot taxonomy)
- WHEN slot lookup runs
- THEN the product is not eligible for any gym outfit slot

#### Scenario: Botines still maps to the calzado slot type at the taxonomy layer
- GIVEN a product with `categoria = "Botines"`
- WHEN slot lookup runs (taxonomy mapping only, before eligibility filtering)
- THEN the product is classified as belonging to the `calzado` slot type, even though it will be excluded from actual candidacy by the Global Calzado Exclusion requirement

### Requirement: Style-Aware Slot Eligibility

The system MUST define slot eligibility per outfit style using an extensible style-keyed structure (e.g. a `STYLE_RULES` map), so that adding a new style's eligibility rules MUST NOT require rewriting the core outfit-assembly flow (`armar()`). For the `Gym` style, the calzado rule MUST restrict eligible candidates to athletic footwear: `categoria` values matching the `Zapatilla*` prefix family, or `categoria = "Sneaker"`. The `Gym` style's calzado rule MUST exclude `Botines`, `Borcego`, `Botas`, and `Ojotas`, even though these categorias remain part of the general Slot Taxonomy's `calzado` slot. Only the `Gym` style ruleset MUST be implemented in this change; other styles remain unimplemented placeholders. This style-keyed whitelist mechanism governs `Borcego`, `Botas`, and `Ojotas` exclusively for the `Gym` style — it MUST NOT be the mechanism used to exclude `Botines`, which is excluded globally and independently of any style (see Global Calzado Exclusion). `Borcego`, `Botas`, and `Ojotas` MUST remain eligible under any future/unmapped style that does not explicitly restrict them, exactly as before this change — this requirement narrows nothing beyond the `Botines` case.
(Previously: did not address whether any calzado exclusion was style-independent; all calzado restrictions were scoped entirely to the `Gym` style's whitelist.)

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

#### Scenario: Borcego/Botas/Ojotas remain Gym-whitelist-scoped only (no scope creep)
- GIVEN a product with `categoria = "Borcego"` and a hypothetical future style or `DEFAULT_STYLE_RULE` that does not explicitly restrict calzado
- WHEN the calzado slot is sampled under that non-Gym style
- THEN the product remains eligible as a calzado candidate, because the `Gym`-only whitelist that excludes `Borcego` does not apply outside the `Gym` style, and no global rule excludes `Borcego`

### Requirement: Global Calzado Exclusion

The system MUST hard-exclude `categoria = "Botines"` from calzado candidacy in every slot sampling path, under every outfit style, including `DEFAULT_STYLE_RULE` (the default/no-restriction style applied when a style has no `STYLE_RULES` entry). This exclusion MUST be evaluated independently of, and prior to, any style-keyed whitelist check, so that no current or future style definition can re-admit `Botines` by omission. The exclusion MUST be independent of `marca`, `genero`, price band, or any outfit-feedback exclude/boost state — `Botines` MUST NOT become eligible even if a like-driven boost exists for its `marca`+`categoria` pair. This requirement MUST NOT be construed to extend to `Borcego`, `Botas`, or `Ojotas`; those categorias remain governed exclusively by the Style-Aware Slot Eligibility requirement's `Gym`-only whitelist, unchanged by this change.

#### Scenario: Botines excluded under the default/no-restriction style
- GIVEN a product with `categoria = "Botines"` and a style with no entry in `STYLE_RULES` (falls back to `DEFAULT_STYLE_RULE`)
- WHEN the calzado slot is sampled for that style
- THEN the product is not eligible as a calzado candidate

#### Scenario: Botines excluded under the Gym style
- GIVEN a product with `categoria = "Botines"`
- WHEN the calzado slot is sampled for the `Gym` style
- THEN the product is not eligible as a calzado candidate

#### Scenario: Botines excluded even with no other calzado candidates available
- GIVEN the only calzado candidates for a given `genero` and price band are all `categoria = "Botines"`
- WHEN an outfit is generated for any style
- THEN none of the Botines products are selected, and the slot instead proceeds through the Fallback Policy (price-band/genero relaxation, then partial-result) rather than admitting Botines

#### Scenario: A like-driven boost does not override the global Botines exclusion
- GIVEN an `outfit_feedback` like row exists whose `marca`+`categoria` pair resolves to a `Botines` product's `marca`+`categoria`
- WHEN the calzado slot is sampled for any style
- THEN the boosted pair's Botines products are still not eligible as calzado candidates

#### Scenario: Borcego/Botas/Ojotas are unaffected by the global exclusion
- GIVEN products with `categoria` values `"Borcego"`, `"Botas"`, and `"Ojotas"`
- WHEN the calzado slot is sampled for the default/no-restriction style (not `Gym`)
- THEN all three remain eligible as calzado candidates, because the Global Calzado Exclusion applies only to `Botines`

#### Scenario: Non-armador surfaces continue to show Botines (regression guard)
- GIVEN the catalog contains active `Botines` products
- WHEN `/api/mejores`, `/api/data`, or `/api/tendencias` is called
- THEN Botines products are returned/displayed exactly as before this change, because the Global Calzado Exclusion is scoped to `OutfitService` slot-eligibility logic only and is never consulted by these endpoints

## Open Questions (flagged for design/tasks)

- Whether `Global Calzado Exclusion` is implemented as a pre-check inside `esCalzadoElegible`/`slotDe` or as a separate gate function composed before style-whitelist evaluation is left to `sdd-design` — this spec only pins the observable behavior (Botines never eligible, Borcego/Botas/Ojotas unaffected outside Gym).
