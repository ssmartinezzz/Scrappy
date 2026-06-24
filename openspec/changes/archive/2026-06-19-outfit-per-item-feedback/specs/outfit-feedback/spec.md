# Delta for Outfit Feedback

## MODIFIED Requirements

### Requirement: POST /api/outfits/feedback

The system MUST expose `POST /api/outfits/feedback` accepting a JSON body that carries one or more per-slot verdicts — each entry identifying the slot, the product `url`, and an independent `liked` boolean for that item — together with the `genero` used to generate the outfit. The system MUST persist one row per rated item (one `(slot, url, liked)` tuple) to the `outfit_feedback` table, and MUST respond with a success acknowledgement. The system MUST use this data to alter `GET /api/outfits` sampling per the Feedback-Driven Sampling requirement below.
(Previously: the request carried ONE shared `liked` for the whole outfit and persisted one row covering all submitted slot URLs.)

#### Scenario: Like submission persists a row per rated item
- GIVEN a generated outfit with torso, piernas, and calzado product URLs and `genero=hombre`
- WHEN the client calls `POST /api/outfits/feedback` rating only the calzado slot with `liked=true`
- THEN the system inserts exactly one row into `outfit_feedback` for the calzado `(slot, url)` with `liked=1`, and no row is written for torso or piernas
- AND the response is 200

#### Scenario: Dislike submission persists a row per rated item
- GIVEN the same generated outfit
- WHEN the client calls `POST /api/outfits/feedback` rating only the calzado slot with `liked=false`
- THEN the system inserts one row into `outfit_feedback` for the calzado `(slot, url)` with `liked=0` and responds 200

#### Scenario: Multiple slots rated in one submission stay independent
- GIVEN a generated outfit with torso, piernas, and calzado URLs
- WHEN the client calls `POST /api/outfits/feedback` with the torso slot `liked=true` and the calzado slot `liked=false` in the same request
- THEN the system inserts one row for torso with `liked=1` and one row for calzado with `liked=0`, each independently

#### Scenario: Feedback affects subsequent generation
- GIVEN an `outfit_feedback` row with `liked=0` exists for a product whose live-catalog `marca`+`categoria` is `Nike`+`Zapatilla`
- WHEN `GET /api/outfits` is called again with any `genero`
- THEN no candidate with `marca=Nike` and `categoria=Zapatilla` is eligible for any slot

### Requirement: outfit_feedback Schema

The system MUST create the `outfit_feedback` table additively via `CREATE TABLE IF NOT EXISTS`, following the existing style used by `favoritos`/`ml_output` in `DatabaseService.java` (snake_case columns, `INTEGER PRIMARY KEY AUTOINCREMENT` surrogate key, `TEXT NOT NULL` for required string fields). The table MUST store, at minimum: an id, the rated `slot` identifier, the rated item's product `url`, the `genero` used, a per-row `liked` boolean (stored as `INTEGER` 0/1) scoped to that single item, and a creation timestamp. Any pre-existing wide-row data from the prior per-outfit schema MAY be left in place or migrated additively; the system MUST NOT require a destructive schema change to apply this delta.
(Previously: a single row stored the torso/piernas/calzado/accesorio URLs together with one outfit-level `liked` value covering all of them.)

#### Scenario: Table created on startup if absent
- GIVEN a fresh `scraper.db` without an `outfit_feedback` table
- WHEN the application starts
- THEN the table is created with the required per-item columns and the application does not fail

#### Scenario: Existing table left untouched
- GIVEN `scraper.db` already has an `outfit_feedback` table from a prior run
- WHEN the application starts
- THEN no error occurs and existing rows are preserved

#### Scenario: Legacy per-outfit rows do not break per-item reads
- GIVEN `scraper.db` contains rows written under the prior per-outfit schema (one shared `liked` across multiple slot URLs)
- WHEN the application reads `outfit_feedback` to derive per-item exclude/boost facts
- THEN legacy rows are handled without raising an error, even if their per-item interpretation is best-effort

### Requirement: Feedback Scope Limitation

The system MUST NOT record outfit "impressions" (i.e., outfits generated but not rated). Only explicit like/dislike actions on individual items MUST be persisted. The system MUST apply pair-level (marca+categoria) hard-exclude on dislike and weight-boost on like when generating outfits, per the Feedback-Driven Sampling requirement; it MUST NOT implement any time-decay or per-user/session personalization of feedback in this change. A verdict recorded for one slot's item MUST NOT be broadcast to or inferred for any other slot in the same submission.
(Previously: did not state the no-broadcast constraint, because a single shared `liked` was inherently broadcast to every slot URL in the submission.)

