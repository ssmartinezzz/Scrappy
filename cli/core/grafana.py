"""Grafana's tie to the CLI. `Ejecutar_instalar.sh` brings it up through
`scripts/observability.sh up` for users who opted in (the first manual `up`
creates `observability/grafana.env`); here the CLI reads how to reach it and
stops it on quit.

The installer drops `_tools/grafana.owned` only when it started (or found
running) the container itself. The marker also keeps the test suite, which
quits the CLI, from reaching a real Docker — same guard as `flaresolverr`.
Credentials are returned to the caller to be shown on screen; nothing here
writes them anywhere.
"""
from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Sequence

from cli.core.config import Config

Runner = Callable[..., object]

DEFAULT_PORT = "3001"
# Hosts that mean "this machine": blank and 0.0.0.0 listen everywhere, loopback included.
_THIS_MACHINE = ("", "0.0.0.0", "127.0.0.1", "localhost")


@dataclass(frozen=True)
class GrafanaInfo:
    url: Optional[str]
    user: str
    password: str
    bind_host: str = ""


def owned_marker(cfg: Config) -> Path:
    return cfg.repo_root / "_tools" / "grafana.owned"


def _env_path(cfg: Config) -> Path:
    return cfg.repo_root / "observability" / "grafana.env"


def _parse(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def read_info(cfg: Config) -> Optional[GrafanaInfo]:
    """None when the user never opted in (no env file)."""
    path = _env_path(cfg)
    if not path.is_file():
        return None
    env = _parse(path)
    host = env.get("GRAFANA_BIND_HOST", "")
    port = env.get("GRAFANA_PORT") or DEFAULT_PORT
    # Grafana is plain HTTP: the CLI only hands out a URL to this machine. Whoever
    # bound it to a LAN address chose that address and is pointed back to it.
    return GrafanaInfo(
        url=f"http://localhost:{port}" if host in _THIS_MACHINE else None,
        user="admin",
        password=env.get("GRAFANA_ADMIN_PASSWORD", ""),
        bind_host=host,
    )


def describe(info: Optional[GrafanaInfo]) -> str:
    """The one line both presenters print. Screen only: callers must not log it."""
    if info is None:
        return "Grafana no está activado: corré scripts/observability.sh up una vez"
    donde = info.url or f"atado a {info.bind_host} (GRAFANA_BIND_HOST en observability/grafana.env)"
    return f"Grafana: {donde}  usuario: {info.user}  clave: {info.password}"


def _run_command(argv: Sequence[str], **kwargs) -> str:
    return subprocess.run(
        list(argv), check=True, capture_output=True, text=True, **kwargs
    ).stdout


def stop_grafana(cfg: Config, *, runner: Optional[Runner] = None) -> None:
    """Best-effort: quitting must never fail because Docker is gone."""
    marker = owned_marker(cfg)
    if not marker.is_file():
        return
    runner = runner or _run_command
    try:
        runner(["bash", str(cfg.repo_root / "scripts" / "observability.sh"), "down"])
    except Exception:
        pass
    finally:
        marker.unlink(missing_ok=True)


class GrafanaService:
    """Injectable face of this module, so presenters take a fake."""

    def __init__(self, runner: Optional[Runner] = None) -> None:
        self._runner = runner

    def info(self, cfg: Config) -> Optional[GrafanaInfo]:
        return read_info(cfg)

    def stop_grafana(self, cfg: Config) -> None:
        stop_grafana(cfg, runner=self._runner)
