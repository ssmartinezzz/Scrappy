# Design: native-cli-installer

**Date:** 2026-07-24
**Status:** design (late-bound to two locked decisions — see ADR-002, ADR-003)
**Store:** openspec
**OS matrix:** Windows + Linux only (macOS out of scope). No binary/distribution/signing.

---

## 1. Overview

This change splits the two monolithic installers along one hard seam and replaces the
duplicated `menu.ps1`/`menu.sh` REST clients with a single Python CLI (Textual TUI over
a testable headless core).

> **Invariant (locked):** the installer never builds the project; the CLI never
> downloads or installs a toolchain component under `_tools/`.

Two decisions are already locked and this design builds around them, not over them:

- **LD-1 — Python dep isolation via `uv`** (ADR-002): the CLI's Python deps (Textual →
  `rich`, `markdown-it-py`) live in a dedicated `_tools/cli-venv`, built by `uv` on a
  **uv-managed standalone CPython** (pinned 3.11.x), NOT on the ML embeddable. The CLI never
  imports ML libraries, so it shares nothing with the embeddable — this makes the venv
  bootstrap deterministic (no `python311._pth` involvement) and eliminates the Windows
  smoke-test risk by construction. The ~235-line ML-deps block in `INSTALAR_Y_CORRER.bat`
  (lines ~330–360, the `TORCH_VER_BEFORE`/`_AFTER` guard) and the embeddable itself are
  **untouched**.
- **LD-2 — template-driven `.env` generation** (ADR-003): `.env` keys come from
  `.env.example` (the schema), not a hardcoded 7-key echo block. Known keys get computed
  values; every other key passes through with its example default. This composes cleanly
  with a parallel change adding optional `LLM_*` vars.

---

## 2. Architecture: headless core vs. TUI presenter

The single most important structural decision (ADR-001) is that the CLI is **not** a
Textual app with logic inside widgets. It is a **headless core library** that a **thin
Textual presenter** drives, and that a **plain-text fallback runner** drives identically.

```
cli/
├── __main__.py            # entry point: arg parse, capability detection, mode routing
├── core/                  # HEADLESS — zero terminal/Textual imports, pytest-friendly
│   ├── config.py          #   repo-root discovery, _tools/ path resolution, port config
│   ├── env_file.py        #   template-driven .env gen (LD-2): read .env.example → reconcile
│   ├── builder.py         #   npm install/build, mvn package + jar copy, VITE ordering
│   ├── rest.py            #   REST client of EXISTING API only; json.dumps site payloads
│   ├── processes.py       #   backend + frontend subprocess lifecycle + teardown
│   └── errors.py          #   typed failures with actionable messages
├── tui/                   # PRESENTER — Textual App; imports core, holds NO business logic
│   ├── app.py             #   Textual App, screens, key bindings (Q / Ctrl+C)
│   └── widgets.py         #   status panel, menu, log tail
└── plain/
    └── runner.py          # FALLBACK — non-interactive text driver over the SAME core
tests/cli/                 # pytest: core unit tests + Textual Pilot tests + injection test
```

**Rule enforced by structure:** `core/` MUST NOT import `textual`, `rich`, or touch
`sys.stdout` for interactive rendering. It returns data and raises typed errors. Both
`tui/` and `plain/` are presenters over the same core. This is what makes the core
unit-testable without a terminal (spec: Graceful TUI Degradation, and the pytest strategy).

### 2.1 Mode routing (entry point)

`__main__.py` runs capability detection **once**, before constructing any presenter:

```
detect_interactive() -> bool:
    if "--plain" in argv: return False
    if os.environ.get("NO_COLOR"): return False
    if os.environ.get("TERM") == "dumb": return False
    if not sys.stdout.isatty() or not sys.stdin.isatty(): return False   # piped/redirected
    if is_legacy_cmd_without_ansi(): return False                        # Windows ConHost pre-ANSI
    return True
```

- `True`  → `tui.app.run(core_context)`
- `False` → `plain.runner.run(core_context)`

Both receive the identical `core_context` (resolved paths, ports, REST client). Neither
presenter re-implements orchestration. Graceful degradation is therefore a *routing*
concern, not scattered `if color:` branches — satisfying "Never crash" by construction.

---

