# CLI REST Contract Specification

## Purpose

Defines the native CLI's interactive menu as a pure REST client of the
existing backend API — no new backend endpoints are introduced by this
change.

## Requirements

### Requirement: No New Backend Endpoints

The CLI MUST implement its interactive menu (scrape, retrain, status, site
CRUD, open dashboard) using only the existing REST endpoints:
`GET /api/status`, `POST /api/scrape` (params `precioMin`, `precioMax`,
`sitios`, `forceRetrain`), `POST /api/ml/entrenar`, and
`GET|POST|DELETE /api/sitios`. The CLI MUST NOT require or depend on any
backend endpoint not already present.

#### Scenario: Menu actions map to existing endpoints
- GIVEN the user selects "scrape" from the menu
- WHEN the CLI executes the action
- THEN it issues `POST /api/scrape` with the configured parameters against
  the existing backend, with no new endpoint added

#### Scenario: Status polling
- GIVEN a scrape is in progress
- WHEN the CLI polls for status
- THEN it calls `GET /api/status` and reflects the response in the menu

### Requirement: Structurally-Safe Site JSON Serialization

When the CLI sends site data to `POST /api/sitios` (or any endpoint carrying
user-entered site name/value strings), it MUST build the JSON payload using a
structurally-safe serializer (e.g. `json.dumps`) rather than string
concatenation or interpolation, so that no user-entered value can alter the
JSON structure or escape into a shell context.

#### Scenario: Hostile site name is safely encoded
- GIVEN the user enters a site name containing `a"b;$(x)`
- WHEN the CLI submits `POST /api/sitios`
- THEN the value is encoded as a single JSON string field
- AND no shell command is invoked with the raw value
- AND the JSON structure is not altered by the input
