# Proposal: native-cli-installer

**Date:** 2026-07-24
**Status:** proposed

---

## Problem

Today `INSTALAR_Y_CORRER.bat` (672 lines) and `Ejecutar_instalar.sh` (237 lines) do
**two jobs at once**: they provision the toolchain (JDK, Maven, Node, Python
embeddable, portable Postgres, ML deps) **and** build the project (npm build, mvn
package + jar copy) **and** generate the runtime `.env`. All of it is imperative
batch/shell, hard to test, and the interactive layer added by
`interactive-cli-launcher` (PR #108: `menu.ps1` / `menu.sh` + their Pester/bash
tests) is a second REST client duplicated across two languages, byte-for-byte, to
stay in sync.

Concrete pain points:

1. **No separation of concerns.** "Install dependencies" and "build + run the
   project" are tangled in the same monolithic scripts. A change to the build flow
   forces editing fragile batch/shell.
2. **Destructive `.env` generation.** Both installers **overwrite the root `.env`
   unconditionally** every run (`> "%ENV_FILE%"` at `INSTALAR_Y_CORRER.bat:617`;
   `cat > "$ENV_FILE"` at `Ejecutar_instalar.sh:144`). Any hand-edited secret
   (real `DATABASE_PASSWORD`, external `DATABASE_URL`, custom CORS origin) is lost
   on the next run. Only `frontend/.env` is guarded (`Ejecutar_instalar.sh:109`).
3. **Duplicated interactive client.** `menu.ps1` and `menu.sh` carry identical
   "API CONTRACT" comment blocks and reimplement the same REST calls in two
   languages — the only thing keeping them aligned is discipline.
4. **Weak UX.** The launcher is a plain foreground process / text menu. The user
   asked for "buenas animaciones, efectos" — a real TUI.

## Proposed Solution

Split the responsibility along one hard seam:

> **Invariant:** the installer never builds the project; the CLI never downloads a
> toolchain.

- **Installers become dependency-provisioners only.** `.bat` steps 1–4, 6, 7
  (toolchain: JDK, Maven, Node, Python embeddable + pip + site-packages, portable
  Postgres, the ~235-line GPU/torch/Marqo ML-deps block) **stay** in the
  installers. They stop building the project and stop generating `.env`.
- **A single Python CLI (Textual) owns build + run + interaction.** Shipped as
  `.py` source, run on the already-vendored Python 3.11 embeddable (Windows) /
  system `python3` (POSIX). It performs: `npm install` + `npm run build`,
  `mvn clean package` + jar copy, `.env` generation (idempotent, see below),
  backend+frontend orchestration, and the interactive menu (scrape / retrain /
  status / site CRUD / open dashboard) as a REST client of the **existing** API —
  **no new backend endpoints**.
- **Retire the duplicated launchers.** Delete `menu.ps1`, `menu.sh`,
  `tests/menu.Tests.ps1`, `tests/menu_test.sh` (intentionally supersedes
  `interactive-cli-launcher` / PR #108). One Python client replaces two.

### `.env` generation policy (decided)

Move `.env` generation from the installers into the CLI with a
non-destructive contract:

1. **Create-if-absent, never overwrite.** No `.env` → generate from computed
   defaults (installer-provisioned PG port, paths). `.env` exists → touch no
   existing value.
2. **Additive reconcile of missing keys.** If `.env.example` gains a new key
   absent from the user's `.env`, the CLI appends it with its default. It never
   modifies an existing key.
3. **`.env` stays gitignored; `.env.example` is the versioned schema (no
   secrets).** Secrets live only in `.env`; the generator never echoes secret
   values to stdout/log.
4. **Explicit escape hatch:** a `--regenerate` / `--force` flag for the
   "blow it away and rebuild" case. Safe by default, with an opt-out.

### Ordering hazard preserved

`VITE_API_BASE_URL` is a **build-time bake** (Vite reads it as a real env var, not
from `.env`, at `vite build` time). The CLI MUST export it **before** running the
frontend build — the same hazard the current scripts document at
`INSTALAR_Y_CORRER.bat:441` and `Ejecutar_instalar.sh:112`.

## Scope

**In scope:**
- New Python CLI (Textual) under a new dir (e.g. `cli/`), shipped as `.py`.
- Shrink `INSTALAR_Y_CORRER.bat` and `Ejecutar_instalar.sh` to
  dependency-provisioning only; their tail invokes the CLI.
- Delete `menu.ps1`, `menu.sh`, `tests/menu.Tests.ps1`, `tests/menu_test.sh`.
- `.env` generation logic (idempotent) inside the CLI.
- A test that preserves the **security property** the deleted menu tests
  guaranteed: hostile input (`a"b;$(x)`) never reaches a shell/JSON injection
  context (structurally safe via `json.dumps`, but the property still needs a
  test).

**Out of scope:**
- No compiled binary, no distribution pipeline (no GitHub Releases, cross-build,
  checksums, version pinning, code signing).
- **macOS** (OS matrix is Windows + Linux only).
- No new backend REST endpoints — the CLI consumes the existing API
  (`/api/status`, `/api/scrape`, `/api/ml/entrenar`, `/api/sitios` GET/POST/DELETE).
- Docker install path (PR #109) is untouched.

## Key tradeoffs / risks (for design)

1. **Python becomes load-bearing on Windows.** Today Python is optional
   (`INSTALAR_Y_CORRER.bat:207-210` prints "ML desactivado" and continues). Once
   the CLI owns build+run, a Python failure MUST fail the install with a clear
   message. Accepted knowingly — this aligns Windows with the POSIX hard-fail that
   already exists (`Ejecutar_instalar.sh:44-56`).
2. **Dependency isolation (#1 design blocker — RESOLVED via uv).** Textual pulls
   `rich` / `markdown-it-py`. Installing them into the **shared** ML site-packages
   risks version drift — the `.bat` already guards against exactly this with the
   `TORCH_VER_BEFORE`/`TORCH_VER_AFTER` check (`INSTALAR_Y_CORRER.bat:342-355`).
   **Decision:** the CLI's deps live in a dedicated `_tools/cli-venv` built by `uv`
   on a **uv-managed standalone CPython** (pinned 3.11.x), NOT on the ML embeddable.
   Because the CLI never imports ML libraries and the venv is built on a clean
   managed interpreter, isolation is total and the embeddable's `python311._pth`
   freeze never participates — the Windows venv-bootstrap failure mode cannot occur
   by construction (no smoke-test gamble). Cost: ~35MB CLI-dedicated Python + one
   install-time download, same class as the embeddable/Node/Maven downloads the
   installer already does. The ~235-line ML-deps block stays untouched. This also
   moots the unverified transformers→rich/markdown-it-py claim (isolation makes it
   irrelevant).
3. **Graceful degradation.** The TUI must degrade under NO_COLOR, TERM=dumb,
   non-TTY/piped output, and legacy cmd.exe.
4. **Security assurance replacement.** Deleting the menu tests drops the only
   artifact proving hostile input can't reach a shell/JSON context. Must be
   replaced, not just dropped.

## Rollback plan

The change is a single PR. Rollback = revert the PR: restores the monolithic
installers and the `menu.*` launchers. The irreversible slice (deleting `menu.*`
and shrinking installers) is sequenced **last** so earlier slices land
independently reviewable.
