# Outfit Feedback Specification

## Purpose

Collect like/dislike signal on generated outfits for a future learning loop. This slice is collection-only: feedback is persisted but MUST NOT influence outfit generation yet.

## Requirements

### Requirement: POST /api/outfits/feedback

The system MUST expose `POST /api/outfits/feedback` accepting a JSON body with the outfit's slot product URLs, the `genero` used to generate it, and a `liked` boolean. The system MUST persist one row per submission to the `outfit_feedback` table and MUST respond with a success acknowledgement. The system MUST use this data to alter `GET /api/outfits` sampling per the Feedback-Driven Sampling requirement below.

#### Scenario: Like submission persists a row
- GIVEN a generated outfit with torso, piernas, and calzado product URLs and `genero=hombre`
- WHEN the client calls `POST /api/outfits/feedback` with `liked=true` and the outfit's product URLs
- THEN the system inserts one row into `outfit_feedback` with `liked=1` and responds 200

#### Scenario: Dislike submission persists a row
- GIVEN the same generated outfit
- WHEN the client calls `POST /api/outfits/feedback` with `liked=false`
- THEN the system inserts one row into `outfit_feedback` with `liked=0` and responds 200

#### Scenario: Feedback affects subsequent generation
- GIVEN a `outfit_feedback` row with `liked=0` exists for a product whose live-catalog `marca`+`categoria` is `Nike`+`Zapatilla`
- WHEN `GET /api/outfits` is called again with any `genero`
- THEN no candidate with `marca=Nike` and `categoria=Zapatilla` is eligible for any slot

### Requirement: outfit_feedback Schema

The system MUST create the `outfit_feedback` table additively via `CREATE TABLE IF NOT EXISTS`, following the existing style used by `favoritos`/`ml_output` in `DatabaseService.java` (snake_case columns, `INTEGER PRIMARY KEY AUTOINCREMENT` surrogate key, `TEXT NOT NULL` for required string fields). The table MUST store, at minimum: an id, the torso/piernas/calzado product URLs, the optional accesorio URL, the `genero` used, the `liked` boolean (stored as `INTEGER` 0/1), and a creation timestamp.

#### Scenario: Table created on startup if absent
- GIVEN a fresh `scraper.db` without an `outfit_feedback` table
- WHEN the application starts
- THEN the table is created with the required columns and the application does not fail

#### Scenario: Existing table left untouched
- GIVEN `scraper.db` already has an `outfit_feedback` table from a prior run
- WHEN the application starts
- THEN no error occurs and existing rows are preserved

### Requirement: Feedback Scope Limitation

The system MUST NOT record outfit "impressions" (i.e., outfits generated but not rated). Only explicit like/dislike actions MUST be persisted. The system MUST apply pair-level (marca+categoria) hard-exclude on dislike and weight-boost on like when generating outfits, per the Feedback-Driven Sampling requirement; it MUST NOT implement any time-decay or per-user/session personalization of feedback in this change.

#### Scenario: Generating an outfit without rating it
- GIVEN the user calls `GET /api/outfits` and never submits feedback
- WHEN no `POST /api/outfits/feedback` call is made
- THEN no row is written to `outfit_feedback` for that generation

#### Scenario: No time decay applied
- GIVEN a dislike row for a `marca`+`categoria` pair was created over 90 days ago
- WHEN a new outfit is generated
- THEN that pair remains hard-excluded with the same strength as a dislike recorded moments ago

### Requirement: Feedback-Driven Sampling

On each outfit generation, the system MUST load all `outfit_feedback` rows and re-derive each referenced product's `marca` and `categoria` by joining the row's stored `url` (torso/piernas/calzado/accesorio) against the live in-memory catalog (`getLastResult().productos()`). A feedback row whose `url` is not present in the live catalog MUST be silently skipped (not treated as an error and not logged as a failure). For every `marca`+`categoria` pair with at least one `liked=0` row, the system MUST hard-exclude all products with that exact `marca`+`categoria` pair from candidacy in every slot, for every `genero`, with no expiry. For every `marca`+`categoria` pair with at least one `liked=1` row, the system MUST increase that pair's selection weight in `weightedRandomPick` relative to non-boosted pairs. This exclude/boost behavior MUST be applied globally — it MUST NOT vary by `genero`.

#### Scenario: Dislike excludes the pair across genero values
- GIVEN a disliked outfit's calzado product has `marca=Adidas`, `categoria=Zapatilla Running`
- WHEN outfits are generated for `genero=hombre`, `genero=mujer`, and `genero=unisex`
- THEN no product with `marca=Adidas` and `categoria=Zapatilla Running` is selected for any of the three requests

#### Scenario: Like increases sampling weight
- GIVEN a liked outfit's torso product has `marca=Nike`, `categoria=Remera`
- AND multiple eligible torso candidates exist including non-boosted Nike Remera products and other marca/categoria pairs
- WHEN many outfits are generated
- THEN products matching `marca=Nike`, `categoria=Remera` are selected more frequently than they would be under uniform weighting

#### Scenario: Feedback references a delisted product
- GIVEN an `outfit_feedback` row stores a `url` that no longer exists in `getLastResult().productos()`
- WHEN outfit generation loads feedback rows
- THEN that row is skipped without raising an error and does not contribute any exclude or boost effect

#### Scenario: Exclude and boost coexist for different pairs
- GIVEN a dislike row for `marca=Puma`+`categoria=Zapatilla` and a like row for `marca=Nike`+`categoria=Zapatilla`
- WHEN the calzado slot is sampled
- THEN Puma Zapatilla products are absent from candidates and Nike Zapatilla products are weighted higher than unboosted candidates

## Open Questions (flagged for design/tasks)

- Exact JSON field names/casing for the request body (e.g. `torsoUrl` vs `torso_url`) are not specified in the proposal — design must define the DTO contract.
- Whether feedback submission requires the full slot URL set (torso/piernas/calzado all present) or tolerates a partial outfit (e.g. fallback partial-result case) is not addressed in the proposal.
- No validation rule is specified for duplicate feedback (e.g. same outfit liked twice) — proposal does not state whether duplicates are allowed, deduped, or rejected.
