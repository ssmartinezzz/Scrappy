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
from cli.core.grafana import GrafanaInfo
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

    def correo_prueba(self, destino=None):
        self.calls.append(("correo_prueba", destino))
        return {"ok": True, "mensaje": "Mail de prueba enviado a t**@example.com."}


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

    def alive(self):
        return ["backend", "frontend"]

    def shutdown_all(self):
        self.shutdown_called = True


class _FakeFlareSolverr:
    def __init__(self) -> None:
        self.stopped_with = []

    def stop_flaresolverr(self, cfg):
        self.stopped_with.append(cfg)


class _FakeLanProxy:
    def __init__(self) -> None:
        self.stopped_with = []

    def stop_proxy(self, cfg):
        self.stopped_with.append(cfg)


class _QuietFollower:
    """Default notice follower: tests that run `start` must not reach a real
    EventFollower, which would retry against a fake rest in the background."""

    def events(self):
        return iter(())

    def close(self):
        pass


def _make_runner(tmp_path, in_text: str, **services) -> tuple[PlainRunner, _FakeRest, _FakeProcesses, io.StringIO]:
    rest = _FakeRest()
    processes = _FakeProcesses()
    out = io.StringIO()
    in_ = io.StringIO(in_text)
    services.setdefault("notice_follower_factory", lambda rest: _QuietFollower())
    r = PlainRunner(_cfg(tmp_path), rest=rest, processes=processes, out=out, in_=in_, **services)
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


def test_quitting_stops_the_flaresolverr_the_installer_started(tmp_path):
    flaresolverr = _FakeFlareSolverr()
    r, _, _, _ = _make_runner(tmp_path, "q\n", flaresolverr=flaresolverr)
    r.run()
    assert flaresolverr.stopped_with == [r.cfg]


def test_quitting_stops_the_grafana_the_installer_started(tmp_path, fake_grafana):
    grafana = fake_grafana()
    r, _, _, _ = _make_runner(tmp_path, "q\n", grafana=grafana)
    r.run()
    assert grafana.stopped_with == [r.cfg]


def test_grafana_verb_prints_url_user_and_password(tmp_path, fake_grafana):
    info = GrafanaInfo(url="http://localhost:3001", user="admin", password="fake-pass")
    r, _, _, out = _make_runner(tmp_path, "", grafana=fake_grafana(info))
    r.dispatch("grafana")
    assert "Grafana: http://localhost:3001  usuario: admin  clave: fake-pass" in out.getvalue()


def test_grafana_verb_says_how_to_opt_in_when_not_activated(tmp_path, fake_grafana):
    r, _, _, out = _make_runner(tmp_path, "", grafana=fake_grafana(None))
    r.dispatch("grafana")
    assert "Grafana no está activado" in out.getvalue()


def test_the_menu_mentions_the_grafana_verb(tmp_path):
    r, _, _, out = _make_runner(tmp_path, "q\n")
    r.run()
    assert "grafana" in out.getvalue()


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


def test_stop_takes_the_lan_proxy_down_through_the_injected_service(tmp_path):
    lan_proxy = _FakeLanProxy()
    r, _, _, _ = _make_runner(tmp_path, "", lan_proxy=lan_proxy)
    r.dispatch("stop")
    assert lan_proxy.stopped_with == [r.cfg]


def test_start_hands_the_injected_lan_proxy_to_preflight_and_apply_mode(tmp_path, monkeypatch):
    from cli.core.runtime_config import Origins, Startup

    _mark_built(tmp_path)
    seen = {}
    monkeypatch.setattr(runner_module, "preflight", lambda mode, **kw: seen.update(preflight=kw["proxy"]))

    def fake_apply(cfg, mode, env, **kw):
        seen["apply_mode"] = kw["proxy"]
        return Startup(mode=mode, origins=Origins(frontend="http://x", backend="http://y"), ca=None)

    monkeypatch.setattr(runner_module, "apply_mode", fake_apply)
    lan_proxy = _FakeLanProxy()
    r, _, _, _ = _make_runner(tmp_path, "", lan_proxy=lan_proxy)

    r.dispatch("start lan")

    assert seen == {"preflight": lan_proxy, "apply_mode": lan_proxy}


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


