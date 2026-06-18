# Archive Report: outfit-builder (Outfit Builder + Outfit Feedback)

**Archived:** 2026-06-17  
**Status:** CLOSED  
**Verdict:** SUCCESS  

---

## Executive Summary

The outfit-builder change has been successfully completed, verified, and archived. Both new capabilities (GYM outfit assembly and like/dislike feedback collection) are implemented and shipping. All 29 implementation tasks were completed. Two CRITICAL bugs discovered during verification were fixed and re-verified live. The outfit feedback schema was updated to match spec requirements. All non-blocking notes have been documented. The change is ready for production.

---

## What Was Shipped

### Capability 1: Outfit Builder (GYM Outfit Assembly)

**Problem:** The catalog holds every garment needed to assemble a complete outfit, but users must mentally combine items by scrolling. There is no way to see a complete, coherent outfit displayed as a unified product.

**Solution:** A new "Outfits" top-level tab with a Gym sub-tab that generates complete outfits on demand. Users select a genero (hombre/mujer/unisex), and the system assembles one random-but-weighted outfit from the catalog: torso + piernas + calzado (required) + accesorio (optional). A re-roll button regenerates a different combo.

**Implementation:**
- Backend service `OutfitService.java`: static `categoria → slot` taxonomy map, price-band-weighted sampling (±30% band), 3-step fallback (price band → genero → partial flag)
- Two new API endpoints:
  - `GET /api/outfits?genero=X` → assembles and returns a complete outfit
  - `POST /api/outfits/feedback` → accepts like/dislike signal on generated outfit
- Frontend panel `OutfitsPanel.jsx`: sub-tab strip (Gym | Casual | Formal), genero selector, outfit card with re-roll and 👍/👎 buttons, placeholder "Próximamente" tabs for non-gym categories
- Routing: new `/outfits` route in `App.jsx`/`AppLayout.jsx` with NavLink nav entry

**Files Modified/Created:**
- `scraper/src/main/java/ar/scraper/web/OutfitService.java` (new, ~180 lines)
- `scraper/src/main/java/ar/scraper/web/ApiController.java` (modified, +2 endpoints, ~50 lines)
- `frontend/src/components/OutfitsPanel.jsx` (new, ~220 lines)
- `frontend/src/components/AppLayout.jsx` (modified, +lazy import/NavLink, ~5 lines)
- `frontend/src/App.jsx` (modified, +1 route, ~2 lines)
- `frontend/src/api.js` (modified, +2 fetch helpers, ~20 lines)

**Key Design Decisions:**
- ADR-1: Footwear slot uses a `categoria` whitelist (Zapatilla*, Botines, etc.) independent of `gymrat` flag, because footwear is structurally excluded from gym-gear tagging by design
- ADR-2: Feedback is collection-only; no weighting of samples yet (zero training signal on day one)
- ADR-3: Assembly reads from in-memory aggregated catalog, not the DB (consistent with other read endpoints)

### Capability 2: Outfit Feedback (Like/Dislike Collection)

**Problem:** No outfit-affinity data exists. Future learning loops need real feedback signal, not fabricated impressions.

**Solution:** Persist like/dislike votes on generated outfits for future weighted sampling. Collection is explicit (via 👍/👎 buttons) — no impression rows recorded.

**Implementation:**
- New `outfit_feedback` SQLite table (CREATE TABLE IF NOT EXISTS) with columns: id, torso_url, piernas_url, calzado_url, accesorio_url, genero, liked (0/1), created_at
- `DatabaseService.guardarOutfitFeedback()` method mirrors existing `guardarFavorito()` insert pattern
- Frontend buttons call `sendOutfitFeedback()` with the outfit's slot URLs and like/dislike boolean

**Files Modified:**
- `scraper/src/main/java/ar/scraper/db/DatabaseService.java` (modified, +DDL+method, ~25 lines)
- `scraper/src/main/java/ar/scraper/web/ApiController.java` (modified, +1 endpoint, ~20 lines)
- `frontend/src/components/OutfitsPanel.jsx` (modified, +like/dislike button handlers)

