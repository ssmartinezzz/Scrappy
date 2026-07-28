"""Build orchestration: frontend (`npm`) + backend (`mvn`), using only the
vendored toolchain under `_tools/` (design.md §4 — never downloads
anything). Owns the `VITE_API_BASE_URL` build-time export ordering hazard:
Vite bakes this value into the bundle at build time and never reads it from
`.env`, so it MUST be a real subprocess environment variable *before*
`npm run build` is invoked.
"""
from __future__ import annotations

import logging
import os
import platform
import shutil
import subprocess
from pathlib import Path
from typing import Callable, Optional, Sequence

from cli.core.config import Config
from cli.core.env_file import compute_defaults, generate_env, parse_env
from cli.core.errors import BuildError

logger = logging.getLogger(__name__)

JAR_NAME = "fashion-scraper-1.0.0.jar"

# Injectable subprocess runner — tests substitute a recorder to assert
# argument/env/order without spawning real processes.
Runner = Callable[..., object]


def _default_runner(cmd: Sequence[str], *, cwd: Path, env: dict) -> "subprocess.CompletedProcess":
    return subprocess.run(list(cmd), cwd=str(cwd), env=env, check=True)


def is_built(cfg: Config) -> bool:
    """True when both build artifacts already exist: the backend fat jar
    (`scraper/scraper.jar`) and the frontend build (`frontend/dist`). Used
    by the Start action to auto-build only when something is missing."""
    return (cfg.repo_root / "scraper" / "scraper.jar").is_file() and (
        cfg.repo_root / "frontend" / "dist"
    ).is_dir()


def _npm_cmd(cfg: Config) -> str:
    """The vendored `npm` if the installer provisioned it, else PATH `npm`.
    Node's Windows zip is flat (`npm.cmd`); the POSIX tarball nests binaries
    under `bin/`. The POSIX installer assumes a SYSTEM Node (documented gap),
    so `_tools/node` may not exist there — fall back to PATH rather than
    dying with 'No such file or directory: .../\\_tools/node/bin/npm'."""
    if platform.system() == "Windows":
        exe = "npm.cmd"
        vendored = cfg.tools.node / exe
    else:
        exe = "npm"
        vendored = cfg.tools.node / "bin" / "npm"
    return str(vendored) if vendored.is_file() else exe


def _mvn_cmd(cfg: Config) -> str:
    """The vendored `mvn` if present, else PATH `mvn` (same POSIX
    system-tools fallback as `_npm_cmd`)."""
    exe = "mvn.cmd" if platform.system() == "Windows" else "mvn"
    vendored = cfg.tools.maven / "bin" / exe
    return str(vendored) if vendored.is_file() else exe


def build_project(
    cfg: Config,
    example_path: Optional[Path] = None,
    env_path: Optional[Path] = None,
    frontend_example_path: Optional[Path] = None,
    frontend_env_path: Optional[Path] = None,
    force_env: bool = False,
    runner: Runner = _default_runner,
) -> None:
    """Run the full build sequence, in this exact order (design.md §4.1,
    corrected for verify.md CRITICAL-1):

    1. `generate_env(...)` for the ROOT `.env` — exists & reconciled first
    2. `generate_env(...)` for `frontend/.env` — SAME template-driven
       create-if-absent + additive-reconcile + never-overwrite contract,
       applied to `frontend/.env.example`. Required because this repo's real
       root `.env.example` intentionally does NOT declare `VITE_API_BASE_URL`
       as an active key (it lives only in `frontend/.env.example`, per D6) —
       relying on the root template alone silently drops it.
    3. parse the ROOT `.env` into a dict
    4. parse `frontend/.env` into a dict and merge it in (frontend values win
       on key collision, though none are expected in practice)
    5. `npm install` (frontend)
    6. `npm run build` (frontend) — `VITE_API_BASE_URL` MUST already be a
       real env var in this subprocess's environment
    7. `mvn clean package -DskipTests` (backend)
    8. copy `scraper/target/fashion-scraper-1.0.0.jar` -> `scraper/scraper.jar`

    Step 7 skips the test suite on purpose. This is an install-and-run
    flow: its contract is "produce a runnable jar", not "validate the
    codebase". Running the suite here makes a user's install depend on
    infrastructure they need not have — the DB tests want Docker or a
    local Postgres (`PostgresTestBase`), and Mockito/ByteBuddy refuses to
    mock under a JDK newer than it supports (a system JDK 24 fails every
    mock-based test while `javac` itself compiles fine). Test execution
    belongs to CI (`.github/workflows/backend-tests.yml`), which runs on a
    pinned toolchain. Test *sources* are still compiled, so a genuinely
    broken test build still surfaces here.
    """
    example_path = example_path or (cfg.repo_root / ".env.example")
    env_path = env_path or (cfg.repo_root / ".env")
    frontend_example_path = frontend_example_path or (cfg.repo_root / "frontend" / ".env.example")
    frontend_env_path = frontend_env_path or (cfg.repo_root / "frontend" / ".env")

    computed = compute_defaults(cfg)
    generate_env(example_path, env_path, computed, force=force_env)
    generate_env(frontend_example_path, frontend_env_path, computed, force=force_env)
    env_values = parse_env(env_path)
    frontend_env_values = parse_env(frontend_env_path)
    # VITE_API_BASE_URL (from frontend/.env) now a REAL process env var
    child_env = {**os.environ, **env_values, **frontend_env_values}

    frontend_dir = cfg.repo_root / "frontend"
    scraper_dir = cfg.repo_root / "scraper"

    try:
        runner([_npm_cmd(cfg), "install"], cwd=frontend_dir, env=child_env)
        runner([_npm_cmd(cfg), "run", "build"], cwd=frontend_dir, env=child_env)
        runner(
            [_mvn_cmd(cfg), "clean", "package", "-DskipTests"],
            cwd=scraper_dir,
            env=child_env,
        )
    except BuildError:
        raise
    except Exception as exc:  # noqa: BLE001 - normalized into a typed error
        raise BuildError(
            f"Build step failed: {exc}",
            action="Check the vendored toolchain under _tools/ and rerun the build.",
        ) from exc

    jar_src = scraper_dir / "target" / JAR_NAME
    jar_dst = scraper_dir / "scraper.jar"
    if not jar_src.is_file():
        raise BuildError(
            f"Expected build artifact not found: {jar_src}",
            action="Confirm `mvn clean package -DskipTests` completed and produced the fat jar.",
        )
    shutil.copyfile(jar_src, jar_dst)
    logger.info("Build complete: %s -> %s", jar_src, jar_dst)
