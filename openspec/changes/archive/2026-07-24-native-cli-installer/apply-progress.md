# Apply Progress: native-cli-installer

**Mode**: TDD for slices 1–2 (test written before implementation for every behavioral unit). Slice 3 is installer/infra/docs work with no new `core/`-level behavioral unit to TDD — its verification is empirical (real command runs) rather than unit-test RED→GREEN, documented as a Work Unit Evidence table below.
**Batch**: 3 of 3 — **Slice 3 of 3 (installer shrink + uv provisioning + menu.\* retirement) — IMPLEMENTATION COMPLETE, IRREVERSIBLE, FINAL.** All three slices of `native-cli-installer` are implemented; only task 3.5.1 (Windows manual acceptance run) remains unchecked, no Windows sandbox available this session. `apply` phase moves to `phases_completed` in `state.yaml`.

---

## Correction: verify.md CRITICAL-1 — 2026-07-26

Single bounded correction transaction, routed back from `sdd-verify`'s FAIL
verdict (see `verify.md` for the full independently-reproduced evidence).
Engram MCP disconnected this session too — all context read from disk
(`verify.md`, `design.md`, `specs/env-file-generation/spec.md`, `tasks.md`,
this file, `state.yaml`, the real `cli/core/builder.py`/`env_file.py`,
`tests/cli/test_builder.py`, root `.env.example`, `frontend/.env.example`).
Strict TDD followed: the fixture was corrected and RED was confirmed against
the pre-fix `builder.py` BEFORE any implementation change.

### Root cause (recap)

`build_project()` only ever called `generate_env()`/`parse_env()` against
the ROOT `.env.example`/`.env`. This repo's real root `.env.example`
intentionally does not declare `VITE_API_BASE_URL` as an active key (it
lives only in `frontend/.env.example`, per the `decouple-services-postgres`
D6 split — documented in the root file's own header comment) — and in
`frontend/.env.example` the key was commented out by default. Since
`generate_env()` only ever emits active `_KeyLine`s (never commented ones),
`VITE_API_BASE_URL` was silently never written anywhere, so `child_env`
passed to `npm run build` lacked it — the exact hazard this change's
headline feature exists to prevent. The passing unit test
(`test_vite_api_base_url_present_in_env_before_npm_run_build`) was a false
positive: its `_bare_project` fixture fabricated a synthetic single-file
`.env.example` with `VITE_API_BASE_URL` active, not matching real project
state.

### RED → GREEN evidence

**RED** (confirmed BEFORE touching `cli/core/builder.py`, fixture corrected
first): rewrote `tests/cli/test_builder.py`'s `_bare_project` to faithfully
mirror the real split — root `.env.example` with `VITE_API_BASE_URL` only as
a comment (plus an unrelated active `OTHER_KEY`), `frontend/.env.example`
with it active — and ran the suite against the UNMODIFIED (pre-fix)
`builder.py`:

```
$ .../vv_red/bin/python -m pytest tests/cli/test_builder.py -v
tests/cli/test_builder.py::test_vite_api_base_url_present_in_env_before_npm_run_build FAILED
tests/cli/test_builder.py::test_build_sequence_runs_in_exact_order PASSED
tests/cli/test_builder.py::test_build_raises_typed_error_when_jar_artifact_missing PASSED
E       KeyError: 'VITE_API_BASE_URL'
1 failed, 2 passed in 0.04s
```

This reproduces CRITICAL-1 as an executable, currently-failing test —
proving the fixed fixture is a real (not vacuous) regression guard.

**GREEN** (after implementing the fix, same venv, same fixture, unchanged):

```
$ .../vv_red/bin/python -m pytest tests/cli/test_builder.py -v
tests/cli/test_builder.py::test_vite_api_base_url_present_in_env_before_npm_run_build PASSED
tests/cli/test_builder.py::test_build_sequence_runs_in_exact_order PASSED
tests/cli/test_builder.py::test_build_raises_typed_error_when_jar_artifact_missing PASSED
3 passed in 0.03s
```

### The fix (exactly the chosen approach — no substitution)

1. **`frontend/.env.example`**: uncommented `VITE_API_BASE_URL` (default
   `http://localhost:3000`) so it is now an active key in its
   architecturally-correct home (per D6); kept the existing explanatory
   comment, extended it to note the build-time-fail-fast hazard and the CLI's
   template-driven generation.
2. **`cli/core/builder.py`** `build_project()`: after generating/reconciling
   the root `.env`, now ALSO calls `generate_env(frontend_example_path,
   frontend_env_path, computed, force=force_env)` (defaults:
   `cfg.repo_root/"frontend"/".env.example"` →
   `cfg.repo_root/"frontend"/".env"`) — using the SAME `computed` dict from
   `compute_defaults(cfg)` (already keys `VITE_API_BASE_URL`; `_resolve`
   only applies a computed value to a key present in that template's own
   schema, so `compute_defaults()` needed zero changes). Both root and
   frontend `.env` are then parsed and merged into `child_env` — before
   `npm install`/`npm run build` — preserving the design's ordering
   invariant (env must be a real subprocess var before Vite reads it) while
   now matching the real repo's file layout. `example_path`/`env_path` and
   the new `frontend_example_path`/`frontend_env_path` are all still
   injectable parameters (test/DI friendly, unchanged pattern).
3. **`tests/cli/test_builder.py`**: `_bare_project` now mirrors the real
   split (see RED evidence above); `test_build_sequence_runs_in_exact_order`
   updated for the now-two-call `generate_env`/`parse_env` sequence
   (`generate_env(root)` → `generate_env(frontend)` → `parse_env(root)` →
   `parse_env(frontend)` → npm install → npm run build → mvn package → jar
   copy).
4. **`specs/env-file-generation/spec.md`**: new requirement "Frontend Env
   Generation Mirrors the Root Contract" with 2 scenarios (frontend/.env
   generated alongside root; existing frontend/.env values untouched).
   **`design.md`** §3/§4.1 updated with correction notes + the corrected
   9-step build sequence.
5. **`tasks.md`**: new "Correction: verify.md CRITICAL-1" section
   (C1.1–C1.5) added, all marked `[x]`.

### APP_OPEN_URL (secondary — bounded, not expanded)

