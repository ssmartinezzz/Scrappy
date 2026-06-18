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

For the `torso` and `piernas` slots, the system MUST only consider products with `gymrat == true`. For the `calzado` slot, the system MUST only consider products whose `categoria` matches the footwear whitelist (`Zapatilla*` family), regardless of `gymrat` value, because footwear is structurally excluded from `gymrat` by `NormalizerService.esGymrat()`. The system MUST NOT modify `esCalzado()` or `esGymrat()` to make footwear gymrat-eligible.

#### Scenario: Footwear matched without gymrat flag
- GIVEN a product with `categoria = "Zapatilla Running"` and `gymrat = false`
- WHEN the calzado slot is sampled
- THEN the product is eligible as a calzado candidate

#### Scenario: Non-gymrat torso excluded
- GIVEN a product with `categoria = "Remera"` and `gymrat = false`
- WHEN the torso slot is sampled
- THEN the product is not eligible as a torso candidate

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

When a required slot (`torso`, `piernas`, `calzado`) has zero matching candidates, the system MUST apply fallback steps in order: (1) relax the price band constraint and retry the slot query; (2) if still zero matches, relax `genero` matching to unisex-only (all genders) and retry; (3) if still zero matches after both relaxations, the response MUST include a partial-result flag and a message indicating insufficient products, and MUST NOT return a slot with a null/fabricated product silently.

#### Scenario: Price band relaxation resolves a sparse slot
- GIVEN the calzado slot has zero candidates within the initial price band but has candidates outside it
- WHEN the outfit is generated
- THEN the system relaxes the price band and returns a complete outfit without a partial flag

#### Scenario: All fallback steps exhausted
- GIVEN the calzado slot has zero candidates under any price band or genero relaxation
- WHEN the outfit is generated
- THEN the response MUST set a partial-result flag and include a human-readable message stating insufficient products, and MUST NOT include a fabricated calzado product

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