---

## Verification Results

**Verdict:** PASS (with non-blocking notes)

### Build & Runtime
- Build: ✅ SUCCESS (both frontend `npm run build` and backend Maven fat JAR)
- Tests: N/A (project has no test infrastructure; `tdd: false` in config.yaml)
- Manual verification: All 29 tasks completed and verified

### Spec Compliance
| Scenario | Status | Notes |
|----------|--------|-------|
| Slot taxonomy | ✅ PASS | 13 torso, 8 piernas, 6 calzado, 10 accesorio categories correctly mapped |
| Gym outfit source filtering | ✅ PASS | Torso/piernas require `gymrat==true`; calzado uses `categoria` whitelist only |
| Genero matching policy | ✅ PASS (after fix) | Empty genero treated unisex-eligible; gendered requests include unisex+empty products |
| Price-band coherence | ✅ PASS | ±30% band around median constrains outfit visual coherence |
| Fallback policy | ✅ PASS (after fix) | 3-step fallback (price band → genero → partial) working correctly |
| GET /api/outfits | ✅ PASS | 200 response with populated slots (or 204 if no catalog loaded) |
| Re-roll | ✅ PASS | Consecutive API calls return different slot picks when candidates ≥2 |
| Missing genero defaults to unisex | ✅ PASS | Omitted `genero` param matches all products (tested live) |
| POST /api/outfits/feedback | ✅ PASS | Row persists to `outfit_feedback` table with correct data |
| Placeholder tabs no-call guarantee | ✅ PASS | Code review confirms Casual/Formal tabs have zero network calls (structural) |

### Issues Found & Fixed

**CRITICAL Issues (both fixed before archive):**

1. **Genero Matching Policy violation — explicit genero=unisex did not match gendered products**
   - Root cause: `OutfitService.generoElegible()` treated explicit `genero=unisex` differently from omitted `genero` (null/blank)
   - Spec requirement: "A request for genero=unisex (or omitted) MUST match products whose genero is unisex, empty/missing, OR any gendered value"
   - Fix: Modified `generoElegible()` to treat `genero.equals("unisex")` the same as null/blank → match any product genero
   - Verification: Re-tested live; `GET /api/outfits?genero=unisex` now returns same result shape as omitted `genero`

2. **Fallback Policy step 2 no-op — genero relaxation did not relax to all genders**
   - Root cause: Step 2 called `filtrar()` with literal string "unisex", which routed through the same broken `generoElegible()` (see above)
   - Spec requirement: "relax genero matching to unisex-only (all genders)" — this step was supposed to try ANY genero-tagged product
   - Fix: Fixed as a side effect of CRITICAL #1; now `generoElegible("unisex")` correctly matches all genero values
   - Verification: Confirmed in live re-test; the fallback sequence now works as specified

**WARNING (fixed before archive):**