## 3. `.env` generation (template-driven — LD-2)

`core/env_file.py` implements the spec's create-if-absent + additive-reconcile +
never-overwrite + `--regenerate` policy, sourced from `.env.example` as schema.

> **Correction (2026-07-26, closes verify.md CRITICAL-1):** this repo's real
> root `.env.example` intentionally does NOT declare `VITE_API_BASE_URL` as
> an active key — it lives only in a separate `frontend/.env.example` (the
> `decouple-services-postgres` D6 split: Vite reads `frontend/.env*`
> natively, never the root `.env`). The original design/implementation
> assumed a single-file schema and silently dropped `VITE_API_BASE_URL`
> against real project files. The fix (§4.1, "Frontend Env Generation
> Mirrors the Root Contract" in specs/env-file-generation) is to call the
> SAME `generate_env()` algorithm below a second time against
> `frontend/.env.example` → `frontend/.env`, using the same `computed` dict
> (`compute_defaults()` already keys `VITE_API_BASE_URL`; `_resolve` only
> applies a computed value to a key present in that template's own schema,
> so no change to `compute_defaults()` was needed).

### 3.1 Algorithm

```
generate_env(example_path, env_path, computed: dict, force: bool):
    schema = parse_keys(example_path)          # ordered [(key, example_default)], comments preserved
    computed = { DATABASE_URL, DATABASE_USERNAME, SCRAPER_MODELS_ROOT,
                 APP_CORS_ALLOWED_ORIGINS, VITE_API_BASE_URL, APP_OPEN_URL,
                 DATABASE_PASSWORD="" }         # the keys the CLI knows how to compute

    if force or not env_path.exists():
        for key, example_default in schema:
            value = computed.get(key, example_default)   # computed wins; else pass through
            write key=value
        return

    existing = parse(env_path)                  # keep every existing value verbatim
    for key, example_default in schema:
        if key not in existing:
            append key = computed.get(key, example_default)   # additive reconcile only
    # existing keys: never touched
```

### 3.2 Composition property (must be stated — LD-2)

Because generation reads keys from `.env.example` and only substitutes the finite set of
**computed** keys, any *new* key added to `.env.example` — including the parallel change's
optional `LLM_*` vars (Ollama-pointing defaults) — flows through automatically:

- **No code change here** when `.env.example` grows.
- New keys are appended with their example default, never overwritten.
- They are **optional by construction**: this change does NOT add them to the backend's
  fail-fast `RequiredEnvVarsGuard`, and does NOT add an "Ask Agent" menu item (that lives
  in `MlStatusPanel.jsx`, reachable via the CLI's existing "open dashboard"). The CLI stays
  agnostic to their meaning.

### 3.3 Secrets

`DATABASE_PASSWORD` (and any credential-bearing value) is written to `.env` but never
echoed to stdout/log. The generator logs key *names* touched, never values for the secret
set. Matches spec "Secrets Never Echoed".

---

## 4. Build orchestration & the VITE ordering hazard

`core/builder.py` owns what the installers surrender: `npm install` + `npm run build`,
`mvn clean package` + jar copy. It uses only vendored toolchain paths from `core/config.py`
(`_tools/jdk21`, `_tools/maven`, `_tools/node`) — never downloads anything (invariant).

### 4.1 Build sequence (VITE_API_BASE_URL is build-time, not `.env`-read)

**Corrected 2026-07-26 (closes verify.md CRITICAL-1)** — two templates are
generated, not one, because `VITE_API_BASE_URL` only lives in
`frontend/.env.example` against this repo's real files (§3 correction note):

```
1. generate_env(example_path, env_path, computed, force)                    # root .env
2. generate_env(frontend_example_path, frontend_env_path, computed, force)  # frontend/.env
3. env = parse(.env)                          # root values
4. frontend_env = parse(frontend/.env)        # frontend values (VITE_API_BASE_URL lives here)
5. child_env = os.environ | env | frontend_env   # VITE_API_BASE_URL now a REAL process env var
6. run("npm install",  cwd=frontend, env=child_env)
7. run("npm run build", cwd=frontend, env=child_env)   # ← Vite BAKES VITE_API_BASE_URL here
8. run("mvn clean package", cwd=scraper, env=child_env)
9. copy scraper/target/fashion-scraper-1.0.0.jar -> scraper/scraper.jar
```

The critical line is steps 2/4→7: `VITE_API_BASE_URL` MUST be in the subprocess environment
*before* `npm run build`, because Vite reads it as a process env var at build time, not from
`.env`. This preserves the exact hazard the old scripts documented at
`INSTALAR_Y_CORRER.bat:441` / `Ejecutar_instalar.sh:112`. Spec: "VITE_API_BASE_URL
Build-Time Export Ordering" (native-cli-orchestration) + "Frontend Env Generation Mirrors
the Root Contract" (env-file-generation).

---

## 5. Process orchestration & teardown

`core/processes.py` reproduces the lifecycle contract the deleted `menu.ps1`/`menu.sh` held.

### 5.1 Contract (ported from menu.ps1 D4/D5)

- **Backend:** `java -Xmx768m -Dfile.encoding=UTF-8 -jar scraper/scraper.jar` on `:3000`,
  cwd = `scraper/`, stderr redirected to `scraper/logs/backend-launcher.err.log` so a boot
  failure is visible.
- **Frontend:** `npm run preview -- --port 5173 --strictPort` on `:5173`, cwd = `frontend/`.
  `--strictPort` makes a port clash fail loudly rather than silently drifting to 4173.
- **Teardown of BOTH** on normal quit, `Q`, `Ctrl+C`, or `SIGTERM`: kill the whole process
  tree (Vite preview spawns a child node), tolerating already-dead PIDs. No orphans.

### 5.2 Cross-platform teardown

- Spawn with `subprocess.Popen`, capturing PIDs.
- Windows: `CREATE_NEW_PROCESS_GROUP`; teardown via `taskkill /PID <pid> /T /F` (tree kill).
- Linux: `start_new_session=True` (new process group); teardown via
  `os.killpg(os.getpgid(pid), SIGTERM)` then `SIGKILL` escalation on timeout.
- A single `Cleanup()` runs from: TUI quit binding, `plain` runner exit, and a
  `signal`/`atexit` handler — all funnel to the same `processes.shutdown_all()`.

### 5.3 CRITICAL preserved gotcha — empty `DATABASE_PASSWORD` on Windows

`menu.ps1:197-204` documents that Windows cannot hold an empty env var (both `set VAR=` and
`.NET SetEnvironmentVariable('')` DELETE it), so an empty trust-auth password would read as
"missing" and trip `RequiredEnvVarsGuard`. The fix: pass it as a **JVM system property** the
backend's `containsProperty()` still sees as present:

```
java ... -DDATABASE_PASSWORD=<value-even-if-empty> ... -jar scraper.jar
```

`core/processes.py` MUST replicate this: when launching the backend, always append
`-DDATABASE_PASSWORD=<value>` (empty string included), plus `-DPYTHON_EXE` / `-DPYTHON_DIR`
when known. This is a load-bearing detail; dropping it silently breaks local trust-auth
boot on Windows.

---

## 6. REST client (existing API only)

`core/rest.py` is a pure client of the **existing** endpoints — no new backend endpoints:

| Menu action    | Call |
|----------------|------|
| scrape         | `POST /api/scrape?precioMin&precioMax&sitios&forceRetrain` |
| retrain        | `POST /api/ml/entrenar` |
| status (poll)  | `GET /api/status` |
| site list      | `GET /api/sitios` |
| site add       | `POST /api/sitios` (JSON body) |
| site delete    | `DELETE /api/sitios` |
| open dashboard | opens `APP_OPEN_URL` in browser (no API call) |

Transport: Python stdlib `urllib.request` (zero extra deps beyond Textual's tree) or
`http.client`. No `requests` dependency added.

### 6.1 Injection-safe site JSON (security property replacing the deleted menu tests)

The site-add payload MUST be built with `json.dumps`, never string concatenation:

```python
body = json.dumps({"nombre": name, "url": url, "rubro": rubro}).encode("utf-8")
```

Hostile input like `a"b;$(x)` becomes a single, properly-escaped JSON string field. It never
alters JSON structure and never reaches a shell (there is no shell in this path — `urllib`
does not spawn a shell). This is the structural replacement for `menu.Tests.ps1` /
`menu_test.sh`. Spec: "Structurally-Safe Site JSON Serialization" +
"Injection-Safety Test Replacement".

---

## 7. Dependency isolation via uv (LD-1)

### 7.1 Provisioning (installer side — the seam)

The installer provisions the `uv` static binary into `_tools/uv` (consistent with
"installer provisions toolchain"). It then provisions a **uv-managed standalone CPython**
and builds a dedicated CLI venv on it — fully separate from both the ML embeddable and the
shared ML site-packages:

```
_tools/uv/uv python install 3.11.<pin>                              # managed CPython, one-time download
_tools/uv/uv venv --managed-python --python 3.11 _tools/cli-venv    # deterministic; no embeddable involved
_tools/uv/uv pip install --python _tools/cli-venv/... textual \
    --offline    # once wheels are cached; --offline forces local-cache-only
```

Verified from uv docs: `uv python install <version>` provisions a python-build-standalone
CPython; `--managed-python` (also `python-preference = only-managed` / `UV_MANAGED_PYTHON`)
forces uv to use ONLY its managed interpreter, never the system/embeddable python; `uv venv`
builds the venv itself in Rust; `--offline` forces cache-only for determinism/network-free
runs. Same commands on Windows and Linux → one pinned CLI Python everywhere, reproducible.

### 7.2 Why a managed CPython, not the embeddable base (de-risked)

The CLI is an orchestrator + REST client; it **never imports ML libraries**, so it has no
reason to share the embeddable. Basing the CLI venv on the embeddable was the ONLY source of
the Windows bootstrap risk (the `python311._pth` sys.path freeze can leak into a venv and
break it). Using a uv-managed CPython removes the embeddable from the equation entirely, so
that failure mode **cannot occur by construction** — no smoke-test gamble, deterministic on
both OSes. Cost: ~35MB of a second, CLI-dedicated Python on disk + one install-time download
(same class as the embeddable/Node/Maven downloads the installer already does). The ML
embeddable stays exactly as-is.

### 7.3 Why uv, not pip-into-shared-env (rejected — see ADR-002)

pip's only options for isolating the CLI deps are: (a) install into the shared ML env →
torch/sklearn/transformers drift (the exact risk the `.bat` already guards at lines
342–355), or (b) `pip install --target` + `sys.path` hacks → imperfect isolation. `uv` gives
a real isolated venv on a clean managed interpreter. The ML-deps block stays untouched; uv is
introduced ONLY for the CLI venv. On Linux the same managed-python path applies — the CLI no
longer depends on whatever `python3` the distro ships, so it is reproducible there too.

---

## 8. Testing strategy (introduces pytest for the CLI)

The project has **no Python test runner today** (JUnit only). This change introduces
`pytest` scoped to the CLI, plugged in as a separate CI job.

| Target | How |
|--------|-----|
| `core/env_file.py` | pytest with tmp_path fixtures: create-if-absent, no-overwrite of existing key, additive reconcile of a new `.env.example` key, `--regenerate` overwrite, secret-not-logged (capsys assertion). |
| `core/builder.py`  | pytest: assert `VITE_API_BASE_URL` present in the env dict passed to the `npm run build` subprocess (monkeypatch subprocess runner, inspect env). |
| `core/rest.py`     | pytest: **injection-safety test** — `json.dumps({"nombre": 'a"b;$(x)', ...})` parses back to exactly that string in one field; assert no `shell=True` / no shell subprocess ever constructed. This is the replacement security artifact. |
| `core/processes.py`| pytest: assert `-DDATABASE_PASSWORD=` appended even when empty (Windows contract); teardown funnel calls tree-kill for each tracked PID (mock). |
| `tui/app.py`       | Textual `App.run_test()` / `Pilot` (per spec): drive key presses, assert menu → core call wiring, assert `Q` triggers teardown. |
| degradation        | pytest: `detect_interactive()` returns False under NO_COLOR, TERM=dumb, non-tty stdin/stdout; assert `plain.runner` selected. |

**CI wiring:** a new job runs `_tools/cli-venv` python `-m pytest tests/cli` (needs the CLI
venv + pytest installed into it). It sits alongside the existing Maven job; it does not gate
the ML/Java build. Runs on Linux CI where the CLI venv builds cleanly.

---

## 9. Installer-shrink seam (what stays vs. moves)

| Concern | STAYS in `.bat`/`.sh` (provision) | MOVES to CLI (build/run) |
|---------|-----------------------------------|--------------------------|
| JDK / Maven / Node download+extract | ✅ | — |
| Python embeddable + pip + site-packages | ✅ | — |
| ML deps (torch/open_clip/Marqo, TORCH_VER guard) | ✅ **untouched** | — |
| Portable Postgres provision + start | ✅ | — |
| **uv binary + `_tools/cli-venv` + `uv pip install textual`** | ✅ **new** | — |
| `npm install` + `npm run build` | — | ✅ |
| `mvn clean package` + jar copy | — | ✅ |
| `.env` generation | — | ✅ (template-driven) |
| backend + frontend orchestration + menu | — | ✅ |
| jq/gum vendoring (menu.sh deps) | ❌ **removed** (menu.sh retired) | — |

**Installer tail change:** the old tail generated `.env` (`.bat:609-617`, `.sh:144-152`) and
invoked `menu.ps1`/`menu.sh` (`.bat:667`). It now invokes the CLI entry point via the
vendored interpreter, e.g. `_tools/python/python.exe cli\__main__.py` (Windows) /
`python3 cli/__main__.py` (Linux). The `.env` echo blocks and the jq/gum provisioning step
(`.sh:156-...`) are deleted.

**Invariant restated:** after the shrink, the installer contains zero `npm`/`mvn`/jar-copy/
`.env`-write commands; the CLI contains zero download/extract/toolchain-install commands.

---

## 10. Architecture Decision Records

### ADR-001 — Python-as-script + Textual over a compiled binary
**Decision:** Ship the CLI as `.py` source run on the already-vendored Python, structured as
a headless core + Textual presenter + plain fallback.
**Rationale:** The installer already provisions Python before any build step, so a `.py` CLI
needs zero new provisioning — the "bootstrapping paradox" only applied to Python-compiled-
to-`.exe`. Textual gives the requested "buenas animaciones/efectos" UX. The core/presenter
split makes orchestration unit-testable without a terminal and makes graceful degradation a
routing concern.
**Rejected:** (a) Compiled binary (PyInstaller/Go/Rust) — adds a build+distribution+signing
pipeline that is explicitly out of scope, and reintroduces provisioning. (b) Keep two-
language `menu.ps1`/`menu.sh` — the duplication is the problem being solved.

### ADR-002 — uv-isolated CLI venv on a uv-managed CPython over pip-into-shared-env
**Decision:** Provision the `uv` binary in `_tools/uv`; provision a uv-managed standalone
CPython (pinned 3.11.x) and build a dedicated `_tools/cli-venv` on it (`--managed-python`),
NOT on the ML embeddable; `uv pip install textual` into that venv. ML-deps block and the
embeddable untouched.
**Rationale:** The CLI never imports ML libraries, so it needs nothing from the embeddable.
Building the venv on a clean managed CPython makes the bootstrap deterministic and removes the
embeddable (and its `python311._pth` sys.path freeze) from the equation, so the Windows venv
failure mode cannot occur by construction. `uv venv` builds the venv in Rust; real isolation
with no risk to the guarded torch/sklearn/transformers versions.
**Rejected:** (a) pip into shared ML site-packages → version drift (already guarded against
at `.bat:342-355`). (b) `pip install --target` + `sys.path` hacks → imperfect isolation.
(c) Reuse the embeddable as the venv base → its `._pth` freeze can leak into the venv and
break bootstrap; rejected in favor of the managed CPython (the ~35MB extra Python is worth
eliminating the risk).
**Cost accepted:** ~35MB second CLI-dedicated Python + one install-time download, same class
as the embeddable/Node/Maven downloads the installer already performs.

### ADR-003 — Template-driven `.env` generation over hardcoded keys
**Decision:** Generate `.env` from `.env.example` as schema; substitute computed values for
the known key set, pass every other key through with its example default; create-if-absent +
additive-reconcile + never-overwrite + `--regenerate`.
**Rationale:** The current echo blocks (`.bat:609-617`, `.sh:144-152`) hardcode 7 keys and
overwrite unconditionally, destroying hand-edited secrets. Template-driven + additive makes
new optional keys (e.g. the parallel `LLM_*` change) flow through with no code change here,
and never become required — the CLI stays agnostic and does not touch `RequiredEnvVarsGuard`.
**Rejected:** Keep the imperative echo block — couples the key set to two shell dialects and
loses user edits every run.

### ADR-004 — Retire menu.ps1/menu.sh for a single Python client
**Decision:** Delete `menu.ps1`, `menu.sh`, `tests/menu.Tests.ps1`, `tests/menu_test.sh`;
the CLI's REST client supersedes them. Sequence this deletion LAST.
**Rationale:** The two launchers are a byte-for-byte-synced two-language REST client kept
aligned only by discipline. One Python client removes the duplication. The deleted menu
tests' security property (hostile input can't reach shell/JSON) is replaced by the pytest
injection test in `core/rest.py`, not dropped.
**Rejected:** Keep the launchers as a fallback — perpetuates the duplication and the
graceful-degradation fallback already covers non-TUI environments.

---

## 11. Implementation shape (3 slices — irreversible slice LAST)

Feeds `sdd-tasks`. Ordered so each slice lands independently reviewable and the destructive
step is last (rollback = revert the single PR).

### Slice 1 — Headless core + pytest (reversible, additive)
- `cli/core/{config,env_file,builder,rest,processes,errors}.py`.
- Introduce `pytest` + `tests/cli/` with: env-gen policy tests, VITE-ordering test,
  injection-safety test, empty-`DATABASE_PASSWORD` test, teardown-funnel test.
- New CI job runs the CLI pytest suite.
- Nothing deleted; installers still own build/run. Fully additive.

### Slice 2 — Textual TUI + graceful degradation + TUI tests (reversible, additive)
- `cli/tui/{app,widgets}.py` presenter, `cli/plain/runner.py` fallback,
  `cli/__main__.py` capability detection + mode routing.
- Textual `App.run_test()`/`Pilot` tests; degradation-routing tests.
- CLI is now runnable standalone against a pre-built project. Still nothing deleted.

### Slice 3 — Installer shrink + uv provisioning + menu.* retirement (IRREVERSIBLE — last)
- Add `uv` provisioning + uv-managed CPython (`uv python install 3.11.<pin>`) +
  `_tools/cli-venv` creation (`--managed-python`) to both installers. Deterministic — no
  embeddable smoke-test gate.
- Verify `import textual` from `_tools/cli-venv` on both OSes as an install acceptance check.
- Shrink `.bat`/`.sh`: remove `.env` echo blocks, npm/mvn/jar steps (none existed to move on
  build — they're removed from the installer's responsibility), jq/gum provisioning; retail
  tail to invoke the CLI.
- Delete `menu.ps1`, `menu.sh`, `tests/menu.Tests.ps1`, `tests/menu_test.sh`.
- Update `docs/` (ARCHITECTURE, any install/launcher references) and `CLAUDE.md` seam notes.

---

## 12. Risks & assumptions

1. **[RESOLVED] CLI venv bootstrap on Windows** — de-risked by building the CLI venv on a
   uv-managed standalone CPython instead of the embeddable, so the `python311._pth` freeze
   never participates. No smoke-test gate; deterministic on both OSes. Residual: the
   install-time `uv python install` download must succeed (network), handled by the
   load-bearing hard-fail (risk 2).
2. **Python becomes load-bearing on Windows** — a Python/uv/venv failure MUST hard-fail the
   install with an actionable message (spec: Python Load-Bearing). Accepted knowingly; aligns
   with the existing POSIX hard-fail.
3. **Empty `DATABASE_PASSWORD` on Windows** — must be passed as `-DDATABASE_PASSWORD=` JVM
   property or local trust-auth boot breaks. Covered by a dedicated test; regression-prone if
   refactored away.
4. **CI can't run the Windows-specific paths** — the injection/env/ordering tests run on
   Linux CI; the Windows `uv venv` smoke test is manual/sandbox during apply.
5. **Parallel `LLM_*` change** — this design assumes those vars are optional with local
   defaults and stay out of `RequiredEnvVarsGuard`. If that change makes any `LLM_*` var
   required, the template-driven generator still emits it, but the "never required here"
   composition property would need revisiting jointly.
