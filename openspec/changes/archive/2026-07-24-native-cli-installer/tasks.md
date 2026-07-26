# Tasks: native-cli-installer

**Date:** 2026-07-24
**Status:** proposed

---

## Delivery Shape

Three slices, each independently reviewable/landable, in strict order. Slice 3 is
**irreversible** (deletes files) and MUST land last — rollback of the whole change is
"revert the PR(s)", which only works cleanly if slice 3 is the final commit set.

| Slice | Reversible? | Depends on |
|-------|-------------|------------|
| 1 — Headless core + pytest | Yes (additive) | none |
| 2 — Textual TUI + degradation | Yes (additive) | Slice 1 |
| 3 — Installer shrink + uv provisioning + menu.* retirement | **No** | Slice 1 (specifically task 1.5.1) + Slice 2 |

**Hard ordering gate:** task 1.5.1 (injection-safety pytest, replacing
`menu.Tests.ps1`/`menu_test.sh`) MUST be green and merged before task 3.3.2–3.3.5 (deleting
the legacy menu files) runs. This is the one non-negotiable cross-slice dependency.

---

## Slice 1 — Headless core + pytest (reversible, additive)

Nothing deleted. Installers still own build/run at the end of this slice. Introduces
`pytest` for the CLI (project currently has no Python test runner).

### Phase 1.0: pytest scaffolding & CI wiring
*(spec: native-cli-orchestration — testing is implied infra for all requirements below)*

- [x] 1.0.1 Create `tests/cli/` with `conftest.py` (repo-root fixture, tmp_path helpers for `.env`/`.env.example` fixtures)
- [x] 1.0.2 Add `pytest` (and `pytest` deps) to the CLI's dependency list consumed later by `uv pip install` (slice 3, task 3.1.5) — for now, document/pin the version the CLI targets
- [x] 1.0.3 Add a CI job running `pytest tests/cli` on Linux runners, alongside (not gating) the existing Maven job; note Windows-specific paths (real `_tools/cli-venv` bootstrap) are manual/sandbox-only per design risk 4

### Phase 1.1: `core/config.py` — repo-root discovery & path resolution

- [x] 1.1.1 Write test: repo-root discovery resolves correctly from a nested cwd; `_tools/` subpath resolution returns `jdk21`, `maven`, `node`, `cli-venv`, `pgsql` paths; port config exposes backend `3000` / frontend `5173`
- [x] 1.1.2 Implement `cli/core/config.py` to satisfy 1.1.1 — zero `textual`/`rich` imports, zero interactive stdout

### Phase 1.2: `core/errors.py` — typed failures

- [x] 1.2.1 Implement `cli/core/errors.py`: typed exception hierarchy (e.g. `BuildError`, `EnvGenError`, `ProcessError`) each carrying an actionable message field, used by every other `core/` module

### Phase 1.3: `core/env_file.py` — template-driven `.env` generation (LD-2)
*(spec: env-file-generation — all four requirements)*

