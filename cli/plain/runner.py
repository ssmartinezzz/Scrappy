"""Non-interactive text driver over the SAME headless `core/` the Textual
TUI drives (`cli/tui/app.py`). This module has ZERO `textual`/`rich`
imports — that is what lets `cli/__main__.py` fall back to it even when
Textual can't be imported at all, and what makes it safe to run under
`NO_COLOR`/`TERM=dumb`/piped stdout (spec: native-cli-orchestration,
"Graceful TUI Degradation").

Emits plain text only — no ANSI escape codes, no `rich` markup — so it is
`NO_COLOR`-safe by construction rather than by a runtime check.
"""
from __future__ import annotations

import sys
import time
import webbrowser
from typing import Callable, Optional, TextIO

from cli.core import logs
from cli.core.builder import build_project, is_built
from cli.core.commands import find, menu_text
from cli.core.config import Config
from cli.core.env_file import compute_defaults, generate_env, parse_env
from cli.core.runtime_config import LOCAL, Origins, apply_mode
from cli.core.errors import CliError
from cli.core.processes import ProcessManager
from cli.core.rest import RestClient, build_rest_client

# Grace period before believing a just-launched service, mirroring the
# console's STARTUP_GRACE_SECONDS. Kept local so this module stays free
# of any import from cli/tui/ (the degradation contract).
STARTUP_GRACE_SECONDS = 1.5

# Rendered from `core.commands`, the same registry the console autocompletes
# from — so the menu can never advertise a verb dispatch does not handle.
MENU = (
    "Fashion Scraper -- plain mode (non-interactive terminal detected)\n"
    + menu_text()
)


def _payload_text(payload: object) -> str:
    return str(payload)


def cmd_status(rest: RestClient) -> str:
    return _payload_text(rest.status())


def cmd_scrape(rest: RestClient) -> str:
    return _payload_text(rest.scrape())


def cmd_retrain(rest: RestClient) -> str:
    return _payload_text(rest.entrenar())


def cmd_list_sitios(rest: RestClient) -> str:
    return _payload_text(rest.listar_sitios())


def cmd_add_sitio(rest: RestClient, nombre: str, url: str, plataforma: str = "tiendanube") -> str:
    return _payload_text(rest.crear_sitio(nombre, url, plataforma))


def cmd_del_sitio(rest: RestClient, nombre: str) -> str:
    return _payload_text(rest.eliminar_sitio(nombre))


def cmd_logs(cfg: Config, service: Optional[str] = None, count: Optional[str] = None) -> str:
    """Tail of a service's log file. The services never write to this
    terminal (see `core.processes` stdio containment) — this is how their
    output is read back."""
    resolved = logs.resolve_service(service)
    lines = logs.tail(
        logs.service_log_path(cfg, resolved),
        lines=int(count) if count and count.isdigit() else logs.DEFAULT_TAIL_LINES,
    )
    if not lines:
        return f"sin log todavía para {resolved}"
    return "\n".join(lines)


def cmd_open_dashboard(
    cfg: Config,
    open_url: Optional[str] = None,
    opener: Callable[[str], object] = webbrowser.open,
) -> str:
    url = open_url or f"http://localhost:{cfg.ports.frontend}"
    opener(url)
    return f"Opened {url}"


