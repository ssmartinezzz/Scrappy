"""Teardown of the FlareSolverr container `Ejecutar_instalar.sh` starts for
Cloudflare-protected sites (fullh4rd). Quitting the CLI stops it, so the next
installer run brings it up fresh.

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


class FlareSolverrService:
    """Injectable face of `stop_flaresolverr`, so presenters take a fake."""

    def __init__(self, runner: Optional[Runner] = None) -> None:
        self._runner = runner

    def stop_flaresolverr(self, cfg: Config) -> None:
        stop_flaresolverr(cfg, runner=self._runner)
