"""Textual presenter: a command console over the same headless `core/` the
plain runner drives (design.md §2, ADR-001).

Holds NO business logic of its own — every command below is a thin call
into `core/`, dispatched off the UI thread via a `@work(thread=True)`
worker so a blocking REST call or a multi-minute build never freezes
rendering.

**Shape.** One column, three rows of chrome: a one-line status strip, the
console (everything else), a prompt with a one-line hint under it. There is
no sidebar, no button grid, no bordered panel stack — the previous layout
needed a maximized window before it was usable. Operations are reached by
typing their verb, autocompleted from `core/commands.py` (the same registry
`help` and the plain runner's menu render), not by single-letter keys —
which also means letters like `q` are typeable instead of being swallowed
as actions.

**The console owns the terminal.** Nothing else may write to it. That is
enforced in `core/processes.py`, where every child's stdout/stderr is bound
to a log file (`core/logs.py`) — an inherited stdout is what used to paint
Spring Boot and Vite output over the rendered frame and shred it. The
`logs` command reads those files back into the console instead.

`cli/__main__.py` is the only place that decides whether this module gets
imported at all (capability-detection routing) — this file assumes Textual
is present, by design.
"""
from __future__ import annotations

import time
import webbrowser
from typing import Callable, Optional

from textual import work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal
from textual.widgets import Input, Static

from cli.core import health, logs
from cli.core.commands import find, help_lines
from cli.core.config import Config
from cli.core.env_file import compute_defaults, generate_env
from cli.core.errors import CliError
from cli.core.health import ConnectFn
from cli.core.processes import ProcessManager
from cli.core.lan_proxy import stop_proxy
from cli.core.rest import RestClient, build_rest_client
from cli.core.runtime_config import LOCAL, apply_mode
from cli.tui.widgets import (
    CommandSuggester,
    Console,
    HintBar,
    StatusBar,
    format_payload,
    verb_completion,
)

# How often (seconds) the status strip re-probes build state + service
# ports, so a service coming up flips ○ -> ● without any user action.
HEALTH_REFRESH_SECONDS = 2.0

# How long to let a just-launched service live before believing it. A
# fail-fast backend (RequiredEnvVarsGuard) or a port clash dies well
# inside this; it runs on a worker thread, so nothing blocks rendering.
STARTUP_GRACE_SECONDS = 1.5

BANNER = "scrappy · consola nativa — `help` lista todo"


class Prompt(Input):
    """The command line. Adds Tab-to-complete and ↑/↓ history on top of
    `Input`; everything else (editing, selection) is inherited."""

    BINDINGS = [
        Binding("tab", "complete", "Completar", show=False),
        Binding("up", "history_prev", "Anterior", show=False),
        Binding("down", "history_next", "Siguiente", show=False),
    ]

    def action_complete(self) -> None:
        """Accept the verb completion. Computed from the shared registry
        rather than read off the widget's async ghost-text state, so Tab
        behaves identically whether or not the suggestion has landed."""
        completed = verb_completion(self.value)
        if completed:
            self.value = completed
            self.cursor_position = len(self.value)

    def action_history_prev(self) -> None:
        self.app.recall_history(-1)

    def action_history_next(self) -> None:
        self.app.recall_history(1)