class PlainRunner:
    """Menu dispatcher: reads one command per line, drives `core/` only.

    Reading via `self.in_.readline()` (never `input()`) means EOF on a
    piped/non-tty stdin returns `""` immediately instead of raising —
    the runner exits its loop cleanly rather than hanging or crashing
    (spec scenario "Piped output falls back to plain text ... does not
    crash or hang waiting for terminal input").
    """

    def __init__(
        self,
        cfg: Config,
        rest: Optional[RestClient] = None,
        processes: Optional[ProcessManager] = None,
        out: TextIO = sys.stdout,
        in_: TextIO = sys.stdin,
        opener: Callable[[str], object] = webbrowser.open,
    ) -> None:
        self.cfg = cfg
        self.rest = rest or build_rest_client(cfg)
        self.processes = processes or ProcessManager()
        self.out = out
        self.in_ = in_
        self.opener = opener
        # Set by `start`. `open` follows it instead of APP_OPEN_URL, which
        # records whatever the .env was last frozen at, not this run.
        self.active_origins: Optional[Origins] = None

    def _print(self, text: str) -> None:
        print(text, file=self.out)

    def _env(self) -> dict:
        return parse_env(self.cfg.repo_root / ".env")

    def dispatch(self, line: str) -> bool:
        """Handle a single command line. Returns `False` when the runner
        should stop (quit); never raises — every action's failure is
        caught and printed so a bad command can't crash the process."""
        command = line.strip()
        if not command:
            return True

        parts = command.split()
        verb, args = parts[0], parts[1:]
        cmd = find(verb)
        if cmd is None:
            self._print(f"Unknown command: {command!r}. Type 'help' or 'q' to quit.")
            return True
        if cmd.name == "quit":
            return False

        try:
            name = cmd.name
            if name == "build":
                build_project(self.cfg)
                self._print("Build complete.")
            elif name == "start":
                mode = args[0] if args else LOCAL
                if not is_built(self.cfg):
                    self._print("jar/frontend ausente — compilando primero…")
                    build_project(self.cfg)
                env = self._env()
                # After the build: apply_mode writes into frontend/dist.
                origins = apply_mode(self.cfg, mode, env)
                self.active_origins = origins
                self._print(f"modo {mode} — API en {origins.backend}")
                self.processes.launch_backend(
                    self.cfg, database_password=env.get("DATABASE_PASSWORD", ""), env=env
                )
                self.processes.launch_frontend(self.cfg, env=env)
                # A service that dies on boot (bad DATABASE_URL, port taken)
                # used to be visible because its output landed on the
                # terminal. Now that it is redirected, saying so here is the
                # only honest signal left. Same contract as the console.
                time.sleep(STARTUP_GRACE_SECONDS)
                dead = [n for n in ("backend", "frontend") if n not in self.processes.alive()]
                if dead:
                    self._print(
                        f"{' and '.join(dead)} died on startup — run "
                        f"`logs {dead[0]}` to see why."
                    )
                else:
                    self._print("Backend + frontend started. Su salida va a `logs`.")
            elif name == "stop":
                self.processes.shutdown_all()
                self._print("Backend + frontend stopped.")
            elif name == "scrape":
                self._print(cmd_scrape(self.rest))
            elif name == "retrain":
                self._print(cmd_retrain(self.rest))
            elif name == "status":
                self._print(cmd_status(self.rest))
            elif name == "sites":
                self._print(cmd_list_sitios(self.rest))
            elif name == "logs":
                self._print(cmd_logs(self.cfg, *args[:2]))
            elif name == "open":
                url = self.active_origins.frontend if self.active_origins else None
                self._print(cmd_open_dashboard(self.cfg, url, self.opener))
            elif name == "help":
                self._print(MENU)
            elif name == "clear":
                # No screen to clear in a non-interactive stream; a rule is
                # the honest equivalent and keeps the verb from being a lie.
                self._print("-" * 60)
            elif name == "add-site":
                if len(args) < 2:
                    self._print(f"Usage: {cmd.usage}")
                else:
                    plataforma = args[2] if len(args) > 2 else "tiendanube"
                    self._print(cmd_add_sitio(self.rest, args[0], args[1], plataforma))
            elif name == "del-site":
                if not args:
                    self._print(f"Usage: {cmd.usage}")
                else:
                    self._print(cmd_del_sitio(self.rest, args[0]))
        except CliError as exc:
            self._print(f"Error: {exc.message}")
        except Exception as exc:  # noqa: BLE001 - plain mode must never crash the process
            self._print(f"Error: {exc}")
        return True

    def run(self) -> int:
        self._print(MENU)
        try:
            while True:
                line = self.in_.readline()
                if line == "":  # EOF: piped/non-tty stdin, never hang
                    break
                if not self.dispatch(line):
                    break
        finally:
            self.processes.shutdown_all()
        return 0


def run(cfg: Config, force_env: bool = False, **kwargs) -> int:
    """Entry point called by `cli/__main__.py` when capability detection
    routes to the plain fallback (or Textual is not importable)."""
    if force_env:
        generate_env(
            cfg.repo_root / ".env.example",
            cfg.repo_root / ".env",
            compute_defaults(cfg),
            force=True,
        )
    runner = PlainRunner(cfg, **kwargs)
    return runner.run()