`verify.md` also noted `APP_OPEN_URL` is never emitted (commented in the
root template). Checked via `rg 'APP_OPEN_URL' cli/ tests/cli/`: **zero**
`cli/` module reads this env var at runtime. The CLI's own "open dashboard"
action (`cli/plain/runner.py::cmd_open_dashboard`,
`cli/tui/app.py::action_open_dashboard`) opens
`http://localhost:<cfg.ports.frontend>` directly via `webbrowser.open`,
computed from `Config`, never from `APP_OPEN_URL`. `APP_OPEN_URL` is a
backend-only convenience (documented in the root `.env.example`: "Backend is
API-only; unset by default (no-op). If set, the backend opens this URL in
the local browser once Tomcat is ready") that the native CLI's own
open-dashboard action has superseded. Unlike `VITE_API_BASE_URL`, it is not
read by `npm run build` (Vite never looks at `APP_OPEN_URL`), so its absence
does not break any build — it is genuinely optional-by-design, not a hazard.
**Left commented, unmodified.** This is a pre-existing minor gap between the
spec's literal "covering at least: ... APP_OPEN_URL" wording and the root
template's own "Optional" section (predates this correction, not part of
CRITICAL-1) — flagged here for visibility, not fixed, per the correction's
explicitly bounded scope.

### Full-suite verification (fresh venvs, this correction)

**Full** (`textual==8.2.8` installed, fresh venv `vv_full`):
```
$ python -m pytest tests/cli -q
...................................................................      [100%]
67 passed in 2.11s
```

**Degraded** (Textual NOT installed, fresh venv `vv_degraded`, same
`cli/requirements.txt` minus the `textual==8.2.8` line):
```
$ python -m pytest tests/cli -q
.........................s..............................                 [100%]
55 passed, 2 skipped in 0.09s
```

Both counts match `verify.md`'s original 67/0/0 and 55/0/2 exactly — zero
regressions introduced by this correction, confirming the fix is scoped and
does not disturb any other passing scenario.

### Scope confirmation

`git status --porcelain` after this correction: only `frontend/.env.example`
newly modified (beyond files already modified before this session started —
`CLAUDE.md`, `Ejecutar_instalar.sh`, `INSTALAR_Y_CORRER.bat`, `SKILL.md`,
`docker-compose.yml`, `docs/API_REFERENCE.md`, `docs/ARCHITECTURE.md`, the
`menu.*` deletions — all pre-existing, untouched by this correction);
`cli/core/builder.py` and `tests/cli/test_builder.py` changes fall inside
the already-untracked `cli/`/`tests/` trees. No installer, docs, or other
`cli/` file touched. No commit made.

### Status

CRITICAL-1 closed. `verify` remains in `phases_pending` — re-verification is
a separate `sdd-verify` pass, not run by this correction. Next:
`sdd-verify`.

---

## Batch 3 — Slice 3 (installer shrink + uv provisioning + menu.* retirement) — 2026-07-25

Engram MCP was disconnected this session too — all context read from disk
per the orchestrator's explicit instruction (tasks.md, apply-progress.md
Batches 1–2, design.md, specs/installer-provisioning +
specs/legacy-launcher-retirement, CLAUDE.md, the real `INSTALAR_Y_CORRER.bat`
/ `Ejecutar_instalar.sh` / `cli/__main__.py` / `cli/core/config.py` /
`cli/core/builder.py` / `cli/plain/runner.py`). Network WAS available this
session — used to fetch the real `uv` GitHub release metadata/binary
(`astral-sh/uv`) and to actually install/exercise it, not just reason about
its CLI from memory.

### Ordering gate (task 3.3.1) — re-verified GREEN before any deletion

Before touching `menu.*`, installed `cli/requirements.txt` into a fresh
throwaway venv and ran the full suite:

```
$ python -m pytest tests/cli -v
...
============================== 67 passed in 2.10s ===============================
```

