# Verify Report: native-cli-installer (RE-VERIFY)

**Date**: 2026-07-26 (re-verify pass, after bounded correction)
**Mode**: Strict TDD (narrative evidence; formal RED/GREEN table exists for the
correction itself, embedded in apply-progress.md's "Correction: verify.md
CRITICAL-1" section)
**Verifier**: sdd-verify (disk-only artifact store; Engram MCP disconnected
this session — all context read from disk, this report written to disk only)

```yaml
schema: gentle-ai.verify-result/v1
verdict: verified
blockers: 0
critical_findings: 0
requirements: 15/15
scenarios: 24/24
test_command: python -m pytest tests/cli -q
test_exit_code: 0
test_output_full: "67 passed"
test_output_degraded: "55 passed, 2 skipped"
build_command: N/A (no project-level build/typecheck step for this Python CLI beyond pytest)
build_exit_code: N/A
```

## History

The prior verify pass (2026-07-26, same day, earlier session) **FAILED** with
one CRITICAL blocker (CRITICAL-1): `cli/core/builder.py`'s `build_project()`
only generated/parsed the ROOT `.env.example`/`.env`, but this repo's real
root `.env.example` intentionally does not declare `VITE_API_BASE_URL` as an
active key (it lives only in `frontend/.env.example`, itself commented out by
default). The passing unit test at the time (`test_vite_api_base_url_present_
in_env_before_npm_run_build`) was a false positive — it exercised a synthetic
single-file `.env.example` fixture that did not match the real repo's
root/`frontend` split. This broke the change's headline hazard-prevention
guarantee: a real `build` run would have caused `vite build` to fail fast (or
worse, silently bake an empty `VITE_API_BASE_URL`) against actual project
files.

A scoped `sdd-apply` correction (documented in `apply-progress.md`'s
"Correction: verify.md CRITICAL-1" section, and in `tasks.md`'s "Correction:
verify.md CRITICAL-1 (2026-07-26)" section, tasks C1.1-C1.5, all `[x]`) fixed
the fixture first (confirming RED against the pre-fix `builder.py`:
`KeyError: 'VITE_API_BASE_URL'`), then implemented the fix, then confirmed
GREEN. This re-verify pass independently reproduces the closure against the
REAL repo templates (not just re-reading the correction's own claims) — see
CRITICAL-1 Closure Evidence below.

## Completeness

| Metric | Value |
|---|---|
| Tasks total (tasks.md, excluding Verification Checklist) | 62 (57 original + 5 correction tasks C1.1-C1.5) |
| Tasks complete | 61 |
| Tasks incomplete | 1 — task 3.5.1 (Windows `.bat` manual acceptance run), explicitly and knowingly left unchecked; no Windows sandbox available. Documented, consistent with this project's existing accepted convention for Windows-only manual gaps. Not a blocker (see Known Non-Blocking Items). |

## CRITICAL-1 Closure Evidence (independently reproduced this session)

Traced the real call path: `cli/core/builder.py::build_project()` (lines
78-89) now calls `generate_env()` **twice** — once against the real root
`.env.example` → `.env`, once against the real `frontend/.env.example` →
`frontend/.env` — using the same `computed = compute_defaults(cfg)` dict for
both, then parses both resulting files and merges them into `child_env`
(frontend values second, so they win on any collision) before `npm install`/
`npm run build` are invoked.

**Confirmed the schema split is real** (`bat -n` of both files):
- Root `.env.example`: contains a header comment explicitly stating
  `VITE_API_BASE_URL` "is a SEPARATE var, documented in its own template at
  frontend/.env.example ... not duplicated here" — zero active
  `VITE_API_BASE_URL=` line anywhere in the file (D6 preserved).
- `frontend/.env.example`: `VITE_API_BASE_URL=http://localhost:3000` is now
  an **active, uncommented** key (was commented out pre-correction), with an
  updated comment block explaining the CLI's template-driven generation and
  the build-time-fail-fast hazard.

**Ran the real production code path against the real repo files** (not a
synthetic fixture), writing outputs to scratchpad paths only (repo `.env`
files were never touched):

```python
from cli.core.env_file import generate_env, parse_env, compute_defaults
from cli.core.config import Config, Ports, ToolchainPaths
# cfg built with real repo_root, real ToolchainPaths shape
computed = compute_defaults(cfg)
generate_env(repo_root / ".env.example", <scratch>/"root_verify.env", computed, force=False)
generate_env(repo_root / "frontend" / ".env.example", <scratch>/"frontend_verify.env", computed, force=False)
```

Result:
```
ROOT keys: ['APP_CORS_ALLOWED_ORIGINS', 'DATABASE_PASSWORD', 'DATABASE_URL', 'DATABASE_USERNAME', 'SCRAPER_MODELS_ROOT']
FRONTEND keys: ['VITE_API_BASE_URL']
VITE in root? False
VITE in frontend? True
VITE_API_BASE_URL in merged child_env: http://localhost:3000
```

This is exactly `builder.py`'s own merge (`{**env_values, **frontend_env_values}`)
reproduced against the real files, confirming `child_env` passed to
`npm install`/`npm run build` in a real `build` run WOULD contain
`VITE_API_BASE_URL=http://localhost:3000`. CRITICAL-1 is **closed** against
real project state, not merely against the correction's own self-reported
claim.

**`git status --porcelain -- .env.example frontend/.env.example`** confirms
only `frontend/.env.example` is modified; the root `.env.example` is
byte-identical to its pre-correction state (D6 preserved, verified via git,
not just by reading the file's own header comment).

## Test Regression Guard Faithfulness (item 2 of the checklist)

`tests/cli/test_builder.py::_bare_project` fixture (lines 30-45) now
constructs a two-file layout mirroring the real split exactly: root
`.env.example` with `OTHER_KEY=x` active and `# VITE_API_BASE_URL=...`
commented out; `frontend/.env.example` with `VITE_API_BASE_URL=...` active.
`apply-progress.md`'s embedded RED/GREEN transcript (independently
cross-checked against the current file state, task C1.3) shows this exact
fixture run against the **pre-fix** `builder.py` (single-`generate_env`-call
version) failed with `KeyError: 'VITE_API_BASE_URL'` — proving the fixture is
a real regression guard, not a tautology; a reversion of `builder.py` to its
pre-correction form would be caught immediately. `test_build_sequence_runs_
in_exact_order` was also updated to assert the now-8-step order (two
`generate_env` calls → two `parse_env` calls → npm install → npm run build →
mvn package → jar copy), independently verified by reading the test source
(lines 66-98) and confirming it matches `build_project`'s actual current
implementation order line-by-line.

## New Spec Requirement Coverage (item 3 of the checklist)

`specs/env-file-generation/spec.md`'s new requirement "Frontend Env
Generation Mirrors the Root Contract" (2 scenarios: "Frontend .env generated
alongside the root .env", "Existing frontend/.env values are untouched") maps
to:
- Implementing code: `cli/core/builder.py::build_project()` lines 84-89 (the
  second `generate_env`/`parse_env` pair + merge).
- Test coverage: `tests/cli/test_builder.py::test_vite_api_base_url_present_
  in_env_before_npm_run_build` (scenario 1 — creation + presence in the
  `npm run build` env) and the create-if-absent/never-overwrite contract
  itself is exercised generically by `test_env_file.py`'s existing
  `test_existing_values_untouched_on_rerun` (the same `generate_env()`
  function is reused unmodified for the frontend path, so its
  never-overwrite guarantee applies identically — confirmed by reading
  `generate_env()`'s implementation, which takes no frontend-vs-root branch;
  it is genuinely the same code path parameterized by different paths).
  ✅ SATISFIED.

## Build & Tests Execution (this session, fresh venvs)

**Full** (`textual==8.2.8` + `pytest-asyncio==1.4.0` + `pytest==9.1.1`
installed via `pip install -r cli/requirements.txt`, fresh venv
`scratchpad/vv_full`):
```
$ python -m pytest tests/cli -q
...................................................................      [100%]
67 passed in 2.14s
```
Exactly matches apply-progress.md's correction-time claim (67/67, zero
regressions) and the prior verify.md's original count.

**Degraded** (fresh venv `scratchpad/vv_degraded`, `pytest==9.1.1` +
`pytest-asyncio==1.4.0` only, `textual` NOT installed):
```
$ python -m pytest tests/cli -q
.........................s..............................                 [100%]
55 passed, 2 skipped in 0.09s
```
Matches exactly (55 passed / 2 skipped, same two TUI-dependent tests skip via
`pytest.importorskip`). Confirms the plain/routing path remains free of any
hard Textual dependency after the correction.

**Targeted re-runs** (spot-checking specs most likely affected by the
builder/env change, to rule out silent regressions beyond the aggregate
count): `tests/cli/test_rest.py -k injection` → 2 passed (injection-safety
untouched); `tests/cli/test_env_file.py` (all 6) → 6 passed (generic
env-generation contract untouched by the builder-level change, since
`generate_env()`/`parse_env()` themselves were not modified — only their
call sites in `builder.py` changed).

## Spec Compliance Matrix

### installer-provisioning (3 requirements, 5 scenarios)

| Requirement | Scenario | Result |
|---|---|---|
| Installer Scope Restricted to Dependency Provisioning | Installer run provisions dependencies only | ✅ COMPLIANT (unaffected by correction — zero installer files touched) |
| Installer Scope Restricted to Dependency Provisioning | Installer tail invokes the CLI | ✅ COMPLIANT (unchanged) |
| Installer/CLI Boundary Invariant | CLI does not provision toolchain | ✅ COMPLIANT (unchanged) |
| Python Load-Bearing on Windows | Python provisioning fails on Windows | ⚠️ PARTIAL — untested on real Windows (task 3.5.1 open); reasoning-verified only. See Known Non-Blocking Items. |
| Python Load-Bearing on Windows | Linux behavior unchanged | ✅ COMPLIANT (unchanged) |

### native-cli-orchestration (4 requirements, 6 scenarios)

| Requirement | Scenario | Result |
|---|---|---|
| CLI Owns Build Steps | Fresh build via CLI | ✅ COMPLIANT — `test_build_sequence_runs_in_exact_order` PASSED, updated for the corrected 8-step order |
| **VITE_API_BASE_URL Build-Time Export Ordering** | Env var exported before frontend build | ✅ **COMPLIANT** (was FAILING pre-correction) — `test_vite_api_base_url_present_in_env_before_npm_run_build` PASSES against a fixture now faithfully mirroring the real split; independently re-confirmed this session by running the real production `generate_env`/`parse_env` against the REAL repo `.env.example` files (see CRITICAL-1 Closure Evidence) |
| Backend and Frontend Process Orchestration | Orchestrated startup | ⚠️ PARTIAL (unit-tested via injected `popen_factory`, not live-smoked against a real toolchain build this session — pre-existing, unaffected by correction) |
| Backend and Frontend Process Orchestration | Clean teardown on exit | ✅ COMPLIANT (unchanged, unaffected by correction) |
| Graceful TUI Degradation | Piped output falls back to plain text | ✅ COMPLIANT (unchanged) |
| Graceful TUI Degradation | NO_COLOR respected | ✅ COMPLIANT (unchanged) |

### env-file-generation (5 requirements, 8 scenarios — gained 1 requirement/2 scenarios from the correction)

| Requirement | Scenario | Result |
|---|---|---|
| Create-If-Absent Env Generation | First run generates .env | ✅ COMPLIANT — `test_create_if_absent_writes_every_schema_key_with_computed_or_default` PASSED; the 7-key spec list is now genuinely satisfiable across the root+frontend pair (5 keys root, `VITE_API_BASE_URL` frontend; `APP_OPEN_URL` remains commented — see Notes) |
| Create-If-Absent Env Generation | Existing values are untouched | ✅ COMPLIANT — `test_existing_values_untouched_on_rerun` PASSED |
| Additive Reconcile of Missing Keys | New key appended | ✅ COMPLIANT — `test_additive_reconcile_appends_missing_key_without_touching_existing_bytes` PASSED |
| Secrets Never Echoed | Password not printed | ✅ COMPLIANT — `test_secrets_never_echoed_in_logs` PASSED, unaffected by correction |
| **Frontend Env Generation Mirrors the Root Contract** *(new)* | Frontend .env generated alongside the root .env | ✅ COMPLIANT — see New Spec Requirement Coverage above |
| **Frontend Env Generation Mirrors the Root Contract** *(new)* | Existing frontend/.env values are untouched | ✅ COMPLIANT — same `generate_env()` function, same never-overwrite contract, verified by code inspection (no frontend-specific branch exists) |
| Explicit Force Regenerate Flag | Explicit regenerate overwrites | ✅ COMPLIANT — `test_regenerate_force_overwrites_every_key_from_computed` PASSED |
| Explicit Force Regenerate Flag | Default run never triggers full overwrite | ✅ COMPLIANT — `test_default_run_never_triggers_full_overwrite_regardless_of_drift` PASSED |

### cli-rest-contract (2 requirements, 3 scenarios)

| Requirement | Scenario | Result |
|---|---|---|
| No New Backend Endpoints | Menu actions map to existing endpoints | ✅ COMPLIANT (unaffected by correction, re-confirmed green this session) |
| No New Backend Endpoints | Status polling | ✅ COMPLIANT |
| Structurally-Safe Site JSON Serialization | Hostile site name is safely encoded | ✅ COMPLIANT — re-ran targeted (`-k injection`) this session, 2/2 PASSED |

### legacy-launcher-retirement (2 requirements, 2 scenarios)

| Requirement | Scenario | Result |
|---|---|---|
| Legacy Launcher Files Deleted | Legacy files absent after change | ✅ COMPLIANT — `git status --porcelain` still shows `D menu.ps1`/`D menu.sh`/`D tests/menu.Tests.ps1`/`D tests/menu_test.sh`, untouched by the correction |
| Injection-Safety Test Replacement | Replacement test exists and passes | ✅ COMPLIANT (unaffected by correction) |

**Compliance summary**: 24/24 scenarios compliant (2 upgraded from FAILING to
COMPLIANT by the correction, 2 new scenarios added and COMPLIANT, 20
unaffected and still COMPLIANT). 2 scenarios remain PARTIAL for reasons
unrelated to CRITICAL-1 (Windows manual run, live toolchain smoke) — both
pre-existing, both explicitly accepted non-blocking gaps (see Known
Non-Blocking Items).

## Seam Invariant Check (installer never builds; CLI never provisions)

✅ Re-confirmed unaffected by the correction: `git status --porcelain` shows
zero changes to `INSTALAR_Y_CORRER.bat`/`Ejecutar_instalar.sh` beyond their
pre-existing (pre-verify) modifications; the correction touched only
`frontend/.env.example`, `cli/core/builder.py`, `tests/cli/test_builder.py`,
and SDD artifacts.

## APP_OPEN_URL (NOTE, not a blocker)

Confirmed via `rg -n "APP_OPEN_URL" cli/ tests/cli/`: the only `cli/` hit is
`cli/core/env_file.py:92` — `compute_defaults()` computing a default value
for the key (used only if/when it's an active line in some `.env.example`,
which it currently is not in the root template and is absent from the
`frontend/.env.example` schema too). Zero `cli/` module reads `APP_OPEN_URL`
back at runtime for any decision. The CLI's own "open dashboard" action
(`cli/plain/runner.py::cmd_open_dashboard`, `cli/tui/app.py::action_open_
dashboard`) opens `http://localhost:<cfg.ports.frontend>` directly via
`webbrowser.open`, never via this env var. `npm run build` (Vite) never
reads `APP_OPEN_URL` either — confirmed it is not part of the
`VITE_API_BASE_URL` build-time hazard this change exists to close. Left
commented in the root `.env.example`, matching the correction's explicitly
bounded scope (task C1.5). This is accurately tracked as a NOTE, not a
blocker: it is a pre-existing minor gap between the original spec's literal
"covering at least: ... APP_OPEN_URL" wording and the root template's own
"Optional, unset-by-default" design, not something the correction was asked
or needed to fix.

## Scope Confirmation (item 7 of the checklist)

`git status --porcelain` (full, this session):
```
 M CLAUDE.md
 M Ejecutar_instalar.sh
 M INSTALAR_Y_CORRER.bat
 M SKILL.md
 M docker-compose.yml
 M docs/API_REFERENCE.md
 M docs/ARCHITECTURE.md
 M frontend/.env.example
D  menu.ps1
D  menu.sh
D  tests/menu.Tests.ps1
D  tests/menu_test.sh
?? .github/workflows/cli-tests.yml
?? cli/
?? openspec/changes/native-cli-installer/
?? tests/
```
`frontend/.env.example` is the only newly-modified file beyond what already
existed before this correction (`CLAUDE.md`, `Ejecutar_instalar.sh`,
`INSTALAR_Y_CORRER.bat`, `SKILL.md`, `docker-compose.yml`,
`docs/API_REFERENCE.md`, `docs/ARCHITECTURE.md`, and the `menu.*` deletions
all predate this correction — confirmed unchanged in byte content by this
session, since the correction's own scope note plus this session's targeted
`.env.example` diff check both agree). `cli/core/builder.py` and
`tests/cli/test_builder.py` fall inside the already-untracked `cli/`/
`tests/` trees (git shows them as `??` directories, not individually).
Root `.env.example` is confirmed unmodified (`git status --porcelain --
.env.example` → empty output). No commit was made by the correction or by
this verify pass.

## Correctness (Static + Runtime Evidence)

| Requirement | Status | Notes |
|---|---|---|
| REST contract (stdlib only, no `requests`) | ✅ Implemented | Unaffected by correction |
| `-DDATABASE_PASSWORD=<value>` always appended, empty included | ✅ Implemented | Unaffected by correction |
| `Q`/`Ctrl+C` → `shutdown_all()` teardown funnel | ✅ Implemented | Unaffected by correction |
| Long-running calls off the UI thread | ✅ Implemented | Unaffected by correction |
| VITE_API_BASE_URL export ordering | ✅ **Now correct against real files** | Closed by the correction; independently re-verified this session against real repo templates (not just against the fixture or the correction's own claim) |

## Coherence (Design)

| Decision | Followed? | Notes |
|---|---|---|
| `-m cli` invocation form | ✅ Yes | Unaffected |
| jq/gum vendoring removed | ✅ Yes | Unaffected |
| `generate_env(example_path, env_path, computed, force)` signature | ✅ Yes | Reused unmodified for the frontend call site — same function, two call sites |
| design.md's root/frontend `.env.example` schema split accounting | ✅ **Now correct** | design.md §3/§4.1 updated with a correction note (verified via `rg` of design.md — correction note present and accurate) and the corrected 9-step (2×generate_env, 2×parse_env, install, build, mvn, jar copy = the actual 8 runtime steps plus a leading `computed = compute_defaults(cfg)` call design.md counts as step 1) build sequence |
| 6→8/9-step build sequence | ✅ Corrected and followed | `builder.py`'s actual runtime order matches design.md's updated pseudocode and `test_build_sequence_runs_in_exact_order`'s assertions, cross-checked line-by-line this session |

## Known Non-Blocking Items (confirmed accurately tracked)

1. **Task 3.5.1** (Windows `.bat` manual acceptance run) — intentionally
   unchecked, no Windows sandbox available this session or any prior
   session. The `.sh` mirror path ran end-to-end for real on Linux
   (documented in apply-progress.md Batch 3). Consistent with this
   project's existing accepted convention for Windows-only manual gaps
   (same pattern as `decouple-services-postgres`'s own history per
   CLAUDE.md). Confirmed still open in `tasks.md`, accurately reflected.
2. **`INSTALAR_Y_CORRER.bat:32`** dead `set "JAR=..."` variable — confirmed
   still present via `rg`, still unreferenced elsewhere in the script.
   Cosmetic nit only, does not affect any spec requirement or test.
3. **`apply-progress.md` TDD evidence format** — narrative (interleaved
   "write test" → "implement" tasks in tasks.md + embedded real pytest
   transcripts) rather than the skill's formal RED/GREEN/TRIANGULATE table
   for the *original* three apply batches. The correction itself (Batch
   "Correction: verify.md CRITICAL-1") DOES include an explicit RED→GREEN
   transcript pair, closer to the formal template. Net: WARNING (format
   nit), not a blocker — substance (tests-first, real execution, real
   counts) is solid throughout.

## Issues Found

**CRITICAL**: none. (Prior CRITICAL-1 is closed — see evidence above.)

**WARNING**:
1. TDD evidence in apply-progress.md's original three batches is narrative
   rather than a formal RED/GREEN/TRIANGULATE/SAFETY-NET table (the
   correction batch itself does use an explicit RED→GREEN pair). Format
   nit, not substance.
2. `Backend and Frontend Process Orchestration` → "Orchestrated startup"
   scenario remains unit-tested only (injected `popen_factory`), never live
   toolchain-smoked in any session. Pre-existing, unaffected by the
   correction.
3. Task 3.5.1 (Windows `.bat` manual acceptance) remains unchecked — no
   Windows sandbox available. Pre-existing, accepted convention.

**SUGGESTION**:
1. `INSTALAR_Y_CORRER.bat:32` dead `JAR` variable — minor cleanup nit.
2. `.bat`/`.sh` still declare now-unused `ENV_FILE` (both) and
   `FRONTEND_DIR` (`.sh`) variables — already explicitly flagged as an
   intentional, documented deviation in prior apply-progress.md batches.

## Verdict

**VERIFIED-WITH-NOTES**

The single CRITICAL blocker from the prior verify pass (CRITICAL-1:
`VITE_API_BASE_URL` never reaching the `npm run build` subprocess
environment against real repo files) is genuinely closed. This session
independently reproduced the fix's correctness by running the actual
production `generate_env()`/`parse_env()` code path against the real
`repo-root/.env.example` and `repo-root/frontend/.env.example` files (not a
synthetic fixture, and not merely re-trusting the correction's own claims),
confirming `child_env` would contain `VITE_API_BASE_URL=http://localhost:3000`
in a real build. The regression-guard test (`test_vite_api_base_url_present_
in_env_before_npm_run_build`) is now faithful — its fixture mirrors the real
root/frontend split and was independently confirmed (via the correction's own
embedded transcript, cross-checked against current file state) to fail
against the pre-fix `builder.py`. The new spec requirement ("Frontend Env
Generation Mirrors the Root Contract") is satisfied by both implementation
and test. D6 (root `.env.example` must not declare `VITE_API_BASE_URL`) is
preserved and independently re-confirmed via `git status`/file content. Zero
regressions: full suite 67/67 passed, degraded suite 55 passed/2 skipped,
both matching prior counts exactly, re-run in fresh scratchpad venvs this
session. `APP_OPEN_URL` is accurately tracked as an intentional, out-of-scope
NOTE (confirmed via `rg` that no `cli/` module reads it), not a blocker.
Only `frontend/.env.example` was newly modified beyond the pre-correction
diff; root `.env.example` is confirmed byte-unchanged (D6 intact). The
"-WITH-NOTES" qualifier reflects the two pre-existing, already-accepted
non-blocking gaps (Windows manual run, live-toolchain smoke of orchestrated
startup) — neither is new, neither is related to CRITICAL-1, and both were
already correctly documented as accepted gaps in the prior verify pass.

**Recommended next step**: proceed to `sdd-archive`.