class ScrappyConsole(App):
    """Command console: build / start / stop / scrape / retrain / status /
    site CRUD / logs / open dashboard. `quit` and `Ctrl+C` both tear down
    backend + frontend before exiting."""

    TITLE = "scrappy"

    # Palette first, then layout. Textual resolves `$name` at parse time,
    # so a theme change is one edit here rather than a hunt through every
    # selector. Deliberately literal rather than Textual's theme tokens:
    # the console commits to one dark terminal look instead of inheriting
    # whatever theme is active.
    CSS = """
    $bg:        #0b0f14;
    $bg-raised: #0e141b;
    $bg-strip:  #111820;
    $fg:        #d7e3ea;
    $fg-muted:  #9aa7b0;
    $fg-faint:  #4a5a66;
    $fg-ghost:  #3d4a55;
    $accent:    #4ade80;
    $rule:      #1e2a35;

    Screen {
        background: $bg;
        color: $fg;
        layout: vertical;
    }

    /* -- one-line health strip -------------------------------------- */
    #statusbar {
        height: 1;
        width: 100%;
        padding: 0 1;
        background: $bg-strip;
        color: $fg-muted;
    }

    /* -- the console: the only thing that grows --------------------- */
    #console {
        height: 1fr;
        width: 100%;
        padding: 0 1;
        background: $bg;
        scrollbar-size-vertical: 1;
        scrollbar-background: $bg;
        scrollbar-color: $rule;
    }

    /* -- prompt row -------------------------------------------------- */
    #promptrow {
        height: 1;
        width: 100%;
        background: $bg-raised;
    }
    #sigil {
        width: 2;
        height: 1;
        color: $accent;
        text-style: bold;
        background: $bg-raised;
    }
    #prompt {
        height: 1;
        width: 1fr;
        border: none;
        padding: 0;
        background: $bg-raised;
        color: $fg;
    }
    #prompt > .input--placeholder, #prompt > .input--suggestion {
        color: $fg-ghost;
    }
    #prompt > .input--cursor {
        background: $accent;
        color: $bg;
    }

    /* -- one-line contextual hint ------------------------------------ */
    #hint {
        height: 1;
        width: 100%;
        padding: 0 1;
        background: $bg;
        color: $fg-faint;
    }
    """

    # Only two global keys. Everything else is a typed command, so the
    # letters stay usable at the prompt. `priority` is required for
    # ctrl+c: `Input` binds it to `copy` by default and would win.
    BINDINGS = [
        Binding("ctrl+c", "quit_app", "Salir", priority=True, show=False),
        Binding("ctrl+l", "wipe_console", "Limpiar", priority=True, show=False),
    ]

    def __init__(
        self,
        cfg: Config,
        rest: Optional[RestClient] = None,
        processes: Optional[ProcessManager] = None,
        open_url: Optional[str] = None,
        opener: Callable[[str], object] = webbrowser.open,
        connect: Optional[ConnectFn] = None,
    ) -> None:
        super().__init__()
        self.cfg = cfg
        self.rest = rest or build_rest_client(cfg)
        self.processes = processes or ProcessManager()
        self.open_url = open_url
        self.active_origins = None  # set by `start`; see _cmd_open
        self.opener = opener
        # Socket probe used by the status strip; injectable so tests never
        # touch a real port (defaults to a real TCP connect).
        self.connect = connect
        # Submitted command lines, oldest first, plus the cursor ↑/↓ walks.
        self.history: list[str] = []
        self._history_pos = 0

    def compose(self) -> ComposeResult:
        yield StatusBar(id="statusbar")
        yield Console(id="console")
        with Horizontal(id="promptrow"):
            yield Static("❯ ", id="sigil")
            yield Prompt(
                placeholder="comando…",
                suggester=CommandSuggester(),
                id="prompt",
            )
        yield HintBar(id="hint")

    def on_mount(self) -> None:
        """Greet, focus the prompt, and start the health poll: one
        immediate refresh, then every `HEALTH_REFRESH_SECONDS` so services
        flip ○ -> ● as they come up."""
        self._emit("info", BANNER)
        self.query_one("#prompt", Prompt).focus()
        self._refresh_health()
        self.set_interval(HEALTH_REFRESH_SECONDS, self._refresh_health)

    # -- health poll -------------------------------------------------

    @work(thread=True, exclusive=True, group="health")
    def _refresh_health(self) -> None:
        """Compute the build + service report off the UI thread (the TCP
        probes block briefly) and marshal it back for rendering. `exclusive`
        + a fixed group means overlapping ticks never pile up."""
        report = health.health_report(self.cfg, connect=self.connect)
        self.call_from_thread(self._apply_health, report)

    def _apply_health(self, report) -> None:
        try:
            self.query_one("#statusbar", StatusBar).update_health(report)
        except Exception:  # noqa: BLE001 - widget may not be mounted yet
            pass

    # -- console helpers ---------------------------------------------

    def _emit(self, kind: str, text: object) -> None:
        try:
            self.query_one("#console", Console).emit(kind, format_payload(text))
        except Exception:  # noqa: BLE001 - widget may not be mounted yet
            pass

    def _emit_error(self, exc: CliError) -> None:
        self._emit("err", exc.message)
        if exc.action:
            self._emit("info", exc.action)

    # -- prompt ------------------------------------------------------

    def on_input_changed(self, event: Input.Changed) -> None:
        """Keep the hint line pointed at the verb being typed."""
        verb = event.value.strip().split(" ")[0]
        cmd = find(verb) if verb else None
        try:
            self.query_one("#hint", HintBar).show(
                f"{cmd.usage}   —   {cmd.help}" if cmd else None
            )
        except Exception:  # noqa: BLE001
            pass

    def on_input_submitted(self, event: Input.Submitted) -> None:
        line = event.value.strip()
        event.input.value = ""
        self._history_pos = len(self.history)
        if not line:
            return
        self._remember(line)
        self._emit("cmd", line)
        self.dispatch(line)

    def _remember(self, line: str) -> None:
        """Append to history, collapsing an immediate repeat — walking back
        through five identical `status` calls helps nobody."""
        if not self.history or self.history[-1] != line:
            self.history.append(line)
        self._history_pos = len(self.history)

    def recall_history(self, delta: int) -> None:
        """Walk history with ↑/↓. Position `len(history)` is the live empty
        line, so walking forward past the newest entry clears the prompt
        rather than sticking on it."""
        if not self.history:
            return
        self._history_pos = max(0, min(len(self.history), self._history_pos + delta))
        prompt = self.query_one("#prompt", Prompt)
        prompt.value = (
            "" if self._history_pos >= len(self.history) else self.history[self._history_pos]
        )
        prompt.cursor_position = len(prompt.value)

    # -- dispatch ----------------------------------------------------

    def dispatch(self, line: str) -> None:
        """Route one submitted line. Never raises: an unknown verb, a bad
        argument count or a failing command all end as a console line."""
        parts = line.split()
        verb, args = parts[0], parts[1:]
        cmd = find(verb)
        if cmd is None:
            self._emit("err", f"comando desconocido: {verb}")
            self._emit("info", "escribí `help` para ver los disponibles")
            return

        try:
            # Inside the try on purpose: the registry and these methods are
            # coupled only by name, so an entry added without its handler
            # must degrade to a console line rather than raise into
            # Textual's event loop and take the app down.
            handler = getattr(self, f"_cmd_{cmd.name.replace('-', '_')}")
            handler(args)
        except AttributeError:
            self._emit("err", f"comando sin implementar: {cmd.name}")
        except CliError as exc:  # noqa: BLE001 - surfaced, never fatal
            self._emit_error(exc)
        except Exception as exc:  # noqa: BLE001 - the console must never crash
            self._emit("err", str(exc))

    @work(thread=True, exclusive=False)
    def _run_core(self, label: str, fn: Callable[[], object]) -> None:
        """Runs `fn` in a real OS thread (never the Textual event loop), so
        a blocking `urllib` REST call or a multi-minute `npm`/`mvn` build
        never freezes rendering. Results and errors are marshalled back onto
        the UI thread via `call_from_thread`."""
        try:
            result = fn()
        except CliError as exc:
            self.call_from_thread(self._emit_error, exc)
            return
        except Exception as exc:  # noqa: BLE001 - a worker must never crash the app
            self.call_from_thread(self._emit, "err", f"{label}: {exc}")
            return
        self.call_from_thread(self._emit, "out", result)
        if label in ("build", "start", "stop"):
            # Local state just changed — reflect it now instead of waiting
            # for the next poll tick.
            self.call_from_thread(self._refresh_health)

    def _usage(self, name: str) -> None:
        cmd = find(name)
        self._emit("err", "faltan argumentos")
        if cmd:
            self._emit("info", cmd.usage)

    # -- commands: remote / slow (worker thread) ---------------------

    def _cmd_build(self, args: list[str]) -> None:
        from cli.core.builder import build_project

        self._emit("info", "compilando (npm + mvn) — puede tardar unos minutos…")
        self._run_core("build", lambda: (build_project(self.cfg), "build listo")[1])

    def _cmd_start(self, args: list[str]) -> None:
        mode = args[0] if args else LOCAL
        self._run_core("start", lambda: self._start_services(mode))

    def _start_services(self, mode: str = LOCAL) -> str:
        from cli.core import builder
        from cli.core.env_file import parse_env

        if not builder.is_built(self.cfg):
            self.call_from_thread(
                self._emit, "info", "jar/dist ausente — compilando primero…"
            )
            builder.build_project(self.cfg)
            self.call_from_thread(self._refresh_health)

        env = parse_env(self.cfg.repo_root / ".env")
        # After the build: apply_mode writes into frontend/dist.
        origins = apply_mode(self.cfg, mode, env)
        self.active_origins = origins
        self.call_from_thread(self._emit, "info", f"modo {mode} — API en {origins.backend}")
        self.processes.launch_backend(
            self.cfg, database_password=env.get("DATABASE_PASSWORD", ""), env=env
        )
        self.processes.launch_frontend(self.cfg, env=env)

        # Reporting success the moment Popen returns would be a lie: a
        # backend with a bad DATABASE_URL, or a frontend whose port is
        # taken, exits within a second. That death used to be visible
        # because its output landed on the terminal; now that we redirect
        # it, saying so here is the only honest signal left.
        time.sleep(STARTUP_GRACE_SECONDS)
        dead = [name for name in ("backend", "frontend") if name not in self.processes.alive()]
        if dead:
            return (
                f"{' y '.join(dead)} murió al arrancar — corré `logs {dead[0]}` "
                f"para ver por qué"
            )
        return "backend + frontend arriba — su salida va a `logs` (no a esta consola)"

    def _cmd_stop(self, args: list[str]) -> None:
        def _stop() -> str:
            self.processes.shutdown_all()
            stop_proxy(self.cfg)
            self.active_origins = None
            return "servicios bajados"

        self._run_core("stop", _stop)

    def _cmd_scrape(self, args: list[str]) -> None:
        self._run_core("scrape", self.rest.scrape)

    def _cmd_retrain(self, args: list[str]) -> None:
        self._run_core("retrain", self.rest.entrenar)

    def _cmd_status(self, args: list[str]) -> None:
        self._run_core("status", self.rest.status)

    def _cmd_sites(self, args: list[str]) -> None:
        self._run_core("sites", self.rest.listar_sitios)

    def _cmd_add_site(self, args: list[str]) -> None:
        if len(args) < 2:
            self._usage("add-site")
            return
        nombre, url = args[0], args[1]
        plataforma = args[2] if len(args) > 2 else "tiendanube"
        self._run_core("add-site", lambda: self.rest.crear_sitio(nombre, url, plataforma))

    def _cmd_del_site(self, args: list[str]) -> None:
        if not args:
            self._usage("del-site")
            return
        nombre = args[0]
        self._run_core("del-site", lambda: self.rest.eliminar_sitio(nombre))

    # -- commands: local / instant -----------------------------------

    def _cmd_logs(self, args: list[str]) -> None:
        """Read a service's log file back into the console. This is the
        only way service output reaches the screen — the processes
        themselves are redirected to these files precisely so they can't
        write here directly."""
        try:
            service = logs.resolve_service(args[0] if args else None)
        except ValueError as exc:
            self._emit("err", str(exc))
            return
        count = int(args[1]) if len(args) > 1 and args[1].isdigit() else logs.DEFAULT_TAIL_LINES
        path = logs.service_log_path(self.cfg, service)
        lines = logs.tail(path, lines=count)
        if not lines:
            self._emit("info", f"sin log todavía para {service} ({path})")
            return
        self._emit("info", f"— {service} · últimas {len(lines)} líneas · {path}")
        for line in lines:
            self._emit("raw", line)

    def _cmd_open(self, args: list[str]) -> None:
        # The active mode wins over APP_OPEN_URL: that records whatever the
        # .env was last frozen at, not where this run actually serves.
        url = (
            self.active_origins.frontend
            if self.active_origins
            else self.open_url or f"http://localhost:{self.cfg.ports.frontend}"
        )
        self.opener(url)
        self._emit("out", f"abriendo {url}")

    def _cmd_help(self, args: list[str]) -> None:
        for line in help_lines():
            self._emit("info", line)

    def _cmd_clear(self, args: list[str]) -> None:
        self.action_wipe_console()

    def _cmd_quit(self, args: list[str]) -> None:
        self.action_quit_app()

    # -- global key actions ------------------------------------------

    def action_wipe_console(self) -> None:
        try:
            self.query_one("#console", Console).wipe()
        except Exception:  # noqa: BLE001
            pass

    def action_quit_app(self) -> None:
        """Bound to `ctrl+c` and reached by the `quit` verb — the single
        clean-teardown funnel (spec: native-cli-orchestration, "Clean
        teardown on exit")."""
        self.processes.shutdown_all()
        self.exit()


def run(cfg: Config, force_env: bool = False) -> int:
    """Entry point called by `cli/__main__.py` when capability detection
    routes to the interactive console."""
    if force_env:
        generate_env(
            cfg.repo_root / ".env.example",
            cfg.repo_root / ".env",
            compute_defaults(cfg),
            force=True,
        )
    ScrappyConsole(cfg).run()
    return 0