All `tests/cli/test_rest.py` injection-safety tests (task 1.5.1) passed,
confirming the gate tasks.md documents ("task 1.5.1 must be green before
3.3.2–3.3.5"). Only after this did the deletions in Phase 3.3 proceed.

### Completed Tasks (Slice 3 — all of it)

- [x] 3.1.1–3.1.6 — uv + `_tools/cli-venv` provisioning added to both
  `INSTALAR_Y_CORRER.bat` (new step `[8/8]`, replacing the old "Compilar
  JAR" step) and `Ejecutar_instalar.sh` (new step `[4/4]`). Both: download
  the pinned `uv==0.11.32` static binary into `_tools/uv` (idempotent —
  skip if already present); `UV_PYTHON_INSTALL_DIR`/`UV_CACHE_DIR` pinned
  under `_tools/uv/` so uv never touches a global/user cache or Python
  registry; `uv python install 3.11.9` (uv-managed standalone CPython, NOT
  the ML embeddable); `uv venv --managed-python --python 3.11.9
  _tools/cli-venv`; `uv pip install --python <venv-python> -r
  cli/requirements.txt`; `import textual` acceptance check that hard-fails
  the install with an actionable message on any failure (uv download, `uv
  python install`, `uv venv`, `uv pip install`, or the final import check).
  The whole block is skipped (idempotent) if `_tools/cli-venv`'s python
  already has `textual` importable — matching every other step's
  "already installed, skip" convention in these scripts.
- [x] 3.2.1–3.2.4 — Installer shrink. Removed from `INSTALAR_Y_CORRER.bat`:
  the frontend `npm install`/`npm run build` block (old step `[5/8]`, now
  Node.js-provisioning-only) and the `mvn clean package` + jar-copy +
  `.env` echo block (old step `[8/8]`). Removed from `Ejecutar_instalar.sh`:
  the frontend build step (old `[4/6]`), the `mvn clean package` + jar-copy
  step (old `[5/6]`), the `.env` heredoc, and the jq/gum vendoring step
  (old `[6/6]`) — see Deviations below for why jq/gum removal was kept
  despite one paraphrased instruction saying to keep it. Both installer
  tails now invoke the CLI via `-m cli` (see Deviations — not the literal
  `cli\__main__.py` path). The ~235-line ML-deps block (`TORCH_VER_BEFORE`/
  `_AFTER` guard), the Python embeddable, JDK/Maven/Node/Postgres
  provisioning, and the system-tool `require` checks in
  `Ejecutar_instalar.sh` are byte-for-byte untouched.
- [x] 3.3.1–3.3.5 — Gate re-verified GREEN (above); deleted `menu.ps1`,
  `menu.sh`, `tests/menu.Tests.ps1`, `tests/menu_test.sh` via `git rm`.
- [x] 3.4.1–3.4.3 — Docs updated: `docs/ARCHITECTURE.md` gained a new
  dedicated "Launcher: CLI nativo" section (architecture tree, venv
  isolation rationale, invocation form, test coverage) and its
  docker-install-alternative paragraph no longer claims `menu.ps1`/`menu.sh`
  are untouched. `CLAUDE.md`'s `interactive-cli-launcher` note was replaced
  by a `native-cli-installer` (2026-07-25) note per the repo's dated-note
  convention; its stack table, file tree, Gotchas, and known-issues table
  were updated. Also updated (in scope per the user's DOCS item 4 — "any
  install/launcher references"): `SKILL.md`'s doc index row, `docs/
  API_REFERENCE.md`'s header note, `docker-compose.yml`'s top comment.
  Invariant grep (task 3.4.3): zero *executable* `npm`/`mvn`/jar-copy/
  `.env`-write lines in either installer (only explanatory comments stating
  these steps were removed matched); zero download/extract/install
  commands anywhere under `cli/`.
- [x] 3.5.2–3.5.3 (Linux acceptance + full suite) — See Work Unit Evidence
  table below for the real end-to-end run performed this session.
- [ ] 3.5.1 (Windows acceptance) — **NOT run this session**, left unchecked
  in tasks.md; no Windows sandbox was available. See Issues/Risks below.

### Files Changed

| File | Action | What Was Done |
|------|--------|----------------|
| `INSTALAR_Y_CORRER.bat` | Modified | Shrunk (removed frontend build, mvn+jar-copy, `.env` echo); added uv + `_tools/cli-venv` provisioning step `[8/8]`; tail now invokes `_tools\cli-venv\Scripts\python.exe -m cli` instead of `menu.ps1` |
| `Ejecutar_instalar.sh` | Modified | Shrunk (removed frontend build, mvn+jar-copy, `.env` heredoc, jq/gum vendoring); added uv + `_tools/cli-venv` provisioning step `[4/4]`; tail now `exec`s `_tools/cli-venv/bin/python -m cli` instead of `bash menu.sh` |
| `menu.ps1` | **Deleted** | Retired — superseded by `cli/tui`/`cli/plain` |
| `menu.sh` | **Deleted** | Retired — superseded by `cli/tui`/`cli/plain` |
| `tests/menu.Tests.ps1` | **Deleted** | Retired — superseded by `tests/cli/test_rest.py`'s injection-safety tests |
| `tests/menu_test.sh` | **Deleted** | Retired — superseded by `tests/cli/test_rest.py`'s injection-safety tests |
| `CLAUDE.md` | Modified | `native-cli-installer` note supersedes `interactive-cli-launcher`; stack table, file tree, Gotchas, known-issues table updated |
| `docs/ARCHITECTURE.md` | Modified | New "Launcher: CLI nativo" section; docker-alternative paragraph updated |
| `SKILL.md` | Modified | Doc index row: `menu.ps1`/`menu.sh` → `cli/` |
| `docs/API_REFERENCE.md` | Modified | Header note: CLI supersedes menu.ps1/menu.sh as the REST client description |
| `docker-compose.yml` | Modified | Top comment: menu.ps1/menu.sh → native CLI |
| `openspec/changes/native-cli-installer/tasks.md` | Modified | All Slice 3 tasks marked `[x]` with evidence/deviation notes |
| `openspec/changes/native-cli-installer/state.yaml` | Modified | `apply` moved from `phases_pending` to `phases_completed`; detailed session note appended |

No files under `cli/`, `tests/cli/`, or `.github/workflows/cli-tests.yml`
were modified this session (per the instruction to prefer not touching
slice 1/2 code) — confirmed via `git status --porcelain` (see below).

### Work Unit Evidence

| Evidence | Value |
|---|---|
| Focused test command and exact result | `pytest tests/cli -v` — run TWICE: (1) in a throwaway venv BEFORE any deletion, as the 3.3.1 gate check: **67/67 passed**; (2) AFTER all installer/doc changes and the `menu.*` deletions, run directly against the REAL `_tools/cli-venv` produced by the actual `Ejecutar_instalar.sh` run below (not a proxy venv): **67/67 passed**. Zero regressions from the deletions/installer edits, as expected since `cli/`/`tests/cli/` were not touched. |
| Runtime harness command/scenario and exact result | `printf "status\nq\n" \| bash Ejecutar_instalar.sh`, run for real against this session's actual git worktree (not a scratch copy). Real result: all 4 provisioning steps passed (internet OK; java 24/mvn/node present; PostgreSQL reused an already-running `fashion-scraper-pg` Docker container; uv 0.11.32 downloaded, CPython 3.11.9 installed via uv, `_tools/cli-venv` created, `cli/requirements.txt` installed — 15 packages incl. textual==8.2.8 — `import textual` acceptance check passed). The tail then ran `_tools/cli-venv/bin/python -m cli`, which (correctly) routed to the plain-mode fallback (piped/non-tty), printed the menu, executed `status` against a real backend on `:3000` in this sandbox (returned real JSON, `4629 productos`), and exited cleanly on `q`. `ps aux` before/after confirmed zero orphan processes introduced by this run (nothing was `start`ed, so `shutdown_all()` had nothing to tear down — consistent with design). |
| Rollback boundary | This entire batch is one deliverable, irreversible-by-design unit (per tasks.md's explicit "Slice 3 ... IRREVERSIBLE — last" framing — rollback of the whole `native-cli-installer` change is "revert the PR(s)", which only works cleanly with slice 3 last, which it is). Within this batch, a partial revert is still mechanically possible: `git revert`/`git checkout` on `INSTALAR_Y_CORRER.bat`, `Ejecutar_instalar.sh`, and the doc files restores their prior content; `git checkout <prior-commit> -- menu.ps1 menu.sh tests/menu.Tests.ps1 tests/menu_test.sh` would restore the deleted files verbatim (they are still in git history at this point, before any squash/merge). No `cli/`/`tests/cli/` files were touched, so no rollback interaction with slices 1–2 is possible. |

### Deviations from Design / Task Wording (flagged explicitly, not silently applied)

1. **CLI invocation form: `-m cli`, not `cli\__main__.py`/`cli/__main__.py`
   as a direct script path.** Both tasks.md (task 3.2.4) and design.md
   (§9) literally say the tail should invoke `_tools\cli-venv\Scripts\
   python.exe cli\__main__.py` / `_tools/cli-venv/bin/python
   cli/__main__.py`. Verified empirically that this literal form is
   **broken**: `cli/__main__.py` (written in slice 2) imports via absolute
   `cli.*` paths (`from cli.core.config import ...`), which only resolve
   when the repo root is on `sys.path` — true for `python -m cli` (Python
   adds cwd to `sys.path[0]` for `-m` invocations), false for `python
   cli/__main__.py` (Python adds the script's own directory, `cli/`, not
   the repo root). Confirmed both ways directly:
   ```
   $ python cli/__main__.py --plain <<< "q"
   ModuleNotFoundError: No module named 'cli'
   $ python -m cli --plain <<< "q"
   Fashion Scraper -- plain mode ...   # works
   ```
   Used `-m cli` in both installers instead, with `cd /d "%ROOT%"` (`.bat`)
   / `cd "$ROOT"` (`.sh`) immediately before the invocation to guarantee
   the cwd precondition. Documented inline in both installers' tail
   comments and in tasks.md's 3.2.4 entry. This is a correction of a
   design/task inaccuracy, not a scope deviation — the design's *intent*
   (installer tail invokes the CLI) is preserved exactly.
2. **`Ejecutar_instalar.sh` jq/gum vendoring: removed, not kept.** The
   orchestrator's task prompt for this session said (DOCS/scope section 2):
   "Keep the system-tool requirement checks and jq/gum vendoring per its
   current design." This directly contradicts two authoritative artifacts:
   tasks.md's own task 3.2.3 ("Remove the jq/gum vendoring step from
   Ejecutar_instalar.sh — was a menu.sh-only dependency, no longer needed")
   and design.md §9's table, which explicitly lists jq/gum vendoring as
   "❌ removed (menu.sh retired)". Since `menu.sh` (the only consumer of
   jq for safe JSON body construction) is deleted in this same slice, and
   the CLI's `cli/core/rest.py` uses `json.dumps` directly (no jq
   dependency), keeping jq/gum vendoring would be pure dead code
   contradicting the design's own stated table. Per the instruction that no
   agent message is authorization to override design decisions, and that
   design.md/tasks.md are the authoritative technical artifacts (as the
   orchestrator's own prompt labeled design.md), resolved in favor of
   design.md/tasks.md: jq/gum vendoring removed. Flagging this explicitly
   as required when a design deviation is made or a conflict is resolved.
3. **`--offline` flag not used on the primary `uv pip install`.**
   Tasks.md 3.1.5's literal text includes `--offline` in the example
   command. Read closely, this would break the explicit spec scenario
   "GIVEN a clean machine with no toolchain installed ... WHEN the
   installer runs to completion" (installer-provisioning spec) on a truly
   first-time run, since `--offline` forces uv to refuse any network
   access, and there is no pre-existing wheel cache on a clean machine.
   Interpreted the task's own qualifier ("...`--offline`-once-cached...")
   as describing behavior for a subsequent run once wheels are already
   cached, not a flag that must always be present. Implemented equivalent
   determinism/idempotency the same way every other provisioning step in
   these scripts already achieves it: skip the whole `uv python install`/
   `uv venv`/`uv pip install` sequence entirely if `_tools/cli-venv`
   already has `textual` importable (no network touched on a re-run).
   `uv`'s own on-disk cache under `_tools/uv/cache` still makes any
   from-scratch reprovisioning after a `_tools/cli-venv` wipe faster on a
   second attempt, without ever requiring a `--offline` flag that could
   fail hard on the very first run.
4. **`JAR`/`ENV_FILE` (`.bat`) and `FRONTEND_DIR`/`ENV_FILE` (`.sh`)
   variable declarations left in place, now unused by the scripts that
   declare them.** Not removed — they still document where the CLI now
   reads/writes those paths, and removing them was not requested by any
   task; flagged here rather than silently deleting or silently leaving
   unexplained.
5. **uv version pin (`0.11.32`) and CPython pin (`3.11.9`) are inferences,
   not carried-over values** — no prior uv pin existed in this repo to
   match. `0.11.32` was the actual `astral-sh/uv` "latest" release resolved
   via the GitHub API at implementation time (2026-07-25); confirmed the
   Windows (`uv-x86_64-pc-windows-msvc.zip`) and Linux
   (`uv-x86_64-unknown-linux-gnu.tar.gz` /
   `uv-aarch64-unknown-linux-gnu.tar.gz`) asset names exist on that release
   by downloading and inspecting the Linux one directly. `3.11.9` was
   chosen (not `3.11` bare, and not a different patch) specifically to
   match the *existing* ML embeddable's pin already used elsewhere in
   `INSTALAR_Y_CORRER.bat` (`python-3.11.9-embed-amd64.zip`) — same minor
   version family across the two independent Pythons in this repo, for
   operator-facing consistency, even though design.md is explicit that
   they intentionally share nothing at runtime. Both pins were verified to
   actually work by running the real commands (not just reading docs) —
   see the Work Unit Evidence table above.

### Issues Found / Risks

1. **Windows path (`INSTALAR_Y_CORRER.bat`) not executed end-to-end this
   session** — no Windows sandbox available (this session's sandbox is
   Linux, same constraint noted in slices 1–2's apply-progress, just
   confirmed newly for THIS session too — CLAUDE.md previously said "Windows-
   only" sandbox, which was not the case this session, but the .bat itself
   was still never run for real either way). The `.bat`'s uv-provisioning
   logic was written to mirror the `.sh` logic that WAS run for real
   (identical uv version pin, identical `uv python install`/`uv venv`/`uv
   pip install`/acceptance-check sequence, identical `-m cli` invocation
   fix), but batch-file quoting/escaping (`^` line continuations,
   `!VAR!` delayed expansion, `for /d` zip-extraction fallback logic) was
   reasoned about, not executed by an interpreter. This is the same class
   of residual risk design.md's own risk #4 already names ("CI can't run
   the Windows-specific paths ... manual/sandbox during apply").
2. **`Ejecutar_instalar.sh`'s pre-existing gap (documented in CLAUDE.md
   before this session): it never vendors Node/Maven under `_tools/` — it
   only `require`s them on `PATH`.** `cli/core/builder.py` (slice 1)
   resolves `npm`/`mvn` exclusively via `cfg.tools.node`/`cfg.tools.maven`
   (i.e. `_tools/node`, `_tools/maven`), never system PATH. This means on a
   machine provisioned purely by `Ejecutar_instalar.sh` (no prior `.bat`
   run to seed `_tools/node`/`_tools/maven`), the CLI's `build` command
   would fail with a missing-executable error, since those `_tools/`
   subdirectories are never populated by this script. This is a
   **pre-existing gap**, not introduced by slice 3 (it predates this
   change and is unrelated to uv/cli-venv), already documented in
   CLAUDE.md's "Problemas conocidos" table, and explicitly out of scope
   for this task list (no task in Phase 3.1–3.4 asks to vendor Node/Maven
   on Linux). Not fixed here — flagged for visibility, since it means the
   CLI's `build` action is not actually usable end-to-end via
   `Ejecutar_instalar.sh` alone on a machine without java/mvn/node already
   on PATH in a way `builder.py` can find. `start`/`status`/etc. (which
   don't touch `builder.py`) are unaffected, as demonstrated by the real
   `status` call in this session's smoke run.
3. **`build`/`start` CLI actions not exercised in this session's real run**
   — the smoke test in the Work Unit Evidence table only exercised
   `status`+`q` (no pre-built jar/frontend was available, and building
   for real would take several minutes and risk toolchain-availability
   noise unrelated to slice 3's actual changes). Their wiring is already
   covered by slice 1/2's unit and Pilot tests (`test_builder.py`,
   `test_plain_runner.py`'s `test_start_action_...`,
   `test_tui_app.py`'s build/start key-binding tests) — this session adds
   no new risk here since `cli/` itself was not modified.
4. **CI (`.github/workflows/cli-tests.yml`) not observed running in
   GitHub Actions this session** — same caveat carried over from slices
   1–2 (no PR opened this session).

### Remaining Tasks

Only task **3.5.1** (Windows manual/sandbox acceptance run of
`INSTALAR_Y_CORRER.bat`) remains unchecked — requires a Windows sandbox not
available this session. All code, script, and doc changes for slice 3 are
complete and were validated as thoroughly as a Linux-only sandbox allows
(the equivalent POSIX path, `Ejecutar_instalar.sh`, WAS run for real
end-to-end — see 3.5.2). This mirrors the project's own pre-existing,
already-accepted convention (see CLAUDE.md's known-issues table) of
documenting Windows-only manual validation gaps rather than blocking on
them. `apply` phase moves to `phases_completed` in `state.yaml` on that
basis; the Windows acceptance run is flagged as a residual risk for
`sdd-verify`/a future session with Windows access, not silently dropped.
Next: `sdd-verify`.

### Status

Slice 3: **4/5 tasks in Phase 3.5 complete (3.5.2, 3.5.3 done; 3.5.1
Windows acceptance NOT run — no sandbox available), all other phases
(3.1–3.4) fully complete. 67/67 `pytest tests/cli` passing against the real
`_tools/cli-venv` this session's `Ejecutar_instalar.sh` run produced.**
All 3 slices of `native-cli-installer` are implementation-complete.
`git status --porcelain` confirms: `menu.ps1`/`menu.sh`/`tests/menu.Tests.ps1`/
`tests/menu_test.sh` show `D` (deleted); `INSTALAR_Y_CORRER.bat`/
`Ejecutar_instalar.sh`/`CLAUDE.md`/`docs/ARCHITECTURE.md`/`SKILL.md`/
`docs/API_REFERENCE.md`/`docker-compose.yml` show modified; `cli/`/
`tests/cli/` untouched (still shown as previously-untracked new files from
slices 1–2, since this change has not been committed yet this session).

---

## Batch 2 — Slice 2 (Textual TUI + graceful degradation) — 2026-07-25

Engram MCP was disconnected this session too — all context read from disk
(`tasks.md`, `design.md`, `specs/native-cli-orchestration/spec.md`, this
file's Batch 1 section, the real `cli/core/*.py` from slice 1 to match
conventions/DI style). Network WAS available this session (unlike the
stated worst case) — confirmed by reaching `pypi.org` and successfully
`pip install`-ing `textual==8.2.8` + `pytest-asyncio` into a throwaway
venv, so the Textual Pilot tests were written AND executed, not just
written. Context7 MCP tools were not exposed in this session's toolset
(not present in the available function list despite the system-prompt
instruction to use them); verified all Textual APIs directly against the
actually-installed `textual==8.2.8` package's source/signatures instead
(`inspect.signature`/`inspect.getsource` on `App.run_test`, `Pilot.press`/
`.click`, `work()`'s decorator source, `App.run_worker`, `Worker.__init__`,
`Binding`, `Static.update`, `Log.write_line`) — arguably a stronger
guarantee than docs for this exact pin, since it's the literal code that
will run.

### Completed Tasks (Slice 2 — all of it)

- [x] 2.1.1–2.1.3 — `cli/__main__.py`: `detect_interactive()` (pure
  function: `--plain` / `NO_COLOR` / `TERM=dumb` / non-tty stdout-or-stdin
  / legacy `cmd.exe` → plain; else TUI), `is_legacy_cmd_without_ansi()`
  (Windows + absence of `WT_SESSION`/`TERM_PROGRAM`/`ANSICON`/
  `ConEmuANSI=ON`), `main()` wiring with DI points (`cfg`, `environ`,
  `stdout_isatty`, `stdin_isatty`) mirroring slice 1's DI style
  (`RestClient.opener`, `ProcessManager.popen_factory`). `main()` also
  degrades to the plain runner if `cli.tui.app` fails to import at all
  (Textual absent) — stronger than the spec strictly requires, but "never
  crash" is this module's rule throughout its docstring and code.
- [x] 2.2.1–2.2.2 — `cli/plain/runner.py`: `PlainRunner` class + module-
  level `run()`; reads commands via `self.in_.readline()` (never
  `input()`) so a piped/non-tty stdin hits EOF and exits the loop cleanly
  instead of raising or hanging; every `dispatch()` action is wrapped so a
  `CliError`/any exception is caught and printed, never propagated (except
  the `finally: shutdown_all()` teardown funnel, which still runs even if
  something above it misbehaves). Zero `textual`/`rich` imports — asserted
  by a dedicated test that inspects the module's source text.
- [x] 2.3.1–2.3.4 — `cli/tui/widgets.py` (`StatusPanel`, `MenuPanel`,
  `LogTail` — presentation only) + `cli/tui/app.py` (`FashionScraperApp`):
  single-letter key `Binding`s (`b`/`u`/`s`/`r`/`t`/`l`/`a`/`x`/`o`) each
  map 1:1 to a `core/` call; `q` and `ctrl+c` both use `priority=True`
  bindings to `action_quit_app` (verified empirically that `priority=True`
  overrides Textual's own default `ctrl+c → help_quit` system binding).
  ALL `core/` dispatch (scrape/retrain/status/site CRUD/build/start) goes
  through one `@work(thread=True)` worker (`_run_core`), confirmed via
  `inspect.getsource(work)` that `thread=True` routes through
  `Worker._run_threaded()`/a real OS thread, not a coroutine — so nothing
  ever blocks the Textual event loop, satisfying the task's explicit
  "long-running calls must run OFF the UI thread via Textual workers"
  requirement for every menu action, not just scrape/build. Results are
  marshalled back via `call_from_thread`.

### Files Created

| File | Purpose |
|------|---------|
| `cli/__main__.py` | capability detection + mode routing (task 2.1) |
| `cli/plain/__init__.py`, `cli/plain/runner.py` | plain fallback presenter (task 2.2) |
| `cli/tui/__init__.py`, `cli/tui/app.py`, `cli/tui/widgets.py` | Textual presenter (task 2.3) |
| `tests/cli/test_main.py` | 15 tests (routing + `is_legacy_cmd_without_ansi`) |
| `tests/cli/test_plain_runner.py` | 16 tests |
| `tests/cli/test_tui_app.py` | 11 tests (Pilot-based; `pytest.importorskip("textual")` guarded) |

`cli/requirements.txt` updated: `textual==8.2.8` pinned (was a `<pin-tbd>`
placeholder from slice 1).

### Test Run (real output — RAN, not written-but-unrun)

Two throwaway venvs this session, both bootstrapped the same way as slice
1 (`python3 -m venv` + pip, since the sandbox's system Python ships no
`pip`/`ensurepip`):

**Venv A — `textual==8.2.8` + `pytest==9.1.1` + `pytest-asyncio==1.4.0` installed:**

```
$ python -m pytest tests/cli -v
...
======================== 67 passed in 2.13s =========================
```

All 67 tests green, including all 11 Textual `Pilot`-based tests in
`test_tui_app.py` (key-press → `core/` call wiring for every menu action,
`@work(thread=True)` confirmed via a spy on `App.run_worker`, `q`/`ctrl+c`
→ `shutdown_all()` + `app.is_running is False`, status payload reflected
into `StatusPanel.status_text`).

**Venv B — `pytest==9.1.1` only, Textual NOT installed (`import textual`
raises `ModuleNotFoundError`):**

```
$ python -m pytest tests/cli -q
.........................s..............................
55 passed, 2 skipped in 0.10s
```

The 2 skips are: (1) `test_main_routes_to_tui_when_interactive` (an
explicit `pytest.importorskip("textual")` guard — this one test
inherently needs Textual importable to patch `cli.tui.app.run`), and (2)
the entire `test_tui_app.py` module (module-level `importorskip`, so
pytest reports it as one collection-level skip rather than 11 individual
skips). All 55 *other* tests — including every plain-runner test and
every other routing test (piped/`NO_COLOR`/`TERM=dumb`/non-tty/`--plain`/
legacy-cmd.exe, and the "Textual not importable → falls back to plain"
test) — pass with zero Textual dependency, which is the literal
requirement stated in the task brief ("plain runner tests must pass
without Textual installed" / "routing tests should run WITHOUT needing
Textual installed").

**Manual smoke (partial — task 2.4.1):** `echo -e "status\nq\n" | python
-m cli --plain` in this sandbox actually reached a live backend already
running on `:3000` and printed a real `GET /api/status` payload
(`{'status': 'DONE', 'mensaje': 'Datos restaurados: 4629 productos', ...}`),
then quit cleanly on `q`. This is a genuine (if partial) end-to-end smoke
of the REST wiring, not a mock. NOT smoked: the interactive Textual TUI
against a real TTY (none available in this tool-driven session — covered
instead by the Pilot tests, which drive the actual widget tree headlessly
via `App.run_test()`), and `start`/`build` (would need a full toolchain
build, out of scope for this apply pass). No orphan-process risk from the
untested paths either way, since `ProcessManager.shutdown_all()` only
tears down processes it itself spawned via `launch_backend`/
`launch_frontend` — it never touches the pre-existing backend the smoke
test talked to.

### Pins / Inferences

- **`textual==8.2.8`** — resolved by `pip install textual` at
  implementation time (2026-07-25); the slice-1 placeholder comment said
  `<pin-tbd-in-slice-2>`, now filled in. Pulls in `rich`/`markdown-it-py`
  as transitive deps; `cli/plain/runner.py` imports none of that tree.
- **`Binding(..., priority=True)` for `q`/`ctrl+c`** — not in design.md
  explicitly, but required: Textual's own `App.BINDINGS` default already
  binds `ctrl+c` to a system `help_quit` action; without `priority=True`
  on our own binding, our `action_quit_app` would not run for `ctrl+c`.
  Empirically verified both with and without `priority=True` before
  committing to this design (see the ad-hoc scripts run during this apply
  pass, not persisted as test files but reproducible via the exact same
  assertions in `test_tui_app.py::test_ctrl_c_binding_tears_down_processes_and_exits`).
- **All `core/` dispatch (not just scrape/build) routed through
  `@work(thread=True)`** — the task brief said "long-running calls
  (scrape/build)" must be off-thread; status/site-CRUD/open-dashboard are
  typically fast, but routing everything through the same single
  `_run_core` worker helper is simpler to reason about and reviews as one
  code path instead of two, and costs nothing (a fast call just finishes
  the thread quickly). Flagged as a deliberate small deviation-by-
  generalization, not a scope violation.
- **`is_legacy_cmd_without_ansi()` heuristic** — design.md names the
  scenario ("legacy `cmd.exe` without ANSI support") but doesn't specify
  the exact detection algorithm. Implemented as: Windows AND none of
  `WT_SESSION`/`TERM_PROGRAM` (modern Windows Terminal/PowerShell hosts)
  AND none of `ANSICON`/`ConEmuANSI=ON` (third-party ANSI consoles) are
  set → legacy. This is a reasonable, commonly-used heuristic but is an
  inference, not a value verified against a real legacy `cmd.exe` (no
  Windows sandbox available this session, consistent with slice 1's
  documented Windows-manual-only constraint).
- **Context7 MCP unavailable this session** — the task brief instructed
  using Context7 to verify Textual's API; the `resolve-library-id`/
  `query-docs` tools were not present in this session's actual tool
  schema (only Read/Edit/Write/Bash were available), so API verification
  was done directly against the real installed `textual==8.2.8` package's
  source instead (see the batch-2 preamble above) — same goal (verify
  against ground truth, not training-data memory), different mechanism.

### Deviations from Design

None structural. `design.md §2.1`'s `detect_interactive()` pseudocode is
implemented verbatim (same short-circuit order: `--plain` → `NO_COLOR` →
`TERM=dumb` → non-tty → legacy cmd.exe → else interactive). The core/tui/
plain split matches `design.md §2`'s tree exactly. One small addition not
in design.md: `main()`'s Textual-absent fallback to plain (see Pins above)
— purely additive robustness, not a deviation from anything specified.

### Issues Found / Risks

1. **Interactive TUI never smoked against a real TTY** — this sandbox is
   fully tool-driven (no PTY attached to a human terminal this session).
   The Pilot-based tests are a strong substitute (they drive the actual
   `FashionScraperApp` widget tree, key bindings, and workers headlessly)
   but are not a substitute for a human confirming the rendered layout
   looks right. Flagged for the eventual task 3.5.1/3.5.2 Windows/Linux
   end-to-end sandbox runs.
2. **`start`/`build` menu actions not manually smoked** — would require
   the full vendored toolchain (`_tools/jdk21`, `_tools/maven`,
   `_tools/node`) to actually build the project, which the CLAUDE.md
   "Gotchas de entorno" section already documents as unavailable in this
   sandbox (same constraint slice 1's apply-progress noted). Unit/Pilot
   tests cover the wiring (`action_do_build`/`action_start_services` call
   the right `core/` functions with the right args) via monkeypatched
   `core/` collaborators; the real subprocess paths remain untested here.
3. **Textual's exact default-binding precedence rules are undocumented
   in the design doc** — relied on empirical verification (see Pins)
   rather than a spec citation. If a future Textual version changes how
   `priority=True` bindings interact with system bindings, this is the
   place that would need re-verifying.
4. `INSTALAR_Y_CORRER.bat`, `Ejecutar_instalar.sh`, `menu.ps1`, `menu.sh`
   remain completely untouched — slice 2 constraint honored (verified via
   `git status --porcelain`).

### Remaining Tasks

Slice 1: none — fully complete (see Batch 1 section above).
Slice 2: none — fully complete.

Slice 3 (installer shrink + uv + `menu.*` retirement, IRREVERSIBLE) — NOT
started, per tasks.md's stated slice order and this change's explicit
instruction that slice 3 was out of scope for this apply pass.

### Status

Slice 2: **4/4 phases complete, 67/67 tests passing (with Textual
installed); 55/57 passing + 2 clean skips (without Textual installed,
proving zero hard dependency on the plain/routing path).** `apply` phase
is still NOT marked complete in `state.yaml` — slice 3 remains.

---

## Batch 1 — Slice 1 (Headless core + pytest) — 2026-07-24

Engram MCP was disconnected this session — all context read from disk
(`tasks.md`, `design.md`, `specs/*.md`, `openspec/config.yaml`, `CLAUDE.md`,
plus the real `ApiController.java` to confirm the exact `/api/sitios`
request/response shape design.md's summary table only approximated).

## Completed Tasks (Slice 1 — all of it)

- [x] 1.0.1–1.0.3 — `tests/cli/conftest.py` scaffolding; `cli/requirements.txt` dependency list (pytest pin); `.github/workflows/cli-tests.yml` CI job (path-filtered, additive, does not gate `backend-tests.yml`)
- [x] 1.1.1–1.1.2 — `cli/core/config.py`: repo-root discovery (marker-based: `scraper/` + `frontend/` dirs + `INSTALAR_Y_CORRER.bat`), `_tools/` toolchain path resolution, `Ports` (backend 3000 / frontend 5173 / postgres 5432)
- [x] 1.2.1 — `cli/core/errors.py`: `CliError` base + `ConfigError`/`EnvGenError`/`BuildError`/`RestError`/`ProcessError`, each with `message` + optional `action`
- [x] 1.3.1–1.3.7 — `cli/core/env_file.py`: `parse_keys`/`parse_env`/`compute_defaults`/`generate_env` — create-if-absent, additive-reconcile, never-overwrite, `force=True` full-overwrite escape hatch, secrets never logged by value (in fact no value is ever logged — key names only, a strictly stronger guarantee than the spec requires)
- [x] 1.4.1–1.4.3 — `cli/core/builder.py`: `build_project()` running `generate_env → parse_env → npm install → npm run build → mvn clean package → jar copy` in that exact order; `VITE_API_BASE_URL` is baked into `child_env` (`os.environ | env_values`) before the `npm run build` call
- [x] 1.5.1–1.5.3 — `cli/core/rest.py`: stdlib-only `RestClient` (`urllib.request` + `json.dumps`); injection-safety ordering gate is green; endpoints verified against the real `ApiController.java` (`GET /api/status`, `POST /api/scrape`, `POST /api/ml/entrenar`, `GET/POST /api/sitios`, `DELETE /api/sitios/{nombre}`)
- [x] 1.6.1–1.6.3 — `cli/core/processes.py`: `ProcessManager` launches backend (always appends `-DDATABASE_PASSWORD=<value>`, empty string included) and frontend (`npm run preview -- --port 5173 --strictPort`); `shutdown_all()` funnel with injectable `taskkill`/`killpg` for cross-platform-from-one-CI-OS testing
- [x] 1.7.1 — full suite green, diff scope confirmed additive-only

## Files Created

| File | Purpose |
|------|---------|
| `cli/__init__.py`, `cli/core/__init__.py` | package docstrings, no logic |
| `cli/core/errors.py` | typed exception hierarchy (task 1.2) |
| `cli/core/config.py` | repo-root discovery + toolchain paths + ports (task 1.1) |
| `cli/core/env_file.py` | template-driven `.env` generation (task 1.3) |
| `cli/core/builder.py` | build orchestration + VITE ordering (task 1.4) |
| `cli/core/rest.py` | stdlib REST client + injection-safe JSON (task 1.5) |
| `cli/core/processes.py` | backend/frontend lifecycle + teardown funnel (task 1.6) |
| `cli/requirements.txt` | CLI Python dependency list, pytest pinned |
| `tests/cli/conftest.py` | shared fixtures (`repo_root`, `fake_repo`) |
| `tests/cli/test_config.py` | 6 tests |
| `tests/cli/test_env_file.py` | 6 tests |
| `tests/cli/test_builder.py` | 3 tests |
| `tests/cli/test_rest.py` | 4 tests |
| `tests/cli/test_processes.py` | 6 tests |
| `.github/workflows/cli-tests.yml` | new CI job, path-filtered, additive |

## Test Run (real output)

Environment note: the sandbox's system Python (3.12, Debian-managed) ships
no `pip`/`ensurepip` by module policy. Bootstrapped a throwaway venv via
`python3 -m venv` (bundles pip via the `python3-pip-whl` package already on
the box) at `/tmp/.../scratchpad/venvtest`, installed `pytest` from
`cli/requirements.txt`'s pin, and ran the suite from the repo root:

```
$ python -m pytest tests/cli -v
============================= test session starts ==============================
platform linux -- Python 3.12.3, pytest-9.1.1, pluggy-1.6.0
collected 25 items

tests/cli/test_builder.py::test_vite_api_base_url_present_in_env_before_npm_run_build PASSED
tests/cli/test_builder.py::test_build_sequence_runs_in_exact_order PASSED
tests/cli/test_builder.py::test_build_raises_typed_error_when_jar_artifact_missing PASSED
tests/cli/test_config.py::test_find_repo_root_resolves_from_a_nested_cwd PASSED
tests/cli/test_config.py::test_find_repo_root_resolves_from_root_itself PASSED
tests/cli/test_config.py::test_find_repo_root_raises_config_error_when_no_markers_found PASSED
tests/cli/test_config.py::test_toolchain_paths_resolve_expected_subdirs PASSED
tests/cli/test_config.py::test_load_config_wires_repo_root_tools_and_ports PASSED
tests/cli/test_config.py::test_ports_expose_backend_and_frontend PASSED
tests/cli/test_env_file.py::test_create_if_absent_writes_every_schema_key_with_computed_or_default PASSED
tests/cli/test_env_file.py::test_existing_values_untouched_on_rerun PASSED
tests/cli/test_env_file.py::test_additive_reconcile_appends_missing_key_without_touching_existing_bytes PASSED
tests/cli/test_env_file.py::test_regenerate_force_overwrites_every_key_from_computed PASSED
tests/cli/test_env_file.py::test_default_run_never_triggers_full_overwrite_regardless_of_drift PASSED
tests/cli/test_env_file.py::test_secrets_never_echoed_in_logs PASSED
tests/cli/test_processes.py::test_backend_launch_always_appends_database_password_even_when_empty PASSED
tests/cli/test_processes.py::test_backend_launch_appends_nonempty_password_too PASSED
tests/cli/test_processes.py::test_teardown_windows_path_calls_taskkill_tree_kill_for_every_tracked_pid PASSED
tests/cli/test_processes.py::test_teardown_posix_path_sigterm_then_sigkill_on_timeout PASSED
tests/cli/test_processes.py::test_teardown_posix_path_no_escalation_when_process_exits_cleanly PASSED
tests/cli/test_processes.py::test_teardown_tolerates_already_dead_pid PASSED
tests/cli/test_rest.py::test_injection_safety_hostile_name_round_trips_as_a_single_json_field PASSED
tests/cli/test_rest.py::test_injection_safety_client_never_invokes_a_shell_subprocess PASSED
tests/cli/test_rest.py::test_hostile_site_name_survives_url_path_encoding_on_delete PASSED
tests/cli/test_rest.py::test_menu_actions_map_to_existing_endpoints_only PASSED

============================== 25 passed in 0.09s ==============================
```

**1.5.1 [ORDERING GATE] status: GREEN.** Both injection-safety tests in
`tests/cli/test_rest.py` (structural JSON round-trip + "no subprocess ever
constructed" via `monkeypatch`ing `subprocess.run`/`subprocess.Popen` to
raise `AssertionError` if called) pass. Slice 3 tasks 3.3.2–3.3.5 (deleting
`menu.ps1`/`menu.sh`/their tests) may proceed once slices 2–3 land, per
this gate.

## Pins / Inferences

- **pytest pin**: `pytest==9.1.1` — resolved by `pip install pytest` at
  implementation time (2026-07-24); no prior CLI pytest pin existed to
  match, so this is a fresh "sensible current" pin per the task's
  instruction, not a carried-over one. Documented inline in
  `cli/requirements.txt`.
- **`/api/sitios` request/response shape**: design.md's summary table says
  `POST /api/sitios (JSON body)` generically; I read the actual
  `ApiController.java` (`agregarSitio`/`eliminarSitio`) to get the exact
  contract: `POST /api/sitios` body is `{nombre, url, plataforma}` (not
  `rubro`, which is what CLAUDE.md's abbreviated table implied), and
  delete is `DELETE /api/sitios/{nombre}` (path parameter, not a query
  parameter as CLAUDE.md's grouped table row suggested). `rest.py` and its
  tests follow the verified source, not the abbreviated docs.
- **Vendored `npm`/`mvn` executable paths**: inferred from
  `INSTALAR_Y_CORRER.bat`'s existing `%NODE_DIR%\npm.cmd` (flat Windows
  Node.js zip layout) and `%MVN_DIR%\bin\mvn.cmd` (Maven ships `bin/` on
  both platforms) — `builder.py`'s `_npm_cmd`/`_mvn_cmd` mirror this
  exactly for Windows, and use the standard POSIX Node.js tarball layout
  (`bin/npm`) for Linux, matching design.md's "vendored toolchain only,
  never system PATH" invariant. Not independently unit-tested by exact
  path string (tests assert command *args*, not the resolved executable
  path, since the path is a pure, low-risk platform switch) — flagged here
  for visibility.
- **`SCRAPER_MODELS_ROOT` computed as absolute path**: `.env.example`'s
  own default is the relative `./scraper/_models`, but CLAUDE.md documents
  the installer generating it as an *absolute* `<repo>/scraper/_models`
  (matching `HF_HOME` resolution notes). `compute_defaults()` follows
  CLAUDE.md's documented installer behavior (absolute), not the relative
  example default.
- **`.env` generator never logs any value, not just secrets**: stronger
  than task 1.3.5 requires (which only requires the `DATABASE_PASSWORD`
  value specifically never appear in a log line) — simpler to reason about
  and closes any future accidental leak of a new secret-shaped key added
  to `.env.example` later (e.g. an API key) without code changes here.

## Deviations from Design

None structural. `design.md §3.1`'s `generate_env(example_path, env_path,
computed, force)` signature is implemented verbatim. `design.md §4.1`'s
6-step build sequence and `design.md §5`'s process contract (including the
`-DDATABASE_PASSWORD=` load-bearing detail) are implemented verbatim.

## Issues Found / Risks

1. **CI job untested in GitHub Actions** — `.github/workflows/cli-tests.yml`
   was added but no PR was opened this session, so the path-filter/job
   itself has only been reasoned about, not observed running. The
   underlying `pytest tests/cli` command was verified locally (25/25
   green).
2. **Sandbox has no real vendored `_tools/`** — all toolchain-path tests
   use a `fake_repo`/`_cfg` fixture with synthetic `_tools/` paths; nobody
   has run `build_project()`/`ProcessManager` against the real
   `_tools/node`, `_tools/maven`, `_tools/jdk21` this session (no Node/Java
   in this sandbox per the project's own documented dev-environment gotcha
   — see `outfit-gymrat-visibility`'s apply-progress.md for the same
   constraint). This is expected for slice 1 (pure unit tests over
   injected subprocess runners) but is worth flagging before slice 2's
   manual smoke tasks (2.4.1) or slice 3's installer end-to-end tasks
   (3.5.1/3.5.2).
3. `cli/__main__.py`, `cli/tui/`, `cli/plain/` intentionally do NOT exist
   yet — those are slice 2. Nothing under `INSTALAR_Y_CORRER.bat`,
   `Ejecutar_instalar.sh`, `menu.ps1`, or `menu.sh` was touched — slice 1
   constraint honored.

## Remaining Tasks

Slice 1: none — fully complete.

Slice 2 (Textual TUI + graceful degradation) — NOT started:
- [ ] 2.1.1–2.1.3 `__main__.py` capability detection & mode routing
- [ ] 2.2.1–2.2.2 `plain/runner.py`
- [ ] 2.3.1–2.3.4 `tui/app.py` + `tui/widgets.py`
- [ ] 2.4.1–2.4.2 slice 2 wrap-up (manual smoke + full suite)

Slice 3 (installer shrink + uv + `menu.*` retirement, IRREVERSIBLE) — NOT
started, blocked on slice 2 landing first per tasks.md's stated slice
order (1.5.1 gate is already satisfied, but the deletion tasks
3.3.2–3.3.5 still require slice 2 to land first per the documented slice
dependency chain: slice 3 depends on slice 1 + slice 2).

## Workload / PR Boundary

- Single work unit: all of Slice 1, applied together (7 new-module files +
  6 test files + 1 CI workflow + dependency list). No partial/rollback
  complexity within the slice — revert `cli/`, `tests/cli/`,
  `.github/workflows/cli-tests.yml` as one unit if needed.
- Per tasks.md's delivery shape, this is intentionally the *only* slice
  landed this apply run — slices 2 and 3 are separate, later apply passes.

## Status

Slice 1: **7/7 phases complete, 25/25 tests passing.** `apply` phase is
NOT marked complete in `state.yaml` — 2 of 3 slices remain.
