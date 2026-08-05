"""Plain fallback runner tests (task 2.2.1). Must pass without Textual
installed — asserted directly by inspecting the module source for any
`textual`/`rich` import (spec: native-cli-orchestration, "Graceful TUI
Degradation").
"""
from __future__ import annotations

import io
import inspect

import cli.plain.runner as runner_module
from cli.core.config import Config, Ports, ToolchainPaths
from cli.plain.runner import PlainRunner, run


def _cfg(tmp_path) -> Config:
    tools = tmp_path / "_tools"
    return Config(
        repo_root=tmp_path,
        tools=ToolchainPaths(
            jdk21=tools / "jdk21",
            maven=tools / "maven",
            node=tools / "node",
            cli_venv=tools / "cli-venv",
            pgsql=tools / "pgsql",
        ),
        ports=Ports(),
    )


class _FakeRest:
    def __init__(self) -> None:
        self.calls: list[tuple] = []

    def status(self):
        self.calls.append(("status",))
        return {"estado": "ocioso"}

    def scrape(self):
        self.calls.append(("scrape",))
        return {"estado": "iniciado"}

    def entrenar(self):
        self.calls.append(("entrenar",))
        return {"estado": "entrenando"}

    def listar_sitios(self):
        self.calls.append(("listar_sitios",))
        return [{"nombre": "freres"}]

    def crear_sitio(self, nombre, url, plataforma="tiendanube"):
        self.calls.append(("crear_sitio", nombre, url, plataforma))
        return {"nombre": nombre}

    def eliminar_sitio(self, nombre):
        self.calls.append(("eliminar_sitio", nombre))
        return {"ok": True}


class _FakeProcesses:
    def __init__(self) -> None:
        self.backend_launched = False
        self.frontend_launched = False
        self.shutdown_called = False
        self.backend_env = None
        self.frontend_env = None

    def launch_backend(self, cfg, database_password, env=None):
        self.backend_launched = True
        self.backend_env = env

    def launch_frontend(self, cfg, env=None):
        self.frontend_launched = True
        self.frontend_env = env

    def shutdown_all(self):
        self.shutdown_called = True


def _make_runner(tmp_path, in_text: str) -> tuple[PlainRunner, _FakeRest, _FakeProcesses, io.StringIO]:
    rest = _FakeRest()
    processes = _FakeProcesses()
    out = io.StringIO()
    in_ = io.StringIO(in_text)
    r = PlainRunner(_cfg(tmp_path), rest=rest, processes=processes, out=out, in_=in_)
    return r, rest, processes, out


# -- module-level import guard --------------------------------------------


def test_module_has_zero_textual_or_rich_imports():
    source = inspect.getsource(runner_module)
    assert "import textual" not in source
    assert "from textual" not in source
    assert "import rich" not in source
    assert "from rich" not in source


# -- action wiring ----------------------------------------------------------


def test_status_action_calls_rest_status(tmp_path):
    r, rest, _, out = _make_runner(tmp_path, "")
    assert r.dispatch("status") is True
    assert rest.calls == [("status",)]
    assert "ocioso" in out.getvalue()


def test_scrape_action_calls_rest_scrape(tmp_path):
    r, rest, _, _ = _make_runner(tmp_path, "")
    r.dispatch("scrape")
    assert rest.calls == [("scrape",)]


def test_retrain_action_calls_rest_entrenar(tmp_path):
    r, rest, _, _ = _make_runner(tmp_path, "")
    r.dispatch("retrain")
    assert rest.calls == [("entrenar",)]


def test_sites_action_calls_rest_listar_sitios(tmp_path):
    r, rest, _, _ = _make_runner(tmp_path, "")
    r.dispatch("sites")
    assert rest.calls == [("listar_sitios",)]


def test_add_site_action_calls_rest_crear_sitio_with_hostile_input(tmp_path):
    r, rest, _, _ = _make_runner(tmp_path, "")
    r.dispatch('add-site a"b;$(x) http://example.com tiendanube')
    assert rest.calls == [("crear_sitio", 'a"b;$(x)', "http://example.com", "tiendanube")]


