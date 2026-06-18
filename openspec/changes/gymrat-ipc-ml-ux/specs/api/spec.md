# API Specification — gymrat-ipc-ml-ux

## Purpose

Specifies the API contract additions required for this change.
All endpoints listed here already exist; this spec documents the fields
that MUST be serialized so the frontend can consume them.

## Requirements

### Requirement: /api/data Serializes gymrat Field

`GET /api/data` MUST include `gymrat: boolean` in every product object returned.
The endpoint already supports the `gymrat=true` query parameter for filtering;
this requirement ensures the field is present in the JSON response body so the
frontend can render gym badges and derive sub-labels without a second request.

#### Scenario: gymrat field present in product JSON

- GIVEN a request to `/api/data`
- WHEN the response is received
- THEN every product object contains a `gymrat` boolean field (true or false)

#### Scenario: gymrat filter still works

- GIVEN a request to `/api/data?gymrat=true`
- WHEN the response is received
- THEN every product object in the result has `gymrat: true`

#### Scenario: gymrat filter not applied returns all products

- GIVEN a request to `/api/data` with no `gymrat` parameter
- WHEN the response is received
- THEN both gym and non-gym products appear in the result

---

### Requirement: Existing Endpoints Remain Unchanged

`/api/inflacion`, `/api/recomendacion`, `/api/ml/estado`, and `/api/tendencias`
MUST NOT change their response contracts. The frontend consumes them as-is.

#### Scenario: /api/inflacion contract unchanged

- GIVEN a request to `/api/inflacion`
- WHEN the response is received
- THEN it contains at minimum: `mensual`, `interanual`, `actualizado` fields

#### Scenario: /api/recomendacion contract unchanged

- GIVEN a request to `/api/recomendacion?url={url}`
- WHEN the response is received
- THEN it contains at minimum: `senal`, `emoji`, `mensaje`, `scoreCompra`, `cambioReal`

#### Scenario: /api/ml/estado contract unchanged

- GIVEN a request to `/api/ml/estado`
- WHEN the response is received
- THEN it contains at minimum: `hasTextModel`, `textMeta` (with `accuracy`, `samples`, `clases`), `training` (with `running`, `phase`, `pct`, `msg`)