def test_start_reports_a_service_that_died_immediately(tmp_path, monkeypatch):
    """Same honesty fix as the console: redirected stdio removed the user's
    only crash signal, so a dead child must be named, not reported as up."""
    monkeypatch.setattr("cli.plain.runner.is_built", lambda cfg: True)
    r, _, processes, out = _make_runner(tmp_path, "")
    processes.alive = lambda: ["frontend"]  # backend died on boot
    r.dispatch("start")
    printed = out.getvalue().lower()
    assert "backend" in printed
    assert "logs" in printed


def test_start_reports_success_when_both_services_survive(tmp_path, monkeypatch):
    monkeypatch.setattr("cli.plain.runner.is_built", lambda cfg: True)
    r, _, processes, out = _make_runner(tmp_path, "")
    processes.alive = lambda: ["backend", "frontend"]
    r.dispatch("start")
    assert "started" in out.getvalue().lower()


# -- LAN trust report + Docker pre-flight -----------------------------------


def test_start_lan_prints_the_trust_report_computed_not_transcribed(tmp_path, monkeypatch):
    from cli.core import lan_report
    from cli.core.lan_proxy import CaUrls
    from cli.core.runtime_config import Origins, Startup

    _mark_built(tmp_path)
    monkeypatch.setattr(runner_module, "preflight", lambda mode, **kw: None)
    fixed = Startup(
        mode="lan",
        origins=Origins(frontend="https://192.0.2.10:8443", backend="https://192.0.2.10:8444"),
        ca=CaUrls(
            ios="http://192.0.2.10:8081/scrappy-dev-ca.cer",
            android="http://192.0.2.10:8081/rootCA.pem",
        ),
    )
    monkeypatch.setattr(runner_module, "apply_mode", lambda cfg, mode, env, **kw: fixed)
    r, _, _, out = _make_runner(tmp_path, "")

    r.dispatch("start lan")

    assert lan_report.render(fixed) in out.getvalue()


def test_start_preflights_docker_before_any_build_work(tmp_path, monkeypatch):
    from cli.core.runtime_config import Origins, Startup

    order: list[str] = []
    monkeypatch.setattr(runner_module, "is_built", lambda cfg: False)
    monkeypatch.setattr(runner_module, "preflight", lambda mode, **kw: order.append("preflight"))
    monkeypatch.setattr(runner_module, "build_project", lambda cfg, *a, **k: order.append("build"))
    monkeypatch.setattr(
        runner_module,
        "apply_mode",
        lambda cfg, mode, env, **kw: Startup(
            mode=mode, origins=Origins(frontend="http://x", backend="http://y"), ca=None
        ),
    )
    r, _, _, _ = _make_runner(tmp_path, "")

    r.dispatch("start lan")

    assert order[:2] == ["preflight", "build"]


def test_start_local_prints_no_trust_report(tmp_path):
    _mark_built(tmp_path)
    r, _, _, out = _make_runner(tmp_path, "")

    r.dispatch("start")

    printed = out.getvalue()
    assert "docs/LAN_HTTPS_SETUP.md" not in printed
    assert "Ajustes" not in printed


# -- watch --------------------------------------------------------------------


class _FakeFollower:
    def __init__(self, *events, raises=None):
        self._events = list(events)
        self._raises = raises
        self.closed = False
        self.consumed = 0

    def events(self):
        for ev in self._events:
            self.consumed += 1
            yield ev
        if self._raises:
            raise self._raises

    def close(self):
        self.closed = True


def _watch_runner(tmp_path, status, follower):
    rest = _FakeRest()
    rest.status = lambda: (rest.calls.append(("status",)), status)[1]
    out = io.StringIO()
    runner = PlainRunner(
        _cfg(tmp_path), rest=rest, processes=_FakeProcesses(), out=out,
        in_=io.StringIO(""), follower_factory=lambda _rest: follower,
    )
    return runner, rest, out


def _ev(name, data):
    from cli.core.sse import StreamEvent

    return StreamEvent(name, data)