def test_del_site_action_calls_rest_eliminar_sitio(tmp_path):
    r, rest, _, _ = _make_runner(tmp_path, "")
    r.dispatch("del-site freres")
    assert rest.calls == [("eliminar_sitio", "freres")]


def test_open_action_calls_opener(tmp_path):
    opened = []
    r, _, _, _ = _make_runner(tmp_path, "")
    r.rest = _FakeRest()
    from cli.plain.runner import cmd_open_dashboard

    result = cmd_open_dashboard(r.cfg, opener=lambda url: opened.append(url))
    assert opened == [f"http://localhost:{r.cfg.ports.frontend}"]
    assert "Opened" in result


def _mark_built(tmp_path):
    (tmp_path / "scraper").mkdir(exist_ok=True)
    (tmp_path / "scraper" / "scraper.jar").write_text("jar", encoding="utf-8")
    (tmp_path / "frontend").mkdir(exist_ok=True)
    (tmp_path / "frontend" / "dist").mkdir(exist_ok=True)


def test_start_action_launches_backend_and_frontend(tmp_path):
    # a real .env so we can assert its values reach the backend launch —
    # the backend fails fast without DATABASE_URL, so this propagation is
    # the "Start doesn't bring the backend up" regression guard.
    _mark_built(tmp_path)  # already built -> no auto-build, just launch
    (tmp_path / ".env").write_text(
        "DATABASE_URL=jdbc:postgresql://localhost:5432/scraper\nDATABASE_PASSWORD=\n",
        encoding="utf-8",
    )
    r, _, processes, _ = _make_runner(tmp_path, "")
    r.dispatch("start")
    assert processes.backend_launched is True
    assert processes.frontend_launched is True
    assert processes.backend_env["DATABASE_URL"] == "jdbc:postgresql://localhost:5432/scraper"
    assert processes.frontend_env == processes.backend_env


def test_start_auto_builds_when_not_built(tmp_path, monkeypatch):
    """Start compiles first when the jar/frontend build is missing, then
    launches — the user shouldn't have to remember to Build first."""
    calls = {"build": 0}

    def fake_build(cfg, *a, **k):
        calls["build"] += 1

    monkeypatch.setattr(runner_module, "build_project", fake_build)
    r, _, processes, out = _make_runner(tmp_path, "")  # nothing built in tmp_path
    r.dispatch("start")

    assert calls["build"] == 1
    assert "compilando primero" in out.getvalue()
    assert processes.backend_launched is True


def test_start_does_not_rebuild_when_already_built(tmp_path, monkeypatch):
    _mark_built(tmp_path)
    calls = {"build": 0}
    monkeypatch.setattr(runner_module, "build_project", lambda *a, **k: calls.__setitem__("build", calls["build"] + 1))
    r, _, processes, _ = _make_runner(tmp_path, "")
    r.dispatch("start")

    assert calls["build"] == 0
    assert processes.backend_launched is True


def test_unknown_command_does_not_raise(tmp_path):
    r, _, _, out = _make_runner(tmp_path, "")
    assert r.dispatch("bogus-command") is True
    assert "Unknown command" in out.getvalue()


def test_quit_command_stops_dispatch_loop(tmp_path):
    r, _, _, _ = _make_runner(tmp_path, "")
    assert r.dispatch("q") is False


def test_rest_error_is_caught_and_printed_not_raised(tmp_path):
    from cli.core.errors import RestError

    class _FailingRest(_FakeRest):
        def status(self):
            raise RestError("boom", action="check backend")

    r = PlainRunner(_cfg(tmp_path), rest=_FailingRest(), processes=_FakeProcesses(), out=io.StringIO(), in_=io.StringIO(""))
    assert r.dispatch("status") is True
    assert "boom" in r.out.getvalue()


# -- run() loop / non-tty stdin safety --------------------------------------


def test_run_on_empty_piped_stdin_returns_immediately_without_hanging(tmp_path):
    """Non-tty/piped stdin yields EOF on the first readline() -- the loop
    must exit cleanly instead of blocking, matching `input()`'s EOFError
    but without ever risking an uncaught exception."""
    r, _, processes, out = _make_runner(tmp_path, "")  # empty StringIO == immediate EOF
    rc = r.run()
    assert rc == 0
    assert processes.shutdown_called is True


