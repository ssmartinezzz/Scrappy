# Archive Report: OpenAPI Contract Documentation & Bidirectional Drift Guard

**Change**: `openapi-swagger-docs`
**Archived**: 2026-09-03
**Artifact store mode**: `hybrid` (openspec + Engram)
**Branch**: `feat/openapi-contract-and-drift-guard`

---

## Executive Summary

The `openapi-swagger-docs` change has been successfully archived and closed. All 32 implementation tasks completed, verification passed with 1 WARNING (resolved post-verify), and both commits (`8c18a7d` and `78126e2`) are confirmed green with the full test suite at 2013/0/0/7. The delta specification for `api-contract-documentation` capability has been merged into the main specs tree at `openspec/specs/api-contract-documentation/spec.md`. The change folder has been moved to `openspec/changes/archive/2026-09-03-openapi-swagger-docs/`.

---

## Verification Summary

**Final Verdict**: PASS

Per the launch prompt's authoritative final-state facts (which supersede intermediate snapshots):
- **Verify verdict**: PASS with 0 CRITICAL, 0 SUGGESTION, 1 WARNING
- **WARNING status**: RESOLVED — the "generated" vs. "hand-written" inconsistency across three places (`docs/openapi.yaml` header, `docs/ARCHITECTURE.md`, commit B message) was corrected by the orchestrator after verify ran, re-verified green, and the change committed by amending commit B
- **Commits**: `8c18a7d` (A) and `78126e2` (B) — note B amended twice post-verify, so any earlier SHA in `verify-report.md` (`13db20a`) is stale per the Final-State Authority hierarchy
- **Test results at close**: 2013 tests / 0 failures / 0 errors / 7 skipped, `BUILD SUCCESS`
- **Baseline improvement**: +5 new guard tests (`OpenApiRouteCoverageTest`)
- **Task completion**: 32/32 implementation tasks checked (`[x]`)

### Verification Highlights (from verify-report, verified independently at that time)

- Guard direction 1 (documented-but-denied) proved live with independent mutation testing
- Guard direction 2 (live-but-undocumented) proved live with independent mutation testing
- Contract completeness independently measured: 63 distinct paths / 76 total operations
- `CODE-2` refactor purity confirmed: `RouteCoverageTest.java` diff shows only deletion of helper methods, addition of delegating wrappers, and one import — zero `@Test` bodies or assertions modified
- All five rationale clusters in `docs/API_REFERENCE.md` preserved verbatim (401-vs-403 semantics, per-owner data scoping, unscoped favourites guard, four identical login 401s, CSRF-nonce/cold-boot design)
- `docs/FRONTEND_AUTH_CONTRACT.md` byte-identical (no changes)
- Guard limit stated in all three required placements: `OpenApiRouteCoverageTest` javadoc, `docs/openapi.yaml` `info.description`, and `docs/API_REFERENCE.md` (Spanish)
- Both commits green in isolation with `clean` rebuild

### WARNING Resolution (Final-State Authority)

The verify-report identified one WARNING: the characterization of `docs/openapi.yaml` as "generated" appeared in three places:
1. `docs/openapi.yaml`'s own `info.description`
2. `docs/ARCHITECTURE.md`'s new index paragraph (in commit B)
3. Commit B's message body