1. **outfit_feedback schema shape diverged from spec literal text**
   - Initial implementation: `slots_json TEXT` blob (single JSON array, matching design.md's pinned DDL)
   - Spec.md literal: "MUST store, at minimum: an id, the torso/piernas/calzado product URLs, the optional accesorio URL..." — read literally suggests discrete URL columns
   - Fix: Changed schema to discrete columns (`torso_url`, `piernas_url`, `calzado_url`, `accesorio_url`, all TEXT, all nullable except genero/liked/created_at)
   - Verification: Confirmed via PRAGMA table_info; new POST request persists rows with correct column values

**NON-BLOCKING Notes:**

1. **apply-progress.md task-count typo** — document reports "26/26 tasks complete" but `tasks.md` contains 29 checkbox items, all checked. Actual completion: 29/29. Cosmetic, does not affect implementation.

2. **Happy-path observation gap** — the scenario "complete outfit, all 3 required slots, no partial flag" was never observed live against a real gym-tagged catalog. Both apply-progress batches honestly document this: the only catalog available (freres+vcp, 1051 products total, both Shopify streetwear/boutique sites) has zero `gymrat==true` products. Implementation is structurally correct by code review and was verified correct via synthetic reproduction (standalone harness test showing a real 3-slot outfit for a 3-product gym-tagged catalog). Recommendation: Run a future verification pass once a gym-focused site is added to the scraper config.

---

## Delta Specs Merged to Main Specs

Two new domain specifications were created during this change:

| Domain | Delta Location | Action | Merged To |
|--------|---|--------|-----------|
| outfit-builder | `openspec/changes/outfit-builder/specs/outfit-builder/spec.md` | Created (full spec) | `openspec/specs/outfit-builder/spec.md` |
| outfit-feedback | `openspec/changes/outfit-builder/specs/outfit-feedback/spec.md` | Created (full spec) | `openspec/specs/outfit-feedback/spec.md` |

Both are new domains with no pre-existing main specs, so the delta specs were copied directly to main specs as the authoritative source of truth.

---

## Implementation Summary

**Total Implementation Tasks:** 29  
**Completed:** 29 (100%)  
**Code Changes:** ~520-600 lines across 7 files

| Phase | Group | Tasks | Status | Notes |
|-------|-------|-------|--------|-------|
| 1-3 | Backend Foundation | 13 | ✅ Complete | OutfitService (slot map, sampler, fallback), DB table, 2 API endpoints |
| 4-6 | Frontend | 8 | ✅ Complete | Panel, tabs, card, routing/nav wiring, API client helpers |
| 7 | Build & Verify | 8 | ✅ Complete | Builds, manual end-to-end testing, fallback scenarios |

**Dependencies Added:** None  
**Build Changes:** None (existing Maven/npm configs)  
**Database Migrations:** None (additive `CREATE TABLE IF NOT EXISTS` style)

---

## Architecture Decisions Documented

- **ADR-1: Footwear slot bypasses `gymrat`, uses a `categoria` whitelist** — to avoid breaking existing GYMRAT badge semantics
- **ADR-2: Learning is collection-only** — feedback persisted, not read back into sampling yet
- **ADR-3: Read from in-memory aggregate, not DB** — consistent with other read endpoints

---

## Files in Archive

This archive contains:
- `proposal.md` — business intent, scope, approach, risks
- `design.md` — technical decisions, data flow, interfaces, ADRs
- `specs/outfit-builder/spec.md` — outfit assembly requirements & scenarios
- `specs/outfit-feedback/spec.md` — feedback collection requirements & scenarios
- `tasks.md` — 29 implementation tasks (all checked)
- `apply-progress.md` — implementation notes, verification results, deviations
- `verify-report.md` — verification verdict, spec compliance, issues found & fixed
- `explore.md` — codebase exploration, approach evaluation

All artifacts are complete and immutable; this archive is the audit trail for this change.

---

## Recommendations for Fast-Follow

1. **Gym-focused catalog addition** — To close the "happy-path never observed" gap, add a gym-specific site (e.g., site focused on fitness equipment, training apparel) and re-run a verification pass observing the full happy-path (all 3 required slots populated, `partial=false`) end-to-end.

2. **Weighted sampling based on feedback** — Once sufficient like/dislike rows accumulate in `outfit_feedback`, implement ADR-2's fast-follow: wire feedback counters into the slot sampling to favor combinations users prefer.

3. **Outfit history / sharing** — Currently out of scope; scope for a future change to persist generated outfits, allow users to revisit favorites, or share outfits.

---

## SDD Cycle Complete

This change has been fully planned (proposal, spec, design), implemented (tasks, apply batches, build verification), verified (live tests, spec compliance matrix, bug fixes), and archived. The active change folder `openspec/changes/outfit-builder/` has been moved to the archive; no in-progress work remains.

**Next Step:** Start a new `/sdd-new` change if additional work is needed, or continue with other SDD changes in the backlog.