def test_run_processes_commands_then_quits_on_q(tmp_path):
    r, rest, processes, out = _make_runner(tmp_path, "status\nq\n")
    rc = r.run()
    assert rc == 0
    assert rest.calls == [("status",)]
    assert processes.shutdown_called is True


def test_run_always_tears_down_even_if_dispatch_raises_unexpectedly(tmp_path, monkeypatch):
    r, _, processes, _ = _make_runner(tmp_path, "status\n")
    monkeypatch.setattr(r, "dispatch", lambda line: (_ for _ in ()).throw(RuntimeError("boom")))
    try:
        r.run()
    except RuntimeError:
        pass
    assert processes.shutdown_called is True


def test_module_level_run_forwards_force_env_flag(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(
        "cli.plain.runner.generate_env",
        lambda example_path, env_path, computed, force: calls.append(force),
    )
    (tmp_path / ".env.example").write_text("FOO=bar\n", encoding="utf-8")
    r, _, processes, out = None, None, None, None
    cfg = _cfg(tmp_path)
    rc = run(cfg, force_env=True, rest=_FakeRest(), processes=_FakeProcesses(), in_=io.StringIO(""), out=io.StringIO())
    assert rc == 0
    assert calls == [True]


# -- shared command registry -----------------------------------------------
#
# Both presenters read `core.commands`. These tests are what stops the plain
# runner from silently accepting a different vocabulary than the registry
# advertises — a menu that lists a verb dispatch does not handle is a lie.


def test_menu_is_rendered_from_the_shared_command_registry(tmp_path):
    from cli.core.commands import COMMANDS

    r, _, _, out = _make_runner(tmp_path, "")
    r._print(runner_module.MENU)
    printed = out.getvalue()
    for cmd in COMMANDS:
        assert cmd.name in printed


def test_every_registry_command_is_accepted_by_dispatch(tmp_path):
    """No verb may be advertised and then rejected as unknown."""
    from cli.core.commands import COMMANDS

    for cmd in COMMANDS:
        if cmd.name == "quit":
            continue  # covered separately: it returns False by design
        r, _, _, out = _make_runner(tmp_path, "")
        r.dispatch(cmd.name)
        assert "Unknown command" not in out.getvalue(), f"{cmd.name} is advertised but unhandled"


def test_aliases_dispatch_to_the_canonical_command(tmp_path):
    r, rest, _, _ = _make_runner(tmp_path, "")
    r.dispatch("st")
    assert rest.calls == [("status",)]


def test_help_prints_every_command(tmp_path):
    from cli.core.commands import COMMANDS

    r, _, _, out = _make_runner(tmp_path, "")
    r.dispatch("help")
    printed = out.getvalue()
    for cmd in COMMANDS:
        assert cmd.name in printed


def test_stop_tears_down_services_without_ending_the_loop(tmp_path):
    r, _, processes, _ = _make_runner(tmp_path, "")
    assert r.dispatch("stop") is True
    assert processes.shutdown_called is True


def test_logs_prints_the_service_log_tail(tmp_path):
    from cli.core.logs import service_log_path

    r, _, _, out = _make_runner(tmp_path, "")
    log = service_log_path(r.cfg, "backend")
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text("started on :3000\n", encoding="utf-8")
    r.dispatch("logs backend")
    assert "started on :3000" in out.getvalue()


def test_logs_on_a_missing_file_reports_instead_of_raising(tmp_path):
    r, _, _, out = _make_runner(tmp_path, "")
    assert r.dispatch("logs frontend") is True
    assert "sin log" in out.getvalue().lower()


def test_logs_rejects_an_unknown_service_name(tmp_path):
    r, _, _, out = _make_runner(tmp_path, "")
    assert r.dispatch("logs ../../etc/passwd") is True
    assert "unknown service" in out.getvalue().lower()


def test_a_command_missing_its_arguments_prints_usage(tmp_path):
    r, rest, _, out = _make_runner(tmp_path, "")
    r.dispatch("add-site solo-nombre")
    assert rest.calls == []
    assert "add-site <nombre> <url> [plataforma]" in out.getvalue()
