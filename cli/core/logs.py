"""Service log files: where a launched child writes, and how the console
reads it back.

This module exists because of one hard rule: **a child process must never
write to the terminal the CLI is drawing on**. Textual owns the screen; a
Spring Boot banner or a Vite re-render arriving on the inherited stdout
paints raw ANSI over the current frame and corrupts it (no repaint fixes
it, because Textual never knows it happened). So every child is bound to a
file here, and the user reads it back through the `logs` command.

Headless: no `textual`/`rich` imports (see the `cli.core` package
docstring), so both presenters share it.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

from cli.core.config import Config

# The services whose stdio the CLI owns. This is a fixed allow-list, not a
# suggestion: `service_log_path` refuses anything outside it, so a service
# name can never be used to steer a write outside the log directory.
SERVICES: tuple[str, ...] = ("backend", "frontend")

DEFAULT_TAIL_LINES = 40

# How much of the file's tail to read for a `tail()` call. A rolling
# backend log reaches megabytes; reading it whole to show 40 lines would
# stall the UI thread, so we seek from the end and read a bounded window.
_TAIL_BLOCK = 16 * 1024


def log_dir(cfg: Config) -> Path:
    """The directory both service logs live in. Shares `scraper/logs/` with
    the backend's own logback output so there is exactly one place to look."""
    return cfg.repo_root / "scraper" / "logs"


def service_log_path(cfg: Config, service: str) -> Path:
    """Path of `service`'s combined stdout+stderr log.

    Raises `ValueError` for an unknown service rather than interpolating
    the name into a path — the name reaches the filesystem, so it is
    validated against `SERVICES` instead of trusted.
    """
    if service not in SERVICES:
        raise ValueError(f"unknown service {service!r} (known: {', '.join(SERVICES)})")
    return log_dir(cfg) / f"{service}.log"


def open_log(cfg: Config, service: str):
    """Open `service`'s log for appending in binary mode, creating the log
    directory if needed. Binary because it is handed straight to `Popen` as
    a stdio target — the child owns the encoding, not us."""
    path = service_log_path(cfg, service)
    path.parent.mkdir(parents=True, exist_ok=True)
    return path.open("ab")


def tail(path: Path, lines: int = DEFAULT_TAIL_LINES) -> list[str]:
    """Last `lines` lines of `path`, oldest first.

    Returns `[]` for a missing or unreadable file — a log the user has not
    generated yet is a normal state, not an error. Undecodable bytes are
    replaced rather than raised on: a child emits whatever it emits, and
    that must never blow up inside a render path.
    """
    if lines <= 0:
        return []
    try:
        size = path.stat().st_size
    except OSError:
        return []

    want = min(size, _TAIL_BLOCK)
    try:
        with path.open("rb") as fh:
            if want < size:
                fh.seek(size - want)
            raw = fh.read(want)
    except OSError:
        return []

    text = raw.decode("utf-8", errors="replace")
    if want < size:
        # The window may have cut a line in half — drop that first partial.
        _, _, text = text.partition("\n")
    return text.splitlines()[-lines:]


def tail_service(cfg: Config, service: str, lines: int = DEFAULT_TAIL_LINES) -> list[str]:
    """`tail()` of a known service's log. Unknown service -> `ValueError`."""
    return tail(service_log_path(cfg, service), lines=lines)


def resolve_service(name: Optional[str]) -> str:
    """Map a user-typed service name onto `SERVICES`, defaulting to the
    backend (the log people actually want when they type a bare `logs`)."""
    if not name:
        return "backend"
    lowered = name.strip().lower()
    aliases = {"api": "backend", "back": "backend", "web": "frontend", "front": "frontend"}
    resolved = aliases.get(lowered, lowered)
    if resolved not in SERVICES:
        raise ValueError(f"unknown service {name!r} (known: {', '.join(SERVICES)})")
    return resolved