#### Scenario: Generating an outfit without rating it
- GIVEN the user calls `GET /api/outfits` and never submits feedback
- WHEN no `POST /api/outfits/feedback` call is made
- THEN no row is written to `outfit_feedback` for that generation

#### Scenario: No time decay applied
- GIVEN a dislike row for a `marca`+`categoria` pair was created over 90 days ago
- WHEN a new outfit is generated
- THEN that pair remains hard-excluded with the same strength as a dislike recorded moments ago

#### Scenario: Dislike on one slot does not affect sibling slots
- GIVEN a generated outfit with torso, piernas, calzado, and accesorio items
- WHEN the client submits `liked=false` for only the calzado item
- THEN the torso, piernas, and accesorio items' `marca`+`categoria` pairs are NOT hard-excluded as a result of this submission

#### Scenario: Like on one slot does not boost sibling slots
- GIVEN a generated outfit with torso, piernas, calzado, and accesorio items
- WHEN the client submits `liked=true` for only the torso item
- THEN the piernas, calzado, and accesorio items' `marca`+`categoria` pairs receive no sampling-weight boost as a result of this submission

### Requirement: Feedback-Driven Sampling

On each outfit generation, the system MUST load all `outfit_feedback` rows and re-derive each referenced product's `marca` and `categoria` by joining the row's stored per-item `url` against the live in-memory catalog (`getLastResult().productos()`). A feedback row whose `url` is not present in the live catalog MUST be silently skipped (not treated as an error and not logged as a failure). For every `marca`+`categoria` pair with at least one item-level `liked=0` row, the system MUST hard-exclude all products with that exact `marca`+`categoria` pair from candidacy in every slot, for every `genero`, with no expiry. For every `marca`+`categoria` pair with at least one item-level `liked=1` row, the system MUST increase that pair's selection weight in `weightedRandomPick` relative to non-boosted pairs. This exclude/boost behavior MUST be applied globally — it MUST NOT vary by `genero`. Exclude/boost derivation MUST be computed strictly per rated item; the system MUST NOT broadcast a single row's verdict across multiple slots or pairs that were not individually rated.
(Previously: derivation joined the outfit-level row's torso/piernas/calzado/accesorio URLs together and applied the single shared `liked` value to all of their pairs at once.)

#### Scenario: Dislike excludes the pair across genero values
- GIVEN a disliked calzado item has `marca=Adidas`, `categoria=Zapatilla Running`
- WHEN outfits are generated for `genero=hombre`, `genero=mujer`, and `genero=unisex`
- THEN no product with `marca=Adidas` and `categoria=Zapatilla Running` is selected for any of the three requests

#### Scenario: Like increases sampling weight
- GIVEN a liked torso item has `marca=Nike`, `categoria=Remera`
- AND multiple eligible torso candidates exist including non-boosted Nike Remera products and other marca/categoria pairs
- WHEN many outfits are generated
- THEN products matching `marca=Nike`, `categoria=Remera` are selected more frequently than they would be under uniform weighting

#### Scenario: Feedback references a delisted product
- GIVEN an `outfit_feedback` row stores a `url` that no longer exists in `getLastResult().produtos()`
- WHEN outfit generation loads feedback rows
- THEN that row is skipped without raising an error and does not contribute any exclude or boost effect

#### Scenario: Exclude and boost coexist for different pairs
- GIVEN a dislike row for `marca=Puma`+`categoria=Zapatilla` and a like row for `marca=Nike`+`categoria=Zapatilla`
- WHEN the calzado slot is sampled
- THEN Puma Zapatilla products are absent from candidates and Nike Zapatilla products are weighted higher than unboosted candidates

#### Scenario: Disliking only calzado excludes only that pair, not sibling pairs from the same outfit
- GIVEN a single generated outfit with torso `marca=Nike`+`categoria=Remera`, piernas `marca=Adidas`+`categoria=Jogging`, and calzado `marca=Puma`+`categoria=Zapatilla`
- WHEN the client submits `liked=false` for only the calzado item
- THEN subsequent outfit generation hard-excludes `marca=Puma`+`categoria=Zapatilla` only
- AND `marca=Nike`+`categoria=Remera` and `marca=Adidas`+`categoria=Jogging` remain fully eligible candidates

## Open Questions (flagged for design/tasks)

- Exact JSON field/array names for the per-item request body (e.g. `slots:[{slot,url,liked}]` vs alternative naming) are not specified in the proposal — design must define the DTO contract.
- Whether a single submission may include verdicts for fewer than all generated slots (e.g. rate only calzado) is implied as supported by the per-item model, but the exact minimum/maximum item count per request is not specified — design must define validation bounds.
- Migration strategy for legacy per-outfit rows (best-effort read vs. ignore vs. backfill) is not pinned by the proposal — design must choose one.
