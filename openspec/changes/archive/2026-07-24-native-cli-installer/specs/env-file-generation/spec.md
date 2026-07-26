# Env File Generation Specification

## Purpose

Defines the CLI's idempotent, non-destructive `.env` generation policy,
replacing the installers' current unconditional overwrite of the root
`.env`.

## Requirements

### Requirement: Create-If-Absent Env Generation

When the root `.env` does not exist, the CLI MUST generate it from computed
defaults (installer-provisioned Postgres port/paths, computed CORS/API URLs)
covering at least: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`,
`SCRAPER_MODELS_ROOT`, `APP_CORS_ALLOWED_ORIGINS`, `VITE_API_BASE_URL`,
`APP_OPEN_URL`. When `.env` already exists, the CLI MUST NOT modify any
existing key's value.

#### Scenario: First run generates .env
- GIVEN no `.env` file exists at the repo root
- WHEN the CLI runs its env step
- THEN a `.env` file is created containing all required keys with computed
  defaults

#### Scenario: Existing values are untouched
- GIVEN `.env` exists with a hand-edited `DATABASE_PASSWORD`
- WHEN the CLI runs its env step
- THEN the existing `DATABASE_PASSWORD` value is unchanged

### Requirement: Additive Reconcile of Missing Keys

When `.env.example` contains a key absent from the user's existing `.env`,
the CLI MUST append that key with its default value to `.env`, and MUST NOT
alter any key already present.

#### Scenario: New key appended
- GIVEN `.env.example` gains a new key not present in the user's `.env`
- WHEN the CLI runs its env step
- THEN the new key is appended to `.env` with its default value
- AND all pre-existing keys and values remain unchanged

### Requirement: Secrets Never Echoed

The `.env` generator MUST NOT print secret values (`DATABASE_PASSWORD`, or
any credential-bearing value) to stdout or log output at any point.

#### Scenario: Password not printed
- GIVEN the CLI generates or reconciles `.env`
- WHEN it logs its progress
- THEN no log line contains the literal `DATABASE_PASSWORD` value

### Requirement: Frontend Env Generation Mirrors the Root Contract

`VITE_API_BASE_URL` is a build-time value Vite reads only from
`frontend/.env*` (never from the root `.env`, and never as a runtime `.env`
parse — see the "VITE_API_BASE_URL Build-Time Export Ordering" requirement
in `native-cli-orchestration`). Because this repo's root `.env.example`
intentionally does not declare `VITE_API_BASE_URL` as an active key (it
lives only in `frontend/.env.example`, per the `decouple-services-postgres`
D6 split), the CLI MUST also generate/reconcile `frontend/.env` from
`frontend/.env.example`, under the exact same create-if-absent +
additive-reconcile + never-overwrite + `--regenerate` contract as the root
`.env`, before invoking `npm run build`.

#### Scenario: Frontend .env generated alongside the root .env
- GIVEN no `frontend/.env` file exists
- WHEN the CLI runs its build step
- THEN `frontend/.env` is created from `frontend/.env.example` with computed
  defaults (including `VITE_API_BASE_URL`)
- AND `VITE_API_BASE_URL` is present in the environment passed to the
  `npm run build` subprocess

#### Scenario: Existing frontend/.env values are untouched
- GIVEN `frontend/.env` exists with a hand-edited `VITE_API_BASE_URL`
- WHEN the CLI runs its build step
- THEN the existing `VITE_API_BASE_URL` value is unchanged and is still the
  value passed to the `npm run build` subprocess

### Requirement: Explicit Force Regenerate Flag

The CLI MUST accept a `--regenerate` (or `--force`) flag that, when passed
explicitly, overwrites `.env` from computed defaults, bypassing the
create-if-absent and additive-reconcile behavior.

#### Scenario: Explicit regenerate overwrites
- GIVEN an existing `.env` with custom values
- WHEN the user runs the CLI with `--regenerate`
- THEN `.env` is rewritten from computed defaults

#### Scenario: Default run never triggers full overwrite
- GIVEN an existing `.env`
- WHEN the CLI runs without `--regenerate`/`--force`
- THEN no existing key is overwritten, regardless of drift from computed
  defaults
