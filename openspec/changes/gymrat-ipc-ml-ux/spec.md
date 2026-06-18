# Spec: gymrat-ipc-ml-ux

**Change**: gymrat-ipc-ml-ux
**Date**: 2026-06-17
**Artifact store**: openspec
**Status**: ready-for-design

## Summary

This change makes three already-computed backend capabilities visible in the dashboard SPA:
GYMRAT classification with sub-labels and filtering, IPC-adjusted purchase signals, and ML model transparency. No new endpoints, dependencies, or database tables are introduced.

## Domain Specs

| Domain | File | Type | Requirements | Scenarios |
|--------|------|------|-------------|-----------|
| frontend | specs/frontend/spec.md | Full (new) | 8 added | 22 |
| ml-pipeline | specs/ml-pipeline/spec.md | Full (new) | 3 added | 7 |
| api | specs/api/spec.md | Full (new) | 2 added | 5 |

## Constraints

- MUST NOT add new npm/Maven/pip dependencies.
- MUST NOT create new REST endpoints (reuse existing contracts).
- MUST NOT alter `Product` record fields other than confirming `gymrat: boolean` is already serialized.
- MUST NOT change SQLite schema.
- All frontend changes MUST be in the single `resources/static/index.html` file (vanilla JS/CSS, no build step).
