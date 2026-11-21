"""Lifecycle of the FlareSolverr container `Ejecutar_instalar.sh` creates for
Cloudflare-protected sites (fullh4rd). Quitting the CLI stops it, and `start`
brings it back: without that, relaunching `python -m cli` without the
installer scraped fullh4rd against a dead solver.

The installer drops `_tools/flaresolverr.owned` only when it started the
container itself; one the user already had answering on 8191 is not ours to
stop. The marker also keeps the test suite, which quits the CLI, from reaching
a real container — same guard as `lan_proxy.stop_proxy`.
"""
from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Callable, Optional, Sequence

from cli.core.config import Config

CONTAINER = "fashion-scraper-flaresolverr"

Runner = Callable[..., object]


def owned_marker(cfg: Config) -> Path:
    return cfg.repo_root / "_tools" / "flaresolverr.owned"


def _run_docker(argv: Sequence[str], **kwargs) -> str:
    return subprocess.run(
        list(argv), check=True, capture_output=True, text=True, **kwargs
    ).stdout


def stop_flaresolverr(cfg: Config, *, runner: Optional[Runner] = None) -> None:
    """Best-effort: quitting must never fail because Docker is gone."""
    marker = owned_marker(cfg)
    if not marker.is_file():
        return
    runner = runner or _run_docker
    try:
        runner(["docker", "stop", CONTAINER])
    except Exception:
        pass
    finally:
        marker.unlink(missing_ok=True)


def ensure_flaresolverr(cfg: Config, *, runner: Optional[Runner] = None) -> Optional[str]:
    """Start the installer's container when it exists but is stopped.

    Returns a line for the console, or None when there was nothing to do:
    no Docker, no container (the user runs their own, or never installed
    one), or it is already running. Never raises: `start` must not fail
    because Docker is gone.
    """
    runner = runner or _run_docker
    try:
        running = str(runner(["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER])).strip()
    except Exception:
        return None
    if running != "false":
        return None
    try:
        runner(["docker", "start", CONTAINER])
    except Exception as exc:
        return f"FlareSolverr no arrancó ({exc}): fullh4rd va a fallar por Cloudflare"
    marker = owned_marker(cfg)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.touch()
    return "FlareSolverr arrancado en localhost:8191 (Cloudflare de fullh4rd)"


class FlareSolverrService:
    """Injectable face of the module, so presenters take a fake."""

    def __init__(self, runner: Optional[Runner] = None) -> None:
        self._runner = runner

    def stop_flaresolverr(self, cfg: Config) -> None:
        stop_flaresolverr(cfg, runner=self._runner)

    def ensure_flaresolverr(self, cfg: Config) -> Optional[str]:
        return ensure_flaresolverr(cfg, runner=self._runner)
