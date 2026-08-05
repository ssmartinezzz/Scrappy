"""Local build + service health signals for the CLI's status view.

Headless: no `textual`/`rich` imports (see `cli.core` package docstring),
so it is unit-testable without a terminal. Every signal is derived from
either the filesystem (is it built?) or a plain TCP connect to a localhost
port (is it up?) -- NEVER from a new backend endpoint (design.md seam:
the CLI consumes only the existing REST API, and here not even that).

The socket connect is injectable (`connect=`) so tests are deterministic
and never touch a real port, mirroring the DI style of the other core
modules (`RestClient.opener`, `ProcessManager.popen_factory`).
"""
from __future__ import annotations

import socket
from dataclasses import dataclass
from typing import Callable, Optional

from cli.core.config import Config

# (host, port, timeout) -> reachable?
ConnectFn = Callable[[str, int, float], bool]

DEFAULT_CONNECT_TIMEOUT = 0.35


@dataclass(frozen=True)
class Check:
    """One health signal. `ok` drives the indicator; `detail` is a short,
    non-user-derived hint (a path label or `:port`) safe to render.

    `short` is the compact label for the console's one-line status bar
    (`api`, `pg`, `dist`). It lives here rather than in a lookup table in
    the widget so renaming a check can't silently degrade the display —
    empty means "no short form, use `name`".
    """

    name: str
    ok: bool
    detail: str
    short: str = ""


def _default_connect(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def port_open(
    host: str,
    port: int,
    timeout: float = DEFAULT_CONNECT_TIMEOUT,
    connect: Optional[ConnectFn] = None,
) -> bool:
    """True if a TCP connection to `host:port` succeeds within `timeout`."""
    return (connect or _default_connect)(host, port, timeout)


def build_checks(cfg: Config) -> list[Check]:
    """Filesystem-derived 'is it built / generated?' signals — instant, no
    sockets. Order is install → build so the panel reads top-to-bottom the
    way the CLI actually progresses."""
    root = cfg.repo_root
    specs = [
        (".env", "env", root / ".env", "generado", "falta — corré `build`"),
        ("frontend/.env", "fe-env", root / "frontend" / ".env", "generado", "falta"),
        ("backend jar", "jar", root / "scraper" / "scraper.jar", "compilado", "sin compilar"),
        ("frontend build", "dist", root / "frontend" / "dist", "compilado", "sin compilar"),
    ]
    return [
        Check(name, path.exists(), ok_detail if path.exists() else bad_detail, short=short)
        for (name, short, path, ok_detail, bad_detail) in specs
    ]


def service_checks(cfg: Config, connect: Optional[ConnectFn] = None) -> list[Check]:
    """Liveness of each service by TCP port probe on localhost."""
    p = cfg.ports
    services = [
        ("Postgres", "pg", p.postgres),
        ("Backend", "api", p.backend),
        ("Frontend", "web", p.frontend),
    ]
    return [
        Check(name, port_open("localhost", port, connect=connect), f":{port}", short=short)
        for (name, short, port) in services
    ]


def health_report(cfg: Config, connect: Optional[ConnectFn] = None) -> list[Check]:
    """Full ordered report: build/generation state first, then live
    service liveness. This is the single entry point the TUI polls."""
    return build_checks(cfg) + service_checks(cfg, connect=connect)
