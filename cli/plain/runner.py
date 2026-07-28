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
import webbrowser
from typing import Callable, Optional, TextIO

from cli.core.builder import build_project, is_built
from cli.core.config import Config
from cli.core.env_file import compute_defaults, generate_env, parse_env
from cli.core.errors import CliError
from cli.core.processes import ProcessManager
from cli.core.rest import RestClient

MENU = """\
Fashion Scraper -- plain mode (non-interactive terminal detected)
  build       run the full build (npm install/build + mvn package)
  start       start backend (:3000) + frontend (:5173)
  scrape      POST /api/scrape
  retrain     POST /api/ml/entrenar
  status      GET /api/status
  sites       GET /api/sitios
  add-site <nombre> <url> [plataforma]   POST /api/sitios
  del-site <nombre>                      DELETE /api/sitios/{nombre}
  open        open the dashboard in the default browser
  q           quit (tears down backend + frontend)
"""


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
    ) -> None:
        self.cfg = cfg
        self.rest = rest or RestClient(base_url=f"http://localhost:{cfg.ports.backend}")
        self.processes = processes or ProcessManager()
        self.out = out
        self.in_ = in_

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
        if command in {"q", "quit", "exit"}:
            return False

        try:
            parts = command.split()
            verb, args = parts[0], parts[1:]
            if verb == "build":
                build_project(self.cfg)
                self._print("Build complete.")
            elif verb == "start":
                if not is_built(self.cfg):
                    self._print("jar/frontend ausente — compilando primero…")
                    build_project(self.cfg)
                env = self._env()
                self.processes.launch_backend(
                    self.cfg, database_password=env.get("DATABASE_PASSWORD", ""), env=env
                )
                self.processes.launch_frontend(self.cfg, env=env)
                self._print("Backend + frontend started.")
            elif verb == "scrape":
                self._print(cmd_scrape(self.rest))
            elif verb == "retrain":
                self._print(cmd_retrain(self.rest))
            elif verb == "status":
                self._print(cmd_status(self.rest))
            elif verb == "sites":
                self._print(cmd_list_sitios(self.rest))
            elif verb == "open":
                self._print(cmd_open_dashboard(self.cfg))
            elif verb == "add-site" and len(args) >= 2:
                plataforma = args[2] if len(args) > 2 else "tiendanube"
                self._print(cmd_add_sitio(self.rest, args[0], args[1], plataforma))
            elif verb == "del-site" and len(args) >= 1:
                self._print(cmd_del_sitio(self.rest, args[0]))
            else:
                self._print(f"Unknown command: {command!r}. Type 'q' to quit.")
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
