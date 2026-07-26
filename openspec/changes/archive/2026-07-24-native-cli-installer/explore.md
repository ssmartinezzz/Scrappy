# Explore: native-cli-installer

**Date:** 2026-07-24 (re-materialized from Engram obs, which disconnected)

## Question

Turn the two installers into dependency-only provisioners and build a CLI (with
good UX) that builds/runs the project and consumes the existing REST API.

## Stack decision — Python + Textual (`.py` source), NOT a compiled binary

An earlier explore ranked Python last because of a "bootstrapping paradox," but
that evaluated *Python-compiled-to-.exe*, not *Python-as-script*. The paradox does
not apply to a plain `.py`: **the installer already provisions the Python runtime
before any build step**, so the CLI needs zero new provisioning.

Evidence (`INSTALAR_Y_CORRER.bat`):
- `190-197` downloads/extracts Python 3.11 embeddable to `_tools/python`.
- `199-202` rewrites `python311._pth` (`#import site` → `import site`) to enable
  site-packages.
- `217-226` installs pip via `get-pip.py`.
- `207-210` prints "AVISO: Python no disponible. ML desactivado." and **continues**
  (Python optional *today*).
- `342-355` `TORCH_VER_BEFORE`/`TORCH_VER_AFTER` guard warns on PyTorch version
  drift after installing `open_clip_torch` into the shared site-packages — proof
  that adding packages to that shared env has caused drift before.

`Ejecutar_instalar.sh:44-56` already hard-fails when `java/mvn/python3/node` are
missing → `python3` is already mandatory on POSIX. Making Python load-bearing on
Windows only aligns the two.

## The seam (locked)

Installer keeps: `.bat` steps 1–4, 6, 7 (toolchain provisioning incl. the
~235-line GPU/torch/Marqo ML-deps block).
CLI takes: `npm install` + `npm run build`, `mvn clean package` + jar copy, `.env`
generation, backend+frontend orchestration, interactive menu.
**Invariant:** installer never builds the project; CLI never downloads a toolchain.

## `.env` current behavior (verified 2026-07-24)

Both installers overwrite the root `.env` unconditionally:
`INSTALAR_Y_CORRER.bat:609-617` (`( echo ... ) > "%ENV_FILE%"`),
`Ejecutar_instalar.sh:144-152` (`cat > "$ENV_FILE" <<EOF`). No existence guard.
Only `frontend/.env` is preserved (`Ejecutar_instalar.sh:109`, `if [ ! -f ]`).
→ Motivates the create-if-absent + additive-reconcile policy in the proposal.

## Retirement target

`menu.ps1` (556), `menu.sh` (506), `tests/menu.Tests.ps1`, `tests/menu_test.sh` —
duplicated two-language REST client + the only tests proving hostile input
(`a"b;$(x)`) never reaches a shell/JSON context. Deleting supersedes PR #108
(`interactive-cli-launcher`). The security property must be replaced, not dropped.

## Open questions handed to design

1. **Dependency isolation (blocker):** how to install Textual (`rich`,
   `markdown-it-py`) without polluting/drifting the shared ML site-packages.
   Verify embeddable venv/ensurepip support; verify torch/sklearn/transformers
   collision risk; verify the unconfirmed claim that transformers/huggingface_hub
   already pull rich/markdown-it-py.
2. **Graceful degradation:** NO_COLOR, TERM=dumb, non-TTY/piped, legacy cmd.exe.
3. **`VITE_API_BASE_URL` ordering:** must be a real env var before `vite build`.
4. **Size budget:** forecast ~1000–1300 added / ~1490 deleted; propose a 3-slice
   shape (headless core → Textual layer → installer shrink + retirement, the
   irreversible slice last).