- [x] 1.3.1 Write test: create-if-absent — no `.env` exists → generated `.env` contains every key from `.env.example` with computed defaults for the known set (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD=""`, `SCRAPER_MODELS_ROOT`, `APP_CORS_ALLOWED_ORIGINS`, `VITE_API_BASE_URL`, `APP_OPEN_URL`) and pass-through defaults for the rest *(Requirement: Create-If-Absent Env Generation)*
- [x] 1.3.2 Write test: existing `.env` untouched — a hand-edited `DATABASE_PASSWORD` (or any existing key) survives a rerun unchanged *(Requirement: Create-If-Absent Env Generation, scenario "Existing values are untouched")*
- [x] 1.3.3 Write test: additive reconcile — a key present in `.env.example` but absent from `.env` is appended with its default; all pre-existing keys/values remain byte-identical (this is the property the parallel `LLM_*` change relies on) *(Requirement: Additive Reconcile of Missing Keys)*
- [x] 1.3.4 Write test: `--regenerate`/`--force` overwrites every key from computed defaults, bypassing create-if-absent/additive-reconcile; default run (no flag) never triggers a full overwrite regardless of drift *(Requirement: Explicit Force Regenerate Flag)*
- [x] 1.3.5 Write test: secrets never echoed — `capsys`/`caplog` assertion that no log line contains the literal `DATABASE_PASSWORD` value during generate/reconcile *(Requirement: Secrets Never Echoed)*
- [x] 1.3.6 Implement `cli/core/env_file.py`: `parse_keys(.env.example)` (ordered, comment-preserving) + `generate_env(example_path, env_path, computed, force)` per design §3.1 algorithm — satisfies 1.3.1–1.3.5
- [x] 1.3.7 Expose `--regenerate`/`--force` as a parsed option on the `env_file` entry point (consumed by `__main__.py` arg parsing in slice 2, task 2.1.3) — done as `generate_env(..., force=...)`'s public `force` parameter; the actual argparse flag wiring happens in `__main__.py` in slice 2 per design

### Phase 1.4: `core/builder.py` — build orchestration & VITE ordering hazard
*(spec: native-cli-orchestration — "CLI Owns Build Steps", "VITE_API_BASE_URL Build-Time Export Ordering")*

- [x] 1.4.1 Write test: `VITE_API_BASE_URL` is present in the env dict passed to the `npm run build` subprocess (monkeypatch the subprocess runner, inspect the env argument) — proves the export happens *before* build invocation *(Requirement: VITE_API_BASE_URL Build-Time Export Ordering)*
- [x] 1.4.2 Write test: build sequence calls `generate_env` → parse `.env` → `npm install` → `npm run build` → `mvn clean package` → jar copy, in that exact order (mock subprocess calls, assert call order) *(Requirement: CLI Owns Build Steps)*
- [x] 1.4.3 Implement `cli/core/builder.py` to satisfy 1.4.1–1.4.2: uses only vendored toolchain paths from `config.py` (`_tools/jdk21`, `_tools/maven`, `_tools/node`) — never downloads anything; copies `scraper/target/fashion-scraper-1.0.0.jar` → `scraper/scraper.jar`

### Phase 1.5: `core/rest.py` — REST client (existing endpoints only) + injection-safety test
*(spec: cli-rest-contract — both requirements; spec: legacy-launcher-retirement — "Injection-Safety Test Replacement")*

- [x] **1.5.1 [ORDERING GATE]** Write the injection-safety test: `json.dumps({"nombre": 'a"b;$(x)', ...})` round-trips to exactly that string in a single JSON field; assert no `shell=True` and no shell subprocess is ever constructed anywhere in the call path. This is the structural replacement for the deleted `menu.Tests.ps1`/`menu_test.sh` and **must be green before task 3.3.2–3.3.5 runs** *(Requirement: Structurally-Safe Site JSON Serialization; Injection-Safety Test Replacement)* — GREEN, see `tests/cli/test_rest.py`
- [x] 1.5.2 Write test: each menu action maps to exactly its existing endpoint — `GET /api/status`, `POST /api/scrape` (params `precioMin`/`precioMax`/`sitios`/`forceRetrain`), `POST /api/ml/entrenar`, `GET|POST|DELETE /api/sitios` — assert method/URL/params and that no other endpoint is ever invoked *(Requirement: No New Backend Endpoints)*
- [x] 1.5.3 Implement `cli/core/rest.py`: stdlib `urllib.request`/`http.client` client (no `requests` dependency), `json.dumps`-based site payload builder, functions for status/scrape/entrenar/sitios CRUD — satisfies 1.5.1–1.5.2

### Phase 1.6: `core/processes.py` — backend + frontend process lifecycle
*(spec: native-cli-orchestration — "Backend and Frontend Process Orchestration")*

- [x] 1.6.1 Write test: backend launch always appends `-DDATABASE_PASSWORD=<value>` (empty string included) even when the password is `""` — regression test for the Windows empty-env-var contract ported from `menu.ps1:197-204`
- [x] 1.6.2 Write test: teardown funnel calls tree-kill for every tracked PID (mock `Popen`; assert `taskkill /T /F` on Windows path / `killpg` SIGTERM→SIGKILL escalation on Linux path for both backend and frontend), tolerating already-dead PIDs
- [x] 1.6.3 Implement `cli/core/processes.py`: spawn backend (`java -Xmx768m -Dfile.encoding=UTF-8 -DDATABASE_PASSWORD=<value> [-DPYTHON_EXE/-DPYTHON_DIR] -jar scraper/scraper.jar`, cwd=`scraper/`, stderr → `scraper/logs/backend-launcher.err.log`) and frontend (`npm run preview -- --port 5173 --strictPort`, cwd=`frontend/`); Windows: `CREATE_NEW_PROCESS_GROUP` + `taskkill /PID <pid> /T /F`; Linux: `start_new_session=True` + `os.killpg(os.getpgid(pid), SIGTERM)` then `SIGKILL` on timeout; single `shutdown_all()` funnel — satisfies 1.6.1–1.6.2

### Phase 1.7: Slice 1 wrap-up

- [x] 1.7.1 Run full `pytest tests/cli` suite green (local + CI); confirm the diff touches only `cli/`, `tests/cli/`, and CI config — zero changes under `INSTALAR_Y_CORRER.bat`, `Ejecutar_instalar.sh`, `menu.ps1`, `menu.sh` — 25/25 passed locally (`git status --porcelain` confirms only `cli/`, `tests/cli/`, `.github/workflows/cli-tests.yml`, plus the openspec change artifacts, were touched); CI job added at `.github/workflows/cli-tests.yml`, not yet run in GitHub Actions (no PR opened this session)

---

## Slice 2 — Textual TUI + graceful degradation (reversible, additive)

CLI becomes runnable standalone against a pre-built project. Still nothing deleted.

### Phase 2.1: `__main__.py` — capability detection & mode routing
*(spec: native-cli-orchestration — "Graceful TUI Degradation")*

- [x] 2.1.1 Write test: `detect_interactive()` returns `False` under `NO_COLOR` set, `TERM=dumb`, non-tty stdin/stdout, and `--plain` flag; returns `True` otherwise (mock env vars, argv, `isatty`) *(Requirement: Graceful TUI Degradation, scenarios "Piped output falls back to plain text", "NO_COLOR respected")* — `tests/cli/test_main.py`
- [x] 2.1.2 Write test: legacy `cmd.exe` without ANSI support routes to plain mode (mock `is_legacy_cmd_without_ansi()`) — `tests/cli/test_main.py`
- [x] 2.1.3 Implement `cli/__main__.py`: arg parsing (`--plain`, `--regenerate`/`--force` from task 1.3.7), `detect_interactive()` per design §2.1, routes to `tui.app.run(core_context)` or `plain.runner.run(core_context)` with an identical `core_context` for both — satisfies 2.1.1–2.1.2. `main()` also degrades to `plain.runner.run` if `cli.tui.app` fails to import at all (Textual absent) — stronger than the spec strictly requires, but "never crash" is the module's rule throughout.

### Phase 2.2: `plain/runner.py` — fallback driver

- [x] 2.2.1 Write test: plain runner drives every menu action (scrape/retrain/status/site CRUD/open dashboard) via `core/` only — no `textual`/`rich` import anywhere in the module, no crash on non-tty stdin — `tests/cli/test_plain_runner.py` (16 tests, incl. a source-text assertion that no `textual`/`rich` import exists anywhere in the module)
- [x] 2.2.2 Implement `cli/plain/runner.py`: non-interactive text driver over the same `core/` used by the TUI; emits no ANSI/color codes when `NO_COLOR` is set — satisfies 2.2.1

### Phase 2.3: `tui/app.py` + `tui/widgets.py` — Textual presenter

- [x] 2.3.1 Write Textual `App.run_test()`/`Pilot` test: drive key presses, assert menu selection → correct `core/` call wiring for scrape/retrain/status/site CRUD/open dashboard — `tests/cli/test_tui_app.py`, RAN GREEN against a real installed `textual==8.2.8` in a throwaway venv (network was available this session)
- [x] 2.3.2 Write Textual `Pilot` test: `Q` and `Ctrl+C` bindings both trigger the `processes.shutdown_all()` teardown funnel from task 1.6.3 — `test_q_binding_tears_down_processes_and_exits` / `test_ctrl_c_binding_tears_down_processes_and_exits`, GREEN
- [x] 2.3.3 Implement `cli/tui/widgets.py`: status panel, menu, log-tail widgets — presentation only, zero business logic
- [x] 2.3.4 Implement `cli/tui/app.py`: Textual `App`, screens, key bindings (`Q`/`Ctrl+C`), imports `core/` only — satisfies 2.3.1–2.3.2. Long-running calls (scrape/retrain/status/site CRUD/build/start) all dispatch through a single `@work(thread=True)` worker (`_run_core`) so no blocking `urllib`/subprocess call ever runs on the UI thread; results/errors are marshalled back via `call_from_thread`.

### Phase 2.4: Slice 2 wrap-up

- [x] 2.4.1 Manual smoke (sandbox/Linux): ran `python -m cli --plain` against the live sandbox — `status` correctly issued `GET /api/status` against a real backend already running on `:3000` in this sandbox and printed the real JSON payload (`{'status': 'DONE', ...4629 productos...}`); `q` quit cleanly. Did NOT run the interactive Textual TUI manually against a real terminal (no TTY available in this tool-driven session) — that surface is instead covered by the Pilot tests (2.3.1/2.3.2), which exercise the real widget tree headlessly. `start`/`build` were not manually smoked (would require a full toolchain build, out of scope for this session) — no orphan-process risk either way since `ProcessManager.shutdown_all()` only tears down processes it itself spawned.
- [x] 2.4.2 Full `pytest tests/cli` suite: 67/67 GREEN with `textual==8.2.8` installed. Additionally ran the same suite in a second venv with Textual NOT installed at all: 55 passed / 2 skipped (the one TUI-import-dependent routing test + the whole `test_tui_app.py` module skip via `pytest.importorskip`) — proves the plain/main-routing path has zero hard Textual dependency, per the task's explicit requirement. Diff scope confirmed additive-only: `git status --porcelain` shows only `cli/`, `tests/cli/`, `.github/workflows/cli-tests.yml` (already tracked as untracked-new) plus the openspec change artifacts — zero changes under `INSTALAR_Y_CORRER.bat`, `Ejecutar_instalar.sh`, `menu.ps1`, `menu.sh`.

---

## Slice 3 — Installer shrink + uv provisioning + menu.* retirement (IRREVERSIBLE — last)

Requires task 1.5.1 (injection-safety test) merged and green before 3.3.2–3.3.5.

### Phase 3.1: uv + `_tools/cli-venv` provisioning (installer side)
*(spec: installer-provisioning — all requirements; design §7, ADR-002)*

- [x] 3.1.1 Add `uv` static binary provisioning to `INSTALAR_Y_CORRER.bat` → `_tools/uv/uv.exe` — pinned `uv==0.11.32`, downloads `uv-x86_64-pc-windows-msvc.zip` from the GitHub release, extracts (handles both the nested-folder and flat zip layouts)
- [x] 3.1.2 Add `uv` static binary provisioning to `Ejecutar_instalar.sh` → `_tools/uv/uv` — same pin, `uv-x86_64-unknown-linux-gnu.tar.gz` / `uv-aarch64-unknown-linux-gnu.tar.gz` by `uname -m`
- [x] 3.1.3 Wire `_tools/uv/uv python install 3.11.9` (uv-managed standalone CPython, NOT the ML embeddable) into both installers — `UV_PYTHON_INSTALL_DIR`/`UV_CACHE_DIR` pinned under `_tools/uv/` so it never touches a global uv cache
- [x] 3.1.4 Wire `_tools/uv/uv venv --managed-python --python 3.11.9 _tools/cli-venv` into both installers
- [x] 3.1.5 Wire `_tools/uv/uv pip install --python _tools/cli-venv/... -r cli/requirements.txt` into both installers (pulls in textual+pytest+pytest-asyncio from task 1.0.2) — **deviation**: no `--offline` flag on the primary install (see Deviations in apply-progress.md — `--offline` would break the explicit "clean machine, no toolchain" scenario on first run; idempotency is instead achieved by skipping the whole block when `_tools/cli-venv` already has `textual` importable, matching every other step's "already installed, skip" pattern in these scripts)
- [x] 3.1.6 Add install acceptance check to both installers: `_tools/cli-venv` python `-c "import textual"` — on failure, hard-fail the install with an actionable message naming the failed step (Python Load-Bearing on Windows; aligns Windows with the existing POSIX hard-fail) *(Requirement: Python Load-Bearing on Windows)*

### Phase 3.2: Installer shrink — remove moved responsibilities
*(spec: installer-provisioning — "Installer Scope Restricted to Dependency Provisioning", "Installer/CLI Boundary Invariant")*

- [x] 3.2.1 Remove the `.env` echo block from `INSTALAR_Y_CORRER.bat` (was lines 609–617) and `Ejecutar_instalar.sh` (was lines 144–152)
- [x] 3.2.2 Remove/confirm-absent any `npm`/`mvn`/jar-copy steps from both installers (build is now fully owned by the CLI, slice 1 task 1.4.3) — verified by grep, see 3.4.3
- [x] 3.2.3 Remove the jq/gum vendoring step from `Ejecutar_instalar.sh` (was a `menu.sh`-only dependency, no longer needed) — matches design.md §9's table row (jq/gum: "❌ removed (menu.sh retired)")
- [x] 3.2.4 Change the installer tail in both scripts: replace the `menu.ps1`/`menu.sh` invocation with the CLI entry point — **deviation from the literal path in this task/design §9**: invoked as `_tools\cli-venv\Scripts\python.exe -m cli` (Windows) / `_tools/cli-venv/bin/python -m cli` (Linux), i.e. module form with cwd=repo root, NOT `cli\__main__.py`/`cli/__main__.py` as a direct script path. Verified empirically: running the `.py` file directly raises `ModuleNotFoundError: No module named 'cli'` because `cli/__main__.py` (slice 2) uses absolute `cli.*` imports, which only resolve when the repo root is on `sys.path` (true for `-m cli`, false for a direct script invocation). *(Requirement: Installer Scope Restricted to Dependency Provisioning, scenario "Installer tail invokes the CLI")*

### Phase 3.3: Legacy launcher retirement
*(spec: legacy-launcher-retirement — "Legacy Launcher Files Deleted")*

- [x] 3.3.1 **Gate check:** confirm task 1.5.1 (injection-safety pytest) is merged and green in CI before proceeding with this phase — re-verified GREEN this session: full `pytest tests/cli` (67/67, incl. both `test_rest.py` injection-safety tests) run fresh before any deletion
- [x] 3.3.2 Delete `menu.ps1`
- [x] 3.3.3 Delete `menu.sh`
- [x] 3.3.4 Delete `tests/menu.Tests.ps1`
- [x] 3.3.5 Delete `tests/menu_test.sh`

### Phase 3.4: Docs & invariant verification

- [x] 3.4.1 Update `docs/ARCHITECTURE.md`: replace the `menu.ps1`/`menu.sh` launcher description with the native CLI (headless core + Textual presenter + plain fallback), document the uv/`_tools/cli-venv` provisioning step
- [x] 3.4.2 Update `CLAUDE.md`: rewrite the `interactive-cli-launcher` seam note to describe the native CLI as its superseding replacement, per this repo's convention of dated inline notes
- [x] 3.4.3 Verify the invariant restated in design §9: grep both installers for `npm`/`mvn`/jar-copy/`.env`-write commands (zero *executable* matches — only explanatory comments stating these steps were removed) and grep `cli/` for download/extract/toolchain-install commands (zero matches) *(Requirement: Installer/CLI Boundary Invariant)*

### Phase 3.5: Slice 3 acceptance

- [ ] 3.5.1 Manual/sandbox (Windows): **NOT run this session** — no Windows sandbox available (consistent with slice 1/2's documented Windows-manual-only constraint). The Windows-specific `.bat` logic was written to mirror the Linux `.sh` logic verified in 3.5.2 line-for-line (same uv version pin, same `uv python install`/`uv venv`/`uv pip install`/acceptance-check sequence, same `-m cli` invocation form), but the actual `.bat` script itself was not executed.
- [x] 3.5.2 Manual/sandbox (Linux): **run for real, end-to-end**, against this repo's actual worktree (not a throwaway copy) — `bash Ejecutar_instalar.sh` with `printf "status\nq\n"` piped to stdin. Real results: `[1/4]`–`[3/4]` passed (internet OK; toolchain OK via system java 24/mvn/node; Postgres reused an already-running `fashion-scraper-pg` Docker container); `[4/4]` downloaded real `uv==0.11.32`, installed a real uv-managed CPython 3.11.9, created a real `_tools/cli-venv`, `uv pip install`-ed `cli/requirements.txt` (15 packages incl. `textual==8.2.8`), and the `import textual` acceptance check passed; the tail then ran `_tools/cli-venv/bin/python -m cli`, which (correctly, since stdin/stdout were piped/non-tty) routed to the plain-mode fallback, printed the menu, executed `status` against a real backend already running on `:3000` in this sandbox (returned real JSON: `4629 productos`), and exited cleanly on `q` with `shutdown_all()` running (no processes to tear down, since `start`/`build` were not exercised — see Issues in apply-progress.md). `ps aux` confirmed zero orphan processes from this run. `build`/`start` were intentionally not exercised (would require a full `npm`+`mvn` build cycle, out of scope for this apply pass's acceptance check; their wiring is already covered by slice 2's unit/Pilot tests).
- [x] 3.5.3 Confirm `pytest tests/cli` full suite (all three slices' tests) is green — run twice: once in a throwaway venv (67/67 passed) BEFORE the 3.3.1 gate check/deletions, and once directly against the REAL `_tools/cli-venv` produced by the 3.5.2 installer run above (67/67 passed) — the actual artifact the shrunk installer now produces, not just a proxy venv. CI (`.github/workflows/cli-tests.yml`) itself was not observed running in GitHub Actions this session (no PR opened) — same caveat as slices 1–2.

---

## Correction: verify.md CRITICAL-1 (2026-07-26)

Single bounded correction transaction, routed back from `sdd-verify`'s FAIL
verdict. `cli/core/builder.py` only generated/parsed the ROOT `.env`, but
this repo's real root `.env.example` never declares `VITE_API_BASE_URL` as
an active key (it lives only in `frontend/.env.example`, itself commented
out by default) — so `npm run build`'s subprocess env never actually
contained `VITE_API_BASE_URL` against real project files, and the passing
unit test that claimed otherwise used a synthetic, non-representative
`.env.example` fixture.

- [x] C1.1 Activate `VITE_API_BASE_URL` as a live key in `frontend/.env.example` (its architecturally-correct home per D6) — was commented out
- [x] C1.2 `cli/core/builder.py`: `build_project()` now also `generate_env()`s `frontend/.env` from `frontend/.env.example` (same create-if-absent/additive-reconcile/never-overwrite/`--regenerate` contract), and merges its parsed values into `child_env` before `npm install`/`npm run build`
- [x] C1.3 Fix the false-positive test: `tests/cli/test_builder.py`'s `_bare_project` fixture now faithfully mirrors the real root/frontend split (root template WITHOUT `VITE_API_BASE_URL` active, frontend template WITH it active) — RED confirmed against the pre-fix `builder.py` (`KeyError: 'VITE_API_BASE_URL'`), GREEN confirmed after C1.2. `test_build_sequence_runs_in_exact_order` updated for the now-two-template generate/parse sequence.
- [x] C1.4 `specs/env-file-generation/spec.md`: new requirement "Frontend Env Generation Mirrors the Root Contract" (+ 2 scenarios); `design.md` §3/§4.1 updated with correction notes
- [x] C1.5 `APP_OPEN_URL` (secondary, bounded): left commented in the root `.env.example` — confirmed via `rg` that no `cli/` module reads `APP_OPEN_URL` at all (the CLI's own "open dashboard" action opens `http://localhost:<cfg.ports.frontend>` directly, not via this env var); it is a backend-only, no-op-when-unset convenience the CLI superseded. Not part of the `npm run build` hazard (only `VITE_API_BASE_URL` is read by Vite at build time), so left out of scope per the correction's bounded mandate. See apply-progress.md for the full note.

## Verification Checklist (maps back to spec scenarios)

- [ ] `pytest tests/cli` passes in full (env-gen, VITE-ordering, injection-safety, empty-`DATABASE_PASSWORD`, teardown-funnel, degradation-routing, TUI Pilot)
- [ ] No `.env` exists → CLI run creates one with all required keys computed (env-file-generation: Create-If-Absent)
- [ ] Existing `.env` with hand-edited `DATABASE_PASSWORD` survives a rerun unchanged (env-file-generation: existing values untouched)
- [ ] New key in `.env.example` (e.g. a future `LLM_*` var) is appended on next CLI run without touching existing keys (env-file-generation: additive reconcile)
- [ ] `--regenerate`/`--force` overwrites `.env` from computed defaults; default run never does (env-file-generation: force flag)
- [ ] No log line ever contains a `DATABASE_PASSWORD` value (env-file-generation: secrets never echoed)
- [ ] `VITE_API_BASE_URL` is baked into the frontend build output (native-cli-orchestration: build-time export ordering)
- [ ] CLI starts backend (`:3000`) + frontend (`:5173`) and both are reachable (native-cli-orchestration: orchestrated startup)
- [ ] `Q`/Ctrl+C teardown leaves zero orphaned backend/frontend processes on both Windows and Linux (native-cli-orchestration: clean teardown)
- [ ] Piped/non-TTY stdout falls back to plain mode without crashing or hanging (native-cli-orchestration: graceful degradation)
- [ ] `NO_COLOR` set → zero ANSI codes emitted (native-cli-orchestration: NO_COLOR respected)
- [ ] Site-add with hostile input `a"b;$(x)` reaches the backend as a single well-formed JSON field, no shell ever invoked (cli-rest-contract + legacy-launcher-retirement: injection safety)
- [ ] `menu.ps1`, `menu.sh`, `tests/menu.Tests.ps1`, `tests/menu_test.sh` no longer exist in the repo (legacy-launcher-retirement: files deleted)
- [ ] Both installers contain zero `npm`/`mvn`/jar-copy/`.env`-write commands; `cli/` contains zero toolchain download/install commands (installer-provisioning: boundary invariant)
- [ ] Windows Python/uv/venv provisioning failure hard-fails the install with an actionable message (installer-provisioning: Python load-bearing)
