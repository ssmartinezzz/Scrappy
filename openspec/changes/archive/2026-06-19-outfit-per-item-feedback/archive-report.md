# Archive Report: outfit-per-item-feedback

**Date**: 2026-06-19  
**Change**: `outfit-per-item-feedback`  
**Status**: ARCHIVED / COMPLETE

## Executive Summary

Outfit per-item feedback and global Botines exclusion change is archived. All tasks completed and verified; deltas merged into main specs; change folder moved to archive; implementation applied to working tree and manually validated by user in browser.

## Artifacts Archived

| Artifact | Location | Type |
|----------|----------|------|
| Proposal | `openspec/changes/archive/2026-06-19-outfit-per-item-feedback/proposal.md` | Change proposal |
| Design | `openspec/changes/archive/2026-06-19-outfit-per-item-feedback/design.md` | Technical architecture |
| Specs (Feedback) | `openspec/changes/archive/2026-06-19-outfit-per-item-feedback/specs/outfit-feedback/spec.md` | Spec delta |
| Specs (Builder) | `openspec/changes/archive/2026-06-19-outfit-per-item-feedback/specs/outfit-builder/spec.md` | Spec delta |
| Tasks | `openspec/changes/archive/2026-06-19-outfit-per-item-feedback/tasks.md` | Work breakdown |

## Merged Specs

Main spec files updated with all deltas from this change:

- **`openspec/specs/outfit-feedback/spec.md`**
  - Changed feedback granularity from per-outfit to per-item
  - `outfit_feedback` table → `outfit_feedback_item` table (per-item rows with `(slot, url, liked)`)
  - `POST /api/outfits/feedback` now accepts `items:[{slot, url, liked}]` per-item array
  - No broadcast of feedback across slots; each item rated independently
  - Added evolution log noting 2026-06-19 change

- **`openspec/specs/outfit-builder/spec.md`**
  - Added Global Calzado Exclusion requirement: `Botines` hard-excluded from all styles
  - `Botines` evaluated independently before style-whitelist check via `CALZADO_VETADO` set in `slotDe()`
  - `Borcego`, `Botas`, `Ojotas` remain Gym-only whitelist-scoped (no scope creep)
  - Clarified Botines remains in taxonomy but not eligible; regression guard for `/api/data`, `/api/mejores`, etc.
  - Added evolution log noting 2026-06-19 change

## Implementation Status

### Database Layer
- [x] New `outfit_feedback_item(id, genero, slot, url, liked, created_at)` table created in `DatabaseService.crearTablas()`
- [x] Index `idx_ofi_liked` on liked column
- [x] `OutfitItemRow` record added; old `OutfitFeedbackRow` removed
- [x] `guardarOutfitFeedbackItem(genero, slot, url, liked)` method added
- [x] `obtenerOutfitFeedback()` repointed to new table

### Backend API
- [x] `ApiController.outfitFeedback()` rewritten to parse per-item `items:[{slot, url, liked}]`
- [x] Calls `db.guardarOutfitFeedbackItem()` once per item in loop
- [x] `buildFeedbackModel()` refactored: iterates one `OutfitItemRow`/row instead of 4-slot loop
- [x] Per-item exclude/boost (no broadcast across slots)

### Outfit Service
- [x] `CALZADO_VETADO = Set.of("Botines")` static set added
- [x] `slotDe()` first-line check returns null for vetoed categories
- [x] `esCalzadoBase()` and `esCalzadoElegible()` unchanged (Borcego/Botas/Ojotas unaffected)

### Frontend
- [x] `OutfitsPanel.jsx`: replaced outfit-level 👍/👎 with per-slot controls
- [x] Per-slot `handleFeedback(slot, url, liked)` posts one item per click
- [x] Per-slot sent-state tracking via `Set` of acted slot keys
- [x] `OutfitCard` renders independent buttons per slot, disabled after rating

### Verification (Manual)
- [x] Scenario 5.1: Dislike calzado-only → only that marca+categoria excluded; other slots eligible
- [x] Scenario 5.2: Botines excluded under Gym, DEFAULT_STYLE_RULE, and unmapped styles; Borcego/Botas/Ojotas remain eligible outside Gym
- [x] Scenario 5.3: `/api/data`, `/api/mejores`, `/api/facets` still surface Botines (regression guard)
- [x] Scenario 5.4: Build passes; Gym tab renders independent per-slot feedback controls

## Spec Evolution Trail

Both main specs now track changes:

- `outfit-feedback/spec.md` Evolution Log:
  - 2026-06-17: `outfit-recommendation-quality` (initial per-outfit feedback with pair-level exclude/boost)
  - 2026-06-19: `outfit-per-item-feedback` (granularity split to per-item)

- `outfit-builder/spec.md` Evolution Log:
  - 2026-06-17: `outfit-builder` (initial with Gym calzado whitelist)
  - 2026-06-19: `outfit-per-item-feedback` (global Botines veto added)

## Archive Folder Structure

```
openspec/changes/archive/2026-06-19-outfit-per-item-feedback/
├── proposal.md
├── design.md
├── specs/
│   ├── outfit-feedback/
│   │   └── spec.md
│   └── outfit-builder/
│       └── spec.md
├── tasks.md
└── archive-report.md  (this file)
```

## Traceability

All SDD artifacts from `outfit-per-item-feedback` change are preserved in the archive folder for historical review and traceability. Main specs (`openspec/specs/`) are the living forward reference; deltas in this archive show prior versions and change rationale.

## Notes

- Legacy `outfit_feedback` table (per-outfit schema) left in place in SQLite; harmless and additive per rollback plan.
- No backfill of legacy rows; new table read independently.
- Build verified green; user confirmed browser QA scenarios for all three verification cases.
- Change is production-ready pending final integration test in a full deploy cycle.
