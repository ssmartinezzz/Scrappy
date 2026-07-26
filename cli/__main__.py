"""Entry point: arg parsing + capability detection + mode routing.

This module is the ONLY place graceful-degradation routing is decided
(design.md §2.1, spec: native-cli-orchestration "Graceful TUI Degradation").
Neither presenter (`tui/`, `plain/`) re-implements an `isatty`/`NO_COLOR`
check — they are both driven by the single decision made here.

The `tui/` import only ever happens inside the `interactive=True` branch,
and is wrapped in a `try/except ImportError` — so a machine without
Textual installed still runs the plain fallback instead of crashing at
import time (see also `cli/plain/runner.py`'s module docstring: it has
zero `textual`/`rich` imports of its own).
"""
from __future__ import annotations

import argparse
import os
import platform
import sys
from typing import Callable, Optional, Sequence

from cli.core.config import Config, load_config


def is_legacy_cmd_without_ansi(
    system: Optional[str] = None,
    environ: Optional[dict] = None,
) -> bool:
    """True on a legacy Windows `cmd.exe` console that predates Windows
    10's ANSI/VT100 support. Windows Terminal and modern PowerShell hosts
    set `WT_SESSION`/`TERM_PROGRAM`; third-party ANSI-capable consoles set
    `ANSICON`/`ConEmuANSI=ON`. Absence of all of these on Windows is
    treated as "legacy cmd.exe without ANSI" per design.md §2.1.
    """
    system = system if system is not None else platform.system()
    environ = environ if environ is not None else os.environ
    if system != "Windows":
        return False
    if environ.get("WT_SESSION") or environ.get("TERM_PROGRAM"):
        return False
    if environ.get("ANSICON") or environ.get("ConEmuANSI") == "ON":
        return False
    return True


def detect_interactive(
    argv: Sequence[str],
    environ: dict,
    stdout_isatty: bool,
    stdin_isatty: bool,
    system: Optional[str] = None,
) -> bool:
    """Capability detection — runs ONCE, before any presenter is built.

    Returns `False` (routes to the plain fallback) when: `--plain` was
    passed, `NO_COLOR` is set, `TERM=dumb`, stdout/stdin is not a TTY
    (piped/redirected), or the console is a legacy `cmd.exe` without ANSI
    support. Returns `True` (routes to the Textual TUI) otherwise.
    (spec: native-cli-orchestration, "Graceful TUI Degradation" — scenarios
    "Piped output falls back to plain text", "NO_COLOR respected".)
    """
    if "--plain" in argv:
        return False
    if environ.get("NO_COLOR"):
        return False
    if environ.get("TERM") == "dumb":
        return False
    if not stdout_isatty or not stdin_isatty:
        return False
    if is_legacy_cmd_without_ansi(system=system, environ=environ):
        return False
    return True


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="cli", description="Fashion Scraper native CLI (build + orchestrate + REST client)"
    )
    parser.add_argument(
        "--plain",
        action="store_true",
        help="Force the non-interactive plain-text runner, bypassing capability detection.",
    )
    parser.add_argument(
        "--regenerate",
        "--force",
        dest="force_env",
        action="store_true",
        help=(
            "Overwrite .env from computed defaults, bypassing create-if-absent/"
            "additive-reconcile (task 1.3.7 / spec: env-file-generation, "
            "'Explicit Force Regenerate Flag')."
        ),
    )
    return parser.parse_args(list(argv))


def _load_tui_run() -> Optional[Callable[..., int]]:
    """Import `cli.tui.app.run` lazily, returning `None` (never raising)
    if Textual is not importable in the running interpreter."""
    try:
        from cli.tui.app import run as tui_run
    except ImportError:
        return None
    return tui_run


def main(
    argv: Optional[Sequence[str]] = None,
    cfg: Optional[Config] = None,
    environ: Optional[dict] = None,
    stdout_isatty: Optional[Callable[[], bool]] = None,
    stdin_isatty: Optional[Callable[[], bool]] = None,
) -> int:
    """CLI entry point.

    `cfg`, `environ`, `stdout_isatty`, `stdin_isatty` are injection points
    for tests (mirrors the `core/` modules' DI style — e.g.
    `RestClient.opener`, `ProcessManager.popen_factory`) so routing can be
    exercised without a real repo checkout or a real terminal.
    """
    argv = list(argv if argv is not None else sys.argv[1:])
    args = parse_args(argv)
    cfg = cfg if cfg is not None else load_config()
    environ = environ if environ is not None else os.environ
    stdout_isatty = stdout_isatty or sys.stdout.isatty
    stdin_isatty = stdin_isatty or sys.stdin.isatty

    interactive = detect_interactive(
        argv=argv,
        environ=environ,
        stdout_isatty=stdout_isatty(),
        stdin_isatty=stdin_isatty(),
    )

    if interactive:
        tui_run = _load_tui_run()
        if tui_run is not None:
            return tui_run(cfg, force_env=args.force_env)
        # Textual not importable: degrade to plain rather than crash —
        # stronger than the spec requires, but "never crash" is the rule.

    from cli.plain.runner import run as plain_run

    return plain_run(cfg, force_env=args.force_env)


if __name__ == "__main__":
    raise SystemExit(main())