def _prog(*sitios):
    done = sum(1 for s in sitios if s["estado"] in ("done", "error"))
    return {"total": len(sitios), "completados": done, "productos": 0, "sitios": list(sitios)}


def _s(nombre, estado, count=0, durMs=0):
    return {"nombre": nombre, "estado": estado, "count": count, "durMs": durMs}


def test_watch_with_nothing_running_says_so_and_never_opens_the_stream(tmp_path):
    follower = _FakeFollower(_ev("scrape.status", {"status": "RUNNING", "mensaje": ""}))
    r, _, out = _watch_runner(tmp_path, {"status": "IDLE", "mensaje": "ocioso"}, follower)

    assert r.dispatch("watch") is True

    assert "No hay un scrape en curso" in out.getvalue()
    assert follower.consumed == 0


def test_watch_prints_each_change_once_and_ends_when_the_run_leaves_running(tmp_path):
    start = {"status": "RUNNING", "mensaje": "",
             "progreso": _prog(_s("a", "en_curso"), _s("b", "esperando"))}
    follower = _FakeFollower(
        _ev("scrape.progress", _prog(_s("a", "en_curso"), _s("b", "esperando"))),  # no change
        _ev("scrape.progress", _prog(_s("a", "done", 9, 2000), _s("b", "en_curso"))),
        _ev("scrape.status", {"status": "DONE", "mensaje": "fin"}),
        _ev("scrape.progress", _prog(_s("zzz", "done"))),  # after the end: never read
    )
    r, _, out = _watch_runner(tmp_path, start, follower)

    r.dispatch("w")

    text = out.getvalue()
    live = text.split("Scrape terminó")[0]
    assert live.count("en_curso") == 2  # a at start, b later; the repeat printed nothing
    assert "9" in text and "2.0s" in text
    assert "terminó" in text and "DONE" in text
    assert "zzz" not in text
    assert follower.closed is True


def test_watch_reports_reconnects(tmp_path):
    start = {"status": "RUNNING", "mensaje": "", "progreso": _prog(_s("a", "en_curso"))}
    follower = _FakeFollower(
        _ev("reconnecting", {"attempt": 2, "error": "boom"}),
        _ev("status", {"status": "DONE", "mensaje": ""}),
    )
    r, _, out = _watch_runner(tmp_path, start, follower)

    r.dispatch("watch")

    assert "reconectando" in out.getvalue() and "2" in out.getvalue()


def test_watch_ctrl_c_exits_cleanly_and_closes_the_stream(tmp_path):
    start = {"status": "RUNNING", "mensaje": "", "progreso": _prog(_s("a", "en_curso"))}
    follower = _FakeFollower(raises=KeyboardInterrupt())
    r, _, out = _watch_runner(tmp_path, start, follower)

    assert r.dispatch("watch") is True

    assert follower.closed is True
    assert "watch detenido" in out.getvalue()


def test_scrape_watch_triggers_the_scrape_then_follows_it(tmp_path):
    follower = _FakeFollower(_ev("scrape.status", {"status": "DONE", "mensaje": ""}))
    r, rest, out = _watch_runner(tmp_path, {"status": "RUNNING", "mensaje": ""}, follower)

    r.dispatch("scrape --watch")

    assert rest.calls[0] == ("scrape",)
    assert ("status",) in rest.calls
    assert "terminó" in out.getvalue()


# -- cloudflare notices ---------------------------------------------------------


class _NoticeFollower:
    def __init__(self, *events):
        import threading

        self._events = list(events)
        self.closed = threading.Event()
        self.seen = threading.Event()

    def events(self):
        yield from self._events
        self.seen.set()
        self.closed.wait(5)

    def close(self):
        self.closed.set()


def _notice_runner(tmp_path, follower):
    _mark_built(tmp_path)
    made = []

    def factory(_rest):
        made.append(follower)
        return follower

    r, _, _, out = _make_runner(tmp_path, "", notice_follower_factory=factory)
    return r, out, made


