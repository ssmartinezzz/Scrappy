# Outfit Feedback Specification

## Purpose

Collect like/dislike signal on generated outfits for a future learning loop. This slice is collection-only: feedback is persisted but MUST NOT influence outfit generation yet.

## Requirements

### Requirement: POST /api/outfits/feedback

The system MUST expose `POST /api/outfits/feedback` accepting a JSON body with the outfit's slot product URLs, the `genero` used to generate it, and a `liked` boolean. The system MUST persist one row per submission to the `outfit_feedback` table and MUST respond with a success acknowledgement. The system MUST NOT use this data to alter any `GET /api/outfits` sampling in this slice.

#### Scenario: Like submission persists a row
- GIVEN a generated outfit with torso, piernas, and calzado product URLs and `genero=hombre`
- WHEN the client calls `POST /api/outfits/feedback` with `liked=true` and the outfit's product URLs
- THEN the system inserts one row into `outfit_feedback` with `liked=1` and responds 200

#### Scenario: Dislike submission persists a row
- GIVEN the same generated outfit
- WHEN the client calls `POST /api/outfits/feedback` with `liked=false`
- THEN the system inserts one row into `outfit_feedback` with `liked=0` and responds 200

#### Scenario: Feedback does not affect generation
- GIVEN one or more `outfit_feedback` rows exist for a product combination
- WHEN `GET /api/outfits` is called again with the same `genero`
- THEN sampling behavior is unaffected by the existing feedback rows (no weighting applied)

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

The system MUST NOT record outfit "impressions" (i.e., outfits generated but not rated). Only explicit like/dislike actions MUST be persisted. The system MUST NOT implement any counter-based or co-occurrence weighting of feedback into generation in this change.

#### Scenario: Generating an outfit without rating it
- GIVEN the user calls `GET /api/outfits` and never submits feedback
- WHEN no `POST /api/outfits/feedback` call is made
- THEN no row is written to `outfit_feedback` for that generation

## Open Questions (flagged for design/tasks)

- Exact JSON field names/casing for the request body (e.g. `torsoUrl` vs `torso_url`) are not specified in the proposal — design must define the DTO contract.
- Whether feedback submission requires the full slot URL set (torso/piernas/calzado all present) or tolerates a partial outfit (e.g. fallback partial-result case) is not addressed in the proposal.
- No validation rule is specified for duplicate feedback (e.g. same outfit liked twice) — proposal does not state whether duplicates are allowed, deduped, or rejected.
