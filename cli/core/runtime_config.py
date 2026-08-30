"""Where the frontend talks, decided per run instead of per build.

`VITE_API_BASE_URL` is baked into the bundle by Vite, so for a long time the
choice between "localhost" and "reachable from another device" was frozen at
build time and lived in two persistent files. Switching meant editing `.env`,
editing `frontend/.env`, and rebuilding — an architectural decision taken at
install time and paid for on every change.

The bundle now reads `window.__API_BASE__` first (`frontend/src/api.js`), set
by `dist/config.js`. This module rewrites that file at launch, so ONE build
serves loopback, a LAN origin behind TLS, and a deployment.

The build-time value stays as the fallback: an unmanaged build (`npm run build`
straight from `frontend/`) keeps behaving exactly as before.
"""
from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
from typing import Optional

from cli.core.config import Config

LOCAL = "local"
LAN = "lan"

#: Mode -> what it means, for `--help` and for the error message below.
MODES: dict[str, str] = {
    LOCAL: "loopback — el default; el navegador y los servicios en esta máquina",
    LAN: "otro dispositivo de la red; lee SCRAPPY_FRONTEND_ORIGIN y SCRAPPY_BACKEND_ORIGIN",
}


class UnknownMode(ValueError):
    """An unknown mode, or a known one that cannot be resolved as configured."""


@dataclass(frozen=True)
class Origins:
    """The pair a run needs: where the browser lands, and who it calls."""

    frontend: str
    backend: str


def resolve_origins(mode: str, cfg: Config) -> Origins:
    """The origins for `mode`.

    `lan` refuses to fall back to localhost. A bundle that quietly kept calling
    `localhost:3000` would, on the phone, be calling the phone — the exact
    failure this mechanism exists to prevent, and one that surfaces as an app
    that loads and then does nothing.
    """
    if mode not in MODES:
        conocidos = ", ".join(sorted(MODES))
        raise UnknownMode(f"modo desconocido: {mode!r}. Modos válidos: {conocidos}")

    if mode == LOCAL:
        return Origins(
            frontend=f"http://localhost:{cfg.ports.frontend}",
            backend=f"http://localhost:{cfg.ports.backend}",
        )

    # Explicit origins still win: a tunnel or a deployment is reachable by a
    # name this machine cannot detect. Otherwise the CLI derives them from the
    # LAN address and the ports its own TLS terminator listens on.
    frontend = os.environ.get("SCRAPPY_FRONTEND_ORIGIN", "").strip()
    backend = os.environ.get("SCRAPPY_BACKEND_ORIGIN", "").strip()
    if not frontend or not backend:
        from cli.core import lan_proxy

        ip = lan_proxy.detect_lan_ip()
        frontend = frontend or f"https://{ip}:{lan_proxy.TLS_FRONTEND_PORT}"
        backend = backend or f"https://{ip}:{lan_proxy.TLS_BACKEND_PORT}"
    return Origins(frontend=frontend, backend=backend)


def apply_mode(cfg: Config, mode: str, env: dict) -> Origins:
    """Point a run at `mode`: the bundle's backend origin, the URL the browser
    is opened at, and the CORS allow-list.

    Mutates `env` (the parsed `.env`) instead of the file, so the mode is a
    property of THIS run and nothing on disk records it. The `.env` keeps
    whatever it had.
    """
    origins = resolve_origins(mode, cfg)
    if mode == LAN:
        # The terminator has to be up before the browser is pointed at it;
        # a failure here must stop the start, not leave the app served on an
        # origin nothing is listening on.
        from cli.core import lan_proxy

        ip = lan_proxy.detect_lan_ip()
        lan_proxy.ensure_cert(cfg, ip)
        lan_proxy.start_proxy(cfg, ip)
    write_runtime_config(cfg, origins.backend)
    env["APP_OPEN_URL"] = origins.frontend
    env["APP_CORS_ALLOWED_ORIGINS"] = _allow(
        env.get("APP_CORS_ALLOWED_ORIGINS", ""), origins.frontend
    )
    return origins


def _allow(actuales: str, origen: str) -> str:
    """Add `origen` to a comma-separated allow-list, keeping the rest.
    Replacing it would lock out the machine that is running this."""
    lista = [o.strip() for o in actuales.split(",") if o.strip()]
    if origen not in lista:
        lista.append(origen)
    return ",".join(lista)


def write_runtime_config(cfg: Config, backend_origin: str) -> Optional[Path]:
    """Rewrite `frontend/dist/config.js`. Returns the path, or `None` when
    there is no build yet — the caller builds first and calls again."""
    dist = cfg.repo_root / "frontend" / "dist"
    if not dist.is_dir():
        return None

    # Single-quoted assignment, so escape backslashes before quotes: the origin
    # comes from the environment and is not trusted to be a bare URL.
    escaped = backend_origin.replace("\\", "\\\\").replace("'", "\\'")
    path = dist / "config.js"
    path.write_text(
        "// Generado por cli/core/runtime_config.py en cada arranque.\n"
        f"window.__API_BASE__ = '{escaped}';\n",
        encoding="utf-8",
    )
    return path