Per the launch prompt, the orchestrator corrected all three after verify ran to state the contract is "hand-written and cross-checked" (not "generated"), re-ran the full suite green, and amended commit B. Verbatim phrase in `docs/API_REFERENCE.md`: `"Ese archivo se escribe a mano — no hay comando que lo regenere"` (confirmed present at B's time of writing). **This WARNING is fully resolved and carries forward no outstanding issues.**

---

## Deliverables

### Specs Merged

| Domain | Source | Destination | Action | Details |
|--------|--------|-------------|--------|---------|
| `api-contract-documentation` | `openspec/changes/openapi-swagger-docs/specs/api-contract-documentation/spec.md` | `openspec/specs/api-contract-documentation/spec.md` | **Created** (new capability) | Delta spec copied mechanically via shell; diff verified empty |

### Archive Contents

Change folder moved to `openspec/changes/archive/2026-09-03-openapi-swagger-docs/`:

- ✅ `proposal.md` — problem statement, constraint capture, scope
- ✅ `design.md` — architecture, ADRs, design decisions
- ✅ `specs/api-contract-documentation/spec.md` — formal requirements and scenarios (5 requirements, 10 scenarios)
- ✅ `tasks.md` — 32 implementation tasks (all checked); commit sequencing (A additive, B docs-only); toolchain specifications; `TEST-1` gate per commit
- ✅ `verify-report.md` — independent verification at time of run (SHAs `8c18a7d`, `13db20a`; final SHAs per launch prompt are `8c18a7d`, `78126e2` due to B's post-verify amend)
- ✅ `apply-progress.md` — intermediate snapshot of apply phase
- ✅ `explore.md` — exploration phase findings and rationale
- ✅ `state.yaml` — SDD state tracking

### Source of Truth Updated

The new capability `api-contract-documentation` is now part of the main specs tree:

```
openspec/specs/
  ├── README.md
  ├── api-contract-documentation/
  │   └── spec.md                    ← NEW (this change)
  ├── cli-rest-contract/
  ├── env-file-generation/
  ├── outfit-builder/
  ├── outfit-feedback/
  └── (6 others)
```

The specification defines five requirements guarding the `docs/openapi.yaml` contract:
1. **Contract Completeness** — every live route documented with auth requirement
2. **Guard Direction 1** — documented-but-denied routes fail the test
3. **Guard Direction 2** — live-but-undocumented routes fail the test (scans controllers, not policy wildcards)
4. **Non-Vacuous Guard** — negative control confirms scan is not empty
5. **Documentation Invariants** — rationale clusters and `FRONTEND_AUTH_CONTRACT.md` preserved

---

## Work Summary

### Commits Shipped

| Commit | Message | Size | Verified | Notes |
|--------|---------|------|----------|-------|
| `8c18a7d` | `feat(docs): add the OpenAPI contract and its bidirectional drift guard` | ~1030–1300 lines | ✅ Green (2013/0/0/7) | Commit A: additive only; test extraction (`LiveRoutes.java`), guard (`OpenApiRouteCoverageTest.java`), and YAML contract. No `src/main` change. `size:exception` pre-accepted for this slice. |
| `78126e2` | `docs(api): retire the mechanical contract from the markdown` | ~566 lines | ✅ Green (2013/0/0/7) | Commit B: docs-only. Mechanical path/method/status entries retired from `docs/API_REFERENCE.md`, `CLAUDE.md` (§API REST), `docs/ARCHITECTURE.md` (index added). Amended post-verify to correct "generated" → "hand-written" phrasing. |

### Delivery Strategy

Per `tasks.md`: `single-pr` with `size:exception` already accepted for commit A. Commit B fits comfortably within budget post-A. Both commits in order A → B.

### Quality Gates

- ✅ **Commit A** green alone (detached HEAD): `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7, BUILD SUCCESS`
- ✅ **Commit B** green alone (branch tip, verified before and after mutation testing): `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7, BUILD SUCCESS`
- ✅ **Full suite**, twice, on close: `Tests run: 2013, Failures: 0, Errors: 0, Skipped: 7, BUILD SUCCESS`
- ✅ **Baseline improvement**: 2008 + 5 new tests = 2013 (the five new `OpenApiRouteCoverageTest` methods)
- ✅ **`TEST-1` requirement met**: each commit individually green with `clean` rebuild

---

## Follow-Up: Change 2 (Not Started)

This was **change 1 of 2** for the Swagger documentation feature. Change 2 (interactive Swagger UI) is not started. The following decisions already settled for change 2 must carry forward:

### Settled Decisions for Change 2

- **Swagger UI route** will render this change's hand-written `docs/openapi.yaml` via `springdoc.swagger-ui.urls[].url` with `springdoc.api-docs.enabled: false`, so **no typed-DTO refactor of `ApiController` is a precondition** — this change's work is not throwaway
- **Route access level**: ADMIN only (not `PERMIT`, not bare `AUTHENTICATED`) — the document enumerates `DELETE /api/db/productos`, `/api/agent/**`, and `/api/usuarios/**` routes which are sensitive
- **Guard-level protection, not UI-level**: The policy row protecting the document applies to the YAML/JSON-serving route itself, not the HTML that renders it from the frontend
- **`RouteCoverageTest` will need a new row** if the UI route is added to `ApiRoutePolicy` — adding a new endpoint without updating that test's policy matrix breaks `RouteCoverageTest.thePermitListIsExactlyWhatWeExpect()` in `hasSize(N)`
- **springdoc controller scan limitation** (if springdoc is added): its `org.springdoc.*` controllers are invisible to `RouteCoverageTest`'s `ar.scraper`-scoped reflection scan — a forgotten policy row for the UI fails closed (403 Forbidden) but silently, so the guard can prove path/method parity but not ownership parity
- **Guard limit** (permanent): the guard proves path + method + access parity only, never response-shape accuracy (see `verify-report` "Known permanent limit")

---

## SDD Cycle Closure

**✅ COMPLETE** — All phases executed:

1. ✅ **sdd-propose**: Problem and solution framed; scope bounded
2. ✅ **sdd-spec**: Five formal requirements with ten scenarios
3. ✅ **sdd-design**: Architecture, ADRs, test design, negative controls
4. ✅ **sdd-tasks**: 32 implementation tasks, sequenced, toolchain specified
5. ✅ **sdd-apply**: Both commits executed, green, staged correctly
6. ✅ **sdd-verify**: Independent verification, PASS verdict (1 resolved WARNING)
7. ✅ **sdd-archive**: Specs merged, change archived, closure report written

**No outstanding blockers.** The change is ready for pull request, review, and delivery under ordinary repository policy.

---

## Artifact Observation IDs (Engram)

When persisting to Engram, the following artifacts were read during archive:

- `sdd/openapi-swagger-docs/proposal` — observation ID to be recorded
- `sdd/openapi-swagger-docs/spec` — observation ID to be recorded
- `sdd/openapi-swagger-docs/design` — observation ID to be recorded
- `sdd/openapi-swagger-docs/tasks` — observation ID to be recorded
- `sdd/openapi-swagger-docs/verify-report` — observation ID to be recorded
- `sdd/openapi-swagger-docs/apply-progress` — intermediate snapshot, read for context only

Archive report will be persisted as:
- `sdd/openapi-swagger-docs/archive-report` — topic_key for Engram persistence

---

## Key Facts at Close

| Fact | Value | Source |
|------|-------|--------|
| Tasks complete | 32/32 | Persisted `tasks.md` (all marked `[x]`); verify-report cross-checked |
| Test count | 2013 | Split JDK 24/21 toolchain; `clean` rebuild; run twice with identical result |
| Test failures | 0 | Full suite; verify phase re-ran independently |
| CRITICAL issues | 0 | verify-report verdict: PASS |
| Blockers | 0 | verify-report + final-state facts (WARNING resolved post-verify) |
| Commits delivered | 2 | A: `8c18a7d` (docs + guard), B: `78126e2` (retire mechanical prose) |
| Spec domain | `api-contract-documentation` | New capability; 5 requirements, 10 scenarios |
| Archive date | 2026-09-03 | ISO format per skill spec |

---

## References

- **Skill executed**: `~/.claude/skills/sdd-archive/SKILL.md` (v2.0)
- **Common protocol**: `~/.claude/skills/_shared/sdd-phase-common.md`
- **Project instructions**: `/games/Scrappy/CLAUDE.md` (user's private project guide)
- **OpenSpec convention**: followed hybrid mode (filesystem + Engram)
- **Architecture decisions**: `openspec/changes/archive/2026-09-03-openapi-swagger-docs/design.md` (ADR-1, ADR-2, ADR-3, Negative Control)
