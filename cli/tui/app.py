"""Textual presenter: a thin `App` over the same headless `core/` the
plain runner drives (design.md §2, ADR-001). Holds NO business logic of
its own -- every action method below is a one-line call into `core/`,
dispatched off the UI thread via a `@work(thread=True)` worker so a
blocking REST call or a multi-minute build never freezes rendering.

Actions are reachable two ways, both routing to the same `action_*`
methods: the single-key `BINDINGS` (shown in the Footer) and the clickable
`Button`s in the sidebar (`on_button_pressed`). The look -- layout, borders,
colours -- is the `CSS` block below; widgets stay presentation-only.

`cli/__main__.py` is the only place that decides whether this module gets
imported at all (capability-detection routing) -- this file assumes
Textual is present, by design.
"""
from __future__ import annotations

import webbrowser
from typing import Callable, Optional

from textual import work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.widgets import Button, Footer, Header, Input

from cli.core.config import Config
from cli.core.env_file import compute_defaults, generate_env
from cli.core.errors import CliError
from cli.core.processes import ProcessManager
from cli.core.rest import RestClient
from cli.tui.widgets import LogTail, StatusPanel


class FashionScraperApp(App):
    """Menu: build/start/scrape/retrain/status/site CRUD/open dashboard.
    `Q` and `Ctrl+C` both tear down backend + frontend before exiting."""

    TITLE = "Fashion Scraper"
    SUB_TITLE = "CLI nativo · build · orquestación · REST"

    CSS = """
    Screen {
        background: $background;
    }

    #body {
        height: 1fr;
    }

    /* -- left: clickable action buttons ------------------------------ */
    #sidebar {
        width: 30;
        padding: 1 2;
        background: $panel;
        border-right: tall $primary;
    }
    #sidebar Button {
        width: 100%;
        margin-bottom: 1;
    }
    #sidebar .spacer {
        height: 1fr;
    }

    /* -- right: status / site form / log ----------------------------- */
    #main {
        padding: 1 2;
        height: 1fr;
    }
    #status {
        height: auto;
        min-height: 3;
        padding: 1 2;
        margin-bottom: 1;
        color: $text;
        background: $boost;
        border: round $accent;
        border-title-color: $accent;
        border-title-align: left;
    }
    #site-form {
        height: auto;
        padding: 1 2;
        margin-bottom: 1;
        border: round $secondary;
        border-title-color: $secondary;
        border-title-align: left;
    }
    #site-form Input {
        margin-bottom: 1;
    }
    #log {
        height: 1fr;
        padding: 0 1;
        background: $surface;
        border: round $primary;
        border-title-color: $primary;
        border-title-align: left;
    }
    """

    BINDINGS = [
        Binding("q", "quit_app", "Quit", priority=True),
        Binding("ctrl+c", "quit_app", "Quit", priority=True, show=False),
        Binding("b", "do_build", "Build"),
        Binding("u", "start_services", "Start"),
        Binding("s", "scrape", "Scrape"),
        Binding("r", "retrain", "Retrain"),
        Binding("t", "status", "Status"),
        Binding("l", "list_sites", "List sites"),
        Binding("a", "add_site", "Add site"),
        Binding("x", "delete_site", "Delete site"),
        Binding("o", "open_dashboard", "Open dashboard"),
    ]

    # Sidebar button id -> the `action_<name>` method it triggers. Keeps
    # click dispatch and key dispatch pointed at the exact same handlers.
    _BUTTON_ACTIONS = {
        "btn-build": "do_build",
        "btn-start": "start_services",
        "btn-scrape": "scrape",
        "btn-retrain": "retrain",
        "btn-status": "status",
        "btn-list": "list_sites",
        "btn-add": "add_site",
        "btn-delete": "delete_site",
        "btn-open": "open_dashboard",
        "btn-quit": "quit_app",
    }

    def __init__(
        self,
        cfg: Config,
        rest: Optional[RestClient] = None,
        processes: Optional[ProcessManager] = None,
        open_url: Optional[str] = None,
        opener: Callable[[str], object] = webbrowser.open,
    ) -> None:
        super().__init__()
        self.cfg = cfg
        self.rest = rest or RestClient(base_url=f"http://localhost:{cfg.ports.backend}")
        self.processes = processes or ProcessManager()
        self.open_url = open_url
        self.opener = opener

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        with Horizontal(id="body"):
            with Vertical(id="sidebar"):
                yield Button("Build", id="btn-build", variant="primary")
                yield Button("Start services", id="btn-start", variant="success")
                yield Button("Scrape", id="btn-scrape", variant="primary")
                yield Button("Retrain", id="btn-retrain", variant="warning")
                yield Button("Status", id="btn-status")
                yield Button("List sites", id="btn-list")
                yield Button("Add site", id="btn-add", variant="success")
                yield Button("Delete site", id="btn-delete", variant="error")
                yield Button("Open dashboard", id="btn-open", variant="primary")
                yield Button("Quit", id="btn-quit", variant="error")
            with Vertical(id="main"):
                yield StatusPanel(id="status")
                with Vertical(id="site-form"):
                    yield Input(placeholder="nombre del sitio", id="site-nombre")
                    yield Input(placeholder="url del sitio", id="site-url")
                    yield Input(placeholder="plataforma (default: tiendanube)", id="site-plataforma")
                yield LogTail(id="log", highlight=False)
        yield Footer()

    def on_mount(self) -> None:
        """Border titles are set post-mount so they render reliably
        regardless of Textual's compose-time attribute timing."""
        self.query_one("#status").border_title = "Estado"
        self.query_one("#site-form").border_title = "Sitio · Add (a) / Delete (x)"
        self.query_one("#log").border_title = "Log"

    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Route a sidebar click to the same `action_*` method its matching
        key binding would fire. Unknown ids are ignored (never crash)."""
        action = self._BUTTON_ACTIONS.get(event.button.id or "")
        if action is not None:
            getattr(self, f"action_{action}")()

    # -- logging / status helpers -----------------------------------

    def _log(self, text: str) -> None:
        try:
            self.query_one("#log", LogTail).append_line(text)
        except Exception:  # noqa: BLE001 - widget may not be mounted (e.g. early call)
            pass

    def _set_status(self, payload: object) -> None:
        try:
            self.query_one("#status", StatusPanel).update_status(payload)
        except Exception:  # noqa: BLE001
            pass

    # -- off-UI-thread core dispatch ---------------------------------

    @work(thread=True, exclusive=False)
    def _run_core(self, label: str, fn: Callable[[], object]) -> None:
        """Runs `fn` in a real OS thread (never the Textual event loop),
        so a blocking `urllib` REST call or a multi-minute `npm`/`mvn`
        build never freezes rendering. Results/errors are marshalled back
        onto the UI thread via `call_from_thread`."""
        try:
            result = fn()
        except CliError as exc:
            self.call_from_thread(self._log, f"{label} failed: {exc.message}")
            return
        except Exception as exc:  # noqa: BLE001 - a worker must never crash the app
            self.call_from_thread(self._log, f"{label} failed: {exc}")
            return
        self.call_from_thread(self._log, f"{label}: {result}")
        if label == "status":
            self.call_from_thread(self._set_status, result)

    # -- action methods (menu operations) ----------------------------

    def action_do_build(self) -> None:
        self._run_core("build", lambda: self._build())

    def _build(self) -> str:
        from cli.core.builder import build_project

        build_project(self.cfg)
        return "build complete"

    def action_start_services(self) -> None:
        self._run_core("start", self._start_services)

    def _start_services(self) -> str:
        from cli.core.env_file import parse_env

        password = parse_env(self.cfg.repo_root / ".env").get("DATABASE_PASSWORD", "")
        self.processes.launch_backend(self.cfg, database_password=password)
        self.processes.launch_frontend(self.cfg)
        return "backend + frontend started"

    def action_scrape(self) -> None:
        self._run_core("scrape", self.rest.scrape)

    def action_retrain(self) -> None:
        self._run_core("retrain", self.rest.entrenar)

    def action_status(self) -> None:
        self._run_core("status", self.rest.status)

    def action_list_sites(self) -> None:
        self._run_core("sites", self.rest.listar_sitios)

    def action_add_site(self) -> None:
        nombre = self.query_one("#site-nombre", Input).value
        url = self.query_one("#site-url", Input).value
        plataforma = self.query_one("#site-plataforma", Input).value or "tiendanube"
        self._run_core("add-site", lambda: self.rest.crear_sitio(nombre, url, plataforma))

    def action_delete_site(self) -> None:
        nombre = self.query_one("#site-nombre", Input).value
        self._run_core("delete-site", lambda: self.rest.eliminar_sitio(nombre))

    def action_open_dashboard(self) -> None:
        url = self.open_url or f"http://localhost:{self.cfg.ports.frontend}"
        self.opener(url)
        self._log(f"Opened {url}")

    def action_quit_app(self) -> None:
        """Bound to both `q` and `ctrl+c` -- the clean-teardown funnel
        (spec: native-cli-orchestration, "Clean teardown on exit")."""
        self.processes.shutdown_all()
        self.exit()


def run(cfg: Config, force_env: bool = False) -> int:
    """Entry point called by `cli/__main__.py` when capability detection
    routes to the interactive Textual TUI."""
    if force_env:
        generate_env(
            cfg.repo_root / ".env.example",
            cfg.repo_root / ".env",
            compute_defaults(cfg),
            force=True,
        )
    app = FashionScraperApp(cfg)
    app.run()
    return 0