def test_start_logs_the_cloudflare_events_the_backend_reports(tmp_path):
    follower = _NoticeFollower(
        _ev("scrape.progress", {"total": 1}),
        _ev("scrape.cloudflare", {"sitio": "fullh4rd", "estado": "detectada",
                                  "detalle": "resolviendo via FlareSolverr"}),
    )
    r, out, _ = _notice_runner(tmp_path, follower)
    try:
        r.dispatch("start")
        assert follower.seen.wait(5)
    finally:
        r.dispatch("stop")
    printed = out.getvalue()
    assert "[Cloudflare] fullh4rd: detectada — resolviendo via FlareSolverr" in printed
    assert "total" not in printed


def test_start_twice_keeps_one_notice_follower(tmp_path):
    follower = _NoticeFollower()
    r, _, made = _notice_runner(tmp_path, follower)
    try:
        r.dispatch("start")
        r.dispatch("start")
        assert follower.seen.wait(5)
    finally:
        r.dispatch("stop")
    assert len(made) == 1


def test_stop_closes_the_notice_follower(tmp_path):
    follower = _NoticeFollower()
    r, _, _ = _notice_runner(tmp_path, follower)
    r.dispatch("start")
    assert follower.seen.wait(5)
    r.dispatch("stop")
    assert follower.closed.is_set()


def test_quitting_closes_the_notice_follower(tmp_path):
    follower = _NoticeFollower()
    r, _, _ = _notice_runner(tmp_path, follower)
    r.dispatch("start")
    assert follower.seen.wait(5)
    r.run()
    assert follower.closed.is_set()


# -- mail: SMTP setup and test mail -----------------------------------------

_ENV_BASE = "DATABASE_URL=jdbc:postgresql://localhost:5432/scraper\n# keep\nSMTP_HOST=\nSMTP_PORT=587\n"


def _write_env(tmp_path, text=_ENV_BASE):
    (tmp_path / ".env").write_text(text, encoding="utf-8")


def test_mail_setup_writes_the_smtp_keys_and_keeps_the_rest_of_the_env(tmp_path):
    from cli.core.env_file import parse_env

    _write_env(tmp_path)
    r, _, _, out = _make_runner(tmp_path, "1\ncuenta@gmail.com\nabcd efgh ijkl mnop\n\n",
                                secret_reader=lambda prompt: "abcd efgh ijkl mnop")

    r.dispatch("mail")

    env = parse_env(tmp_path / ".env")
    assert env["SMTP_HOST"] == "smtp.gmail.com" and env["SMTP_PORT"] == "587"
    assert env["SMTP_USERNAME"] == "cuenta@gmail.com"
    assert env["SMTP_PASSWORD"] == "abcdefghijklmnop"
    assert env["SMTP_FROM_ADDRESS"] == "cuenta@gmail.com"
    assert env["DATABASE_URL"] == "jdbc:postgresql://localhost:5432/scraper"
    assert "# keep" in (tmp_path / ".env").read_text(encoding="utf-8")
    assert "stop" in out.getvalue() and "mail test" in out.getvalue()


def test_mail_setup_reads_the_password_through_the_secret_reader_and_never_prints_it(tmp_path):
    _write_env(tmp_path)
    leidos = []

    def secret_reader(prompt):
        leidos.append(prompt)
        return "clave-super-secreta"

    r, _, _, out = _make_runner(tmp_path, "1\ncuenta@gmail.com\n\n", secret_reader=secret_reader)

    r.dispatch("mail")

    assert len(leidos) == 1 and "contraseña" in leidos[0].lower()
    assert "clave-super-secreta" not in out.getvalue()
    assert "clave-super-secreta" not in repr(r.in_.getvalue())


def test_mail_setup_explains_the_gmail_app_password_requirement(tmp_path):
    _write_env(tmp_path)
    r, _, _, out = _make_runner(tmp_path, "1\ncuenta@gmail.com\n", secret_reader=lambda p: p and "")

    r.dispatch("mail")

    assert "apppasswords" in out.getvalue()


def test_mail_setup_asks_again_after_an_invalid_answer(tmp_path):
    _write_env(tmp_path)
    r, _, _, out = _make_runner(tmp_path, "9\n1\ncuenta@gmail.com\n\n", secret_reader=lambda p: "clave")

    r.dispatch("mail")

    assert "Proveedor desconocido" in out.getvalue()
    assert "smtp.gmail.com" in (tmp_path / ".env").read_text(encoding="utf-8")


def test_mail_setup_cancelled_by_eof_leaves_the_env_untouched(tmp_path):
    _write_env(tmp_path)
    r, _, _, out = _make_runner(tmp_path, "1\n", secret_reader=lambda p: "x")

    r.dispatch("mail")

    assert (tmp_path / ".env").read_text(encoding="utf-8") == _ENV_BASE
    assert "cancelad" in out.getvalue().lower()


def test_mail_setup_sets_the_api_base_url_from_a_known_public_origin(tmp_path):
    from cli.core.env_file import parse_env

    _write_env(tmp_path, _ENV_BASE + "VITE_API_BASE_URL=https://192.168.1.50:3443\n")
    r, _, _, _ = _make_runner(tmp_path, "1\ncuenta@gmail.com\n\n", secret_reader=lambda p: "clave")

    r.dispatch("mail")

    assert parse_env(tmp_path / ".env")["NOTIFICACIONES_API_BASE_URL"] == "https://192.168.1.50:3443"


def test_mail_setup_leaves_the_api_base_url_alone_on_a_loopback_install(tmp_path):
    from cli.core.env_file import parse_env

    _write_env(tmp_path, _ENV_BASE + "VITE_API_BASE_URL=http://localhost:3000\n")
    r, _, _, _ = _make_runner(tmp_path, "1\ncuenta@gmail.com\n\n", secret_reader=lambda p: "clave")

    r.dispatch("mail")

    assert "NOTIFICACIONES_API_BASE_URL" not in parse_env(tmp_path / ".env")


def test_mail_test_asks_the_backend_to_send_to_the_given_address(tmp_path):
    r, rest, _, out = _make_runner(tmp_path, "")

    r.dispatch("mail test titular@example.com")

    assert rest.calls == [("correo_prueba", "titular@example.com")]
    assert "t**@example.com" in out.getvalue()


def test_mail_test_defaults_to_the_configured_sender(tmp_path):
    _write_env(tmp_path, _ENV_BASE + "SMTP_FROM_ADDRESS=avisos@example.com\n")
    r, rest, _, _ = _make_runner(tmp_path, "")

    r.dispatch("mail test")

    assert rest.calls == [("correo_prueba", "avisos@example.com")]


def test_mail_with_an_unknown_subcommand_prints_usage(tmp_path):
    r, rest, _, out = _make_runner(tmp_path, "")

    r.dispatch("mail frobnicate")

    assert rest.calls == [] and "mail [setup|test" in out.getvalue()


def test_mail_after_a_setup_reminds_that_the_backend_needs_a_restart(tmp_path):
    _write_env(tmp_path)
    r, _, _, out = _make_runner(tmp_path, "1\ncuenta@gmail.com\n\n", secret_reader=lambda p: "clave")

    r.dispatch("mail")

    assert "reinici" in out.getvalue().lower()


def test_mail_setup_ends_when_input_runs_out_at_the_password(tmp_path):
    _write_env(tmp_path)
    antes = (tmp_path / ".env").read_text(encoding="utf-8")
    r, _, _, out = _make_runner(tmp_path, "1\ncuenta@gmail.com\n")

    r.dispatch("mail")

    assert "cancelada" in out.getvalue()
    assert (tmp_path / ".env").read_text(encoding="utf-8") == antes


def test_mail_setup_gives_up_after_repeated_invalid_answers(tmp_path):
    _write_env(tmp_path)
    antes = (tmp_path / ".env").read_text(encoding="utf-8")
    r, _, _, out = _make_runner(tmp_path, "1\ncuenta@gmail.com\n", secret_reader=lambda prompt: "")

    r.dispatch("mail")

    assert "cancelada" in out.getvalue()
    assert (tmp_path / ".env").read_text(encoding="utf-8") == antes
