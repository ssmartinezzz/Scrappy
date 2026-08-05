"""Textual `App.run_test()`/`Pilot` tests for the command console.

The console is command-driven, not menu-driven: there are no sidebar
buttons and no single-letter action keys. Every operation is reached by
typing its verb at the prompt, so these tests drive the real input widget
and submit it, which is exactly the path a user takes.

Requires `textual` to be importable — this whole module is skipped rather
than failing the suite when it is not installed.
"""
from __future__ import annotations

import pytest

textual = pytest.importorskip("textual", reason="textual not installed in this environment")

from cli.core.config import Config, Ports, ToolchainPaths  # noqa: E402
from cli.core.health import Check  # noqa: E402
from cli.tui.app import ScrappyConsole  # noqa: E402
from cli.tui.widgets import CommandSuggester, Console, HintBar, StatusBar  # noqa: E402


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
        self.shutdown_called = 0

    def launch_backend(self, cfg, database_password, env=None):
        self.backend_launched = True
        self.backend_env = env

    def launch_frontend(self, cfg, env=None):
        self.frontend_launched = True
        self.frontend_env = env

    def shutdown_all(self):
        self.shutdown_called += 1

    def alive(self):
        names = []
        if self.backend_launched:
            names.append("backend")
        if self.frontend_launched:
            names.append("frontend")
        return names


opened: list[str] = []


def _make_app(tmp_path, connect=lambda *a: False):
    rest = _FakeRest()
    processes = _FakeProcesses()
    app = ScrappyConsole(
        _cfg(tmp_path),
        rest=rest,
        processes=processes,
        opener=lambda url: opened.append(url),
        connect=connect,  # stubbed: health probes never touch a real port
    )
    return app, rest, processes


async def _settle(app) -> None:
    """Wait for the app's workers, tolerating a cancelled one.

    The health poll is `@work(exclusive=True, group="health")`, so a fresh
    tick deliberately cancels the in-flight one — and commands that finish
    by refreshing health (`start`, `stop`, `build`) trigger exactly that.
    `wait_for_complete` raises `WorkerCancelled` for it, which is the
    designed behavior surfacing as a test failure, not a defect. Swallowing
    it here is what makes these tests deterministic instead of timing-
    dependent."""
    from textual.worker import WorkerCancelled

    try:
        await app.workers.wait_for_complete()
    except WorkerCancelled:
        pass


async def _submit(app, pilot, line: str) -> None:
    """Type `line` at the prompt and press enter, then let the worker
    finish — the same sequence a user performs."""
    app.query_one("#prompt").value = line
    await pilot.press("enter")
    await _settle(app)
    await pilot.pause()


def _console_text(app) -> str:
    return "\n".join(app.query_one("#console", Console).history)


# -- command dispatch --------------------------------------------------


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "line,expected",
    [
        ("status", ("status",)),
        ("scrape", ("scrape",)),
        ("retrain", ("entrenar",)),
        ("sites", ("listar_sitios",)),
    ],
)
async def test_typing_a_verb_calls_the_matching_rest_method(tmp_path, line, expected):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, line)
    assert rest.calls == [expected]


@pytest.mark.asyncio
async def test_aliases_dispatch_to_the_canonical_command(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "st")
    assert rest.calls == [("status",)]


@pytest.mark.asyncio
async def test_add_site_takes_its_arguments_from_the_command_line(tmp_path):
    """Arguments come from the typed line, not from a form — and a hostile
    name is passed through as one opaque argument (`core.rest` json-encodes
    it; nothing here ever reaches a shell)."""
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, 'add-site a"b;$(x) http://example.com tiendanube')
    assert rest.calls == [("crear_sitio", 'a"b;$(x)', "http://example.com", "tiendanube")]


@pytest.mark.asyncio
async def test_add_site_defaults_the_platform_when_omitted(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "add-site freres http://freres.com")
    assert rest.calls == [("crear_sitio", "freres", "http://freres.com", "tiendanube")]


@pytest.mark.asyncio
async def test_del_site_passes_the_name_through(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "del-site freres")
    assert rest.calls == [("eliminar_sitio", "freres")]


@pytest.mark.asyncio
async def test_a_command_missing_its_arguments_reports_usage_and_calls_nothing(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "add-site solo-nombre")
        assert "add-site <nombre> <url> [plataforma]" in _console_text(app)
    assert rest.calls == []


@pytest.mark.asyncio
async def test_an_unknown_command_is_reported_without_crashing(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "definitely-not-a-command")
        assert app.is_running is True
        assert "definitely-not-a-command" in _console_text(app)
    assert rest.calls == []


@pytest.mark.asyncio
async def test_an_empty_line_is_ignored(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "   ")
    assert rest.calls == []


@pytest.mark.asyncio
async def test_the_prompt_is_cleared_after_submitting(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        assert app.query_one("#prompt").value == ""


@pytest.mark.asyncio
async def test_a_rest_failure_is_reported_in_the_console_and_never_crashes(tmp_path):
    from cli.core.errors import RestError

    app, rest, _ = _make_app(tmp_path)

    def boom():
        raise RestError("backend caído", action="Levantá el backend con `start`.")

    rest.status = boom
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        assert app.is_running is True
        text = _console_text(app)
    assert "backend caído" in text
    assert "Levantá el backend con `start`." in text


# -- worker discipline -------------------------------------------------


@pytest.mark.asyncio
async def test_commands_run_off_the_ui_thread(tmp_path):
    """A REST call or a multi-minute build must never run on the event
    loop, or rendering freezes. Spies on `run_worker` (what
    `@work(thread=True)` calls under the hood) because a fast fake can
    finish before a post-hoc check of `app.workers` would see it."""
    app, _, _ = _make_app(tmp_path)
    recorded: dict = {}
    original = app.run_worker

    def _spy(*args, **kwargs):
        recorded.update(kwargs)
        return original(*args, **kwargs)

    app.run_worker = _spy
    async with app.run_test() as pilot:
        await _submit(app, pilot, "scrape")
    assert recorded.get("thread") is True


# -- local commands ----------------------------------------------------


@pytest.mark.asyncio
async def test_open_uses_the_configured_frontend_port(tmp_path):
    opened.clear()
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "open")
    assert opened == [f"http://localhost:{app.cfg.ports.frontend}"]


@pytest.mark.asyncio
async def test_help_lists_every_command(tmp_path):
    from cli.core.commands import COMMANDS

    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "help")
        text = _console_text(app)
    for cmd in COMMANDS:
        assert cmd.name in text


@pytest.mark.asyncio
async def test_clear_empties_the_console(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        assert _console_text(app) != ""
        await _submit(app, pilot, "clear")
        assert _console_text(app) == ""


@pytest.mark.asyncio
async def test_stop_tears_down_the_services_without_exiting(tmp_path):
    app, _, processes = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "stop")
        assert processes.shutdown_called == 1
        assert app.is_running is True


@pytest.mark.asyncio
async def test_logs_renders_the_service_log_tail(tmp_path):
    from cli.core.logs import service_log_path

    app, _, _ = _make_app(tmp_path)
    log = service_log_path(app.cfg, "backend")
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text("started on :3000\nhit /api/status\n", encoding="utf-8")

    async with app.run_test() as pilot:
        await _submit(app, pilot, "logs backend")
        text = _console_text(app)
    assert "started on :3000" in text and "hit /api/status" in text


@pytest.mark.asyncio
async def test_logs_on_a_missing_file_says_so_instead_of_failing(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "logs frontend")
        assert app.is_running is True
        assert "sin log" in _console_text(app).lower()


@pytest.mark.asyncio
async def test_logs_rejects_an_unknown_service_name(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "logs ../../etc/passwd")
        assert app.is_running is True
        assert "unknown service" in _console_text(app).lower()


# -- teardown ----------------------------------------------------------


@pytest.mark.asyncio
@pytest.mark.parametrize("how", ["quit", "q", "exit"])
async def test_quit_verb_tears_down_processes_and_exits(tmp_path, how):
    app, _, processes = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, how)
    assert processes.shutdown_called == 1
    assert app.is_running is False


@pytest.mark.asyncio
async def test_ctrl_c_tears_down_processes_and_exits(tmp_path):
    """`ctrl+c` stays bound app-wide with priority — `Input` binds it to
    `copy` by default, and quitting must win over that."""
    app, _, processes = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("ctrl+c")
        await pilot.pause()
    assert processes.shutdown_called == 1
    assert app.is_running is False


@pytest.mark.asyncio
async def test_typing_q_is_not_swallowed_as_a_quit_key(tmp_path):
    """The old menu bound bare `q` to quit, which made the letter untypeable.
    In a command console `q` is just a character until you submit it."""
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        app.query_one("#prompt").focus()
        await pilot.press("q")
        await pilot.pause()
        assert app.is_running is True
        assert app.query_one("#prompt").value == "q"


# -- autocomplete ------------------------------------------------------


@pytest.mark.asyncio
async def test_suggester_completes_a_verb_prefix():
    suggester = CommandSuggester()
    assert await suggester.get_suggestion("sc") == "scrape"


@pytest.mark.asyncio
async def test_suggester_returns_none_for_an_unmatched_prefix():
    suggester = CommandSuggester()
    assert await suggester.get_suggestion("zzz") is None


@pytest.mark.asyncio
async def test_suggester_stops_suggesting_once_the_verb_is_complete():
    """After the first space the user is typing arguments; ghost-completing
    them against command names would be noise."""
    suggester = CommandSuggester()
    assert await suggester.get_suggestion("add-site fre") is None


@pytest.mark.asyncio
async def test_suggester_picks_the_first_match_alphabetically():
    suggester = CommandSuggester()
    assert await suggester.get_suggestion("st") == "start"


@pytest.mark.asyncio
async def test_tab_accepts_the_current_suggestion(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        prompt = app.query_one("#prompt")
        prompt.focus()
        await pilot.press("s", "c")
        await pilot.pause()
        await pilot.press("tab")
        await pilot.pause()
        assert prompt.value == "scrape"


@pytest.mark.asyncio
async def test_tab_on_an_unmatched_prefix_leaves_the_value_alone(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        prompt = app.query_one("#prompt")
        prompt.focus()
        await pilot.press("z", "z")
        await pilot.pause()
        await pilot.press("tab")
        await pilot.pause()
        assert prompt.value == "zz"


# -- history -----------------------------------------------------------


@pytest.mark.asyncio
async def test_up_arrow_recalls_the_previous_command(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        app.query_one("#prompt").focus()
        await pilot.press("up")
        await pilot.pause()
        assert app.query_one("#prompt").value == "status"


@pytest.mark.asyncio
async def test_up_twice_walks_further_back(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        await _submit(app, pilot, "sites")
        app.query_one("#prompt").focus()
        await pilot.press("up")
        await pilot.press("up")
        await pilot.pause()
        assert app.query_one("#prompt").value == "status"


@pytest.mark.asyncio
async def test_down_returns_towards_an_empty_prompt(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        app.query_one("#prompt").focus()
        await pilot.press("up")
        await pilot.press("down")
        await pilot.pause()
        assert app.query_one("#prompt").value == ""


@pytest.mark.asyncio
async def test_history_does_not_record_repeated_identical_commands(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        await _submit(app, pilot, "status")
        assert app.history == ["status"]


# -- status bar --------------------------------------------------------


def test_status_bar_line_is_a_single_row():
    """The whole point of the redesign: health is one line, not a bordered
    panel, so the console fits a small terminal."""
    line = StatusBar.line(
        [Check("Backend", True, ":3000"), Check("Postgres", False, ":5432")]
    )
    assert "\n" not in line


def test_status_bar_marks_up_and_down_services():
    line = StatusBar.line(
        [Check("Backend", True, ":3000"), Check("Postgres", False, ":5432")]
    )
    assert "[b green]●[/]" in line
    assert "[b red]○[/]" in line


def test_status_bar_uses_short_labels():
    """Long names ('frontend build') would wrap a narrow terminal."""
    line = StatusBar.line([Check("frontend build", True, "compilado", short="dist")])
    assert "dist" in line and "frontend build" not in line


def test_status_bar_falls_back_to_the_full_name_without_a_short_label():
    line = StatusBar.line([Check("Postgres", True, ":5432")])
    assert "Postgres" in line


@pytest.mark.asyncio
async def test_status_bar_populates_on_mount_from_the_connect_probe(tmp_path):
    def connect(host, port, timeout=0.35):
        return port == 3000  # only the backend is "up"

    app, _, _ = _make_app(tmp_path, connect=connect)
    async with app.run_test() as pilot:
        await app.workers.wait_for_complete()
        await pilot.pause()
        rendered = app.query_one("#statusbar", StatusBar).last_line
    assert "[b green]●[/] api" in rendered
    assert "[b red]○[/] pg" in rendered


# -- hint bar ----------------------------------------------------------


@pytest.mark.asyncio
async def test_hint_bar_shows_the_usage_of_the_verb_being_typed(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        app.query_one("#prompt").value = "add-site "
        await pilot.pause()
        assert "<nombre> <url>" in app.query_one("#hint", HintBar).text


@pytest.mark.asyncio
async def test_hint_bar_returns_to_the_default_on_an_unknown_verb(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        app.query_one("#prompt").value = "nonsense "
        await pilot.pause()
        assert "help" in app.query_one("#hint", HintBar).text


# -- console rendering -------------------------------------------------


def test_console_renders_payloads_as_plain_text_not_markup():
    """A payload is data, never markup: a `[red]` in a site name or a REST
    error must render literally instead of restyling the console."""
    text = Console.build_line("out", "[red]not a tag[/red]")
    assert text.plain.endswith("[red]not a tag[/red]")


def test_console_line_carries_a_timestamp():
    text = Console.build_line("out", "hello")
    assert text.plain[2] == ":" and text.plain[5] == ":"


def test_console_marks_errors_differently_from_output():
    assert Console.build_line("err", "x").plain != Console.build_line("out", "x").plain


# -- payload formatting ------------------------------------------------


def test_dict_payloads_render_as_indented_json_not_python_repr():
    """`{'estado': 'ocioso'}` is a Python repr leaking into the UI. JSON is
    what the API actually returned, and it reads in a narrow terminal."""
    from cli.tui.widgets import format_payload

    out = format_payload({"estado": "ocioso", "productos": 6692})
    assert "'estado'" not in out
    assert '"estado": "ocioso"' in out


def test_list_payloads_render_one_entry_per_line():
    from cli.tui.widgets import format_payload

    out = format_payload([{"nombre": "freres"}, {"nombre": "harvey"}])
    assert out.count("\n") >= 3


def test_payload_json_keeps_accents_readable():
    from cli.tui.widgets import format_payload

    assert "clásico" in format_payload({"n": "clásico"})


def test_plain_string_payloads_pass_through_untouched():
    from cli.tui.widgets import format_payload

    assert format_payload("build listo") == "build listo"


def test_unserialisable_payloads_fall_back_to_str_instead_of_raising():
    from cli.tui.widgets import format_payload

    assert format_payload({"o": object()}).startswith("{")


@pytest.mark.asyncio
async def test_the_console_shows_rest_results_as_json(tmp_path):
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        assert '"estado": "ocioso"' in _console_text(app)


def test_lists_render_one_compact_entry_per_line():
    """A fully indented list of 20 sites is 80 lines of scroll. One compact
    JSON object per line keeps a list scannable in a small terminal."""
    from cli.tui.widgets import format_payload

    out = format_payload([{"nombre": "freres", "url": "x"}, {"nombre": "harvey", "url": "y"}])
    lines = out.splitlines()
    assert lines[0] == "["
    assert lines[1].strip() == '{"nombre": "freres", "url": "x"},'
    assert lines[-1] == "]"


def test_an_empty_list_renders_on_one_line():
    from cli.tui.widgets import format_payload

    assert format_payload([]) == "[]"


def test_dicts_keep_the_indented_form():
    from cli.tui.widgets import format_payload

    assert format_payload({"a": 1, "b": 2}).splitlines()[1].startswith('  "a"')


@pytest.mark.asyncio
async def test_only_the_first_line_of_a_multi_line_result_is_timestamped(tmp_path):
    """Repeating the clock down the left edge of a JSON block is noise; the
    continuation lines align under it instead."""
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        lines = [ln for ln in app.query_one("#console", Console).history if '"estado"' in ln]
        assert lines, "expected the JSON body in the console"
        assert not lines[0].startswith(("0", "1", "2")), "continuation line was timestamped"


@pytest.mark.asyncio
async def test_the_first_line_of_a_result_still_carries_the_clock(tmp_path):
    """Targets the first line of the RESULT, not the echoed command.
    Indexing blindly into history would hit the echo, which is always
    stamped, and pass even if the multi-line stamping regressed."""
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "status")
        history = app.query_one("#console", Console).history
        body = next(i for i, ln in enumerate(history) if ln.rstrip().endswith("{"))
        assert history[body][2] == ":" and history[body][5] == ":"
        assert '"estado"' in history[body + 1]
        assert not history[body + 1].startswith(("0", "1", "2"))


# -- registry/handler coupling -----------------------------------------


@pytest.mark.asyncio
async def test_every_registry_command_has_a_handler_and_never_raises(tmp_path, monkeypatch):
    """The console routes by building a method name from the registry
    entry. A command added to `core.commands` without its `_cmd_*` method
    would otherwise raise an uncaught AttributeError into Textual's event
    loop — `dispatch` promises it never raises, so prove it."""
    from cli.core import builder as builder_mod
    from cli.core.commands import COMMANDS
    from cli.tui import app as app_mod

    # `build` and `start` are in the registry and would otherwise shell out
    # to a real npm/mvn build here, making the test slow and its worker
    # outlive the harness.
    monkeypatch.setattr(builder_mod, "build_project", lambda cfg, *a, **k: None)
    monkeypatch.setattr(builder_mod, "is_built", lambda cfg: True)
    monkeypatch.setattr(app_mod, "STARTUP_GRACE_SECONDS", 0)

    for cmd in COMMANDS:
        app, _, _ = _make_app(tmp_path)
        async with app.run_test() as pilot:
            app.dispatch(cmd.name)  # must not raise for any registered verb
            if cmd.name != "quit":
                # `quit` exits the app and tears its workers down by design.
                await _settle(app)
            await pilot.pause()


@pytest.mark.asyncio
async def test_a_registry_entry_with_no_handler_degrades_to_a_console_line(tmp_path, monkeypatch):
    """The failure mode itself, forced: an unhandled verb becomes an error
    line, not a crash."""
    from cli.core import commands as commands_mod

    orphan = commands_mod.Command("orphan-verb", "no handler on purpose")
    monkeypatch.setattr(commands_mod, "_BY_NAME", {**commands_mod._BY_NAME, "orphan-verb": orphan})

    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "orphan-verb")
        assert app.is_running is True
        assert "orphan-verb" in _console_text(app)


# -- build / start through the console ---------------------------------
#
# These two carry the most side effects (a multi-minute build, a service
# launch) and the rewrite dropped their old key-binding tests. Restored
# against the command path a user actually drives.


@pytest.mark.asyncio
async def test_build_runs_the_project_build(tmp_path, monkeypatch):
    from cli.core import builder as builder_mod

    calls = {"n": 0}
    monkeypatch.setattr(builder_mod, "build_project", lambda cfg, *a, **k: calls.__setitem__("n", calls["n"] + 1))

    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await _submit(app, pilot, "build")
    assert calls["n"] == 1


@pytest.mark.asyncio
async def test_start_auto_builds_when_nothing_is_compiled(tmp_path, monkeypatch):
    """Pressing start with no jar/dist compiles first — the user should not
    have to remember `build`."""
    from cli.core import builder as builder_mod

    calls = {"n": 0}
    monkeypatch.setattr(builder_mod, "build_project", lambda cfg, *a, **k: calls.__setitem__("n", calls["n"] + 1))

    app, _, processes = _make_app(tmp_path)  # nothing built in tmp_path
    async with app.run_test() as pilot:
        await _submit(app, pilot, "start")
    assert calls["n"] == 1
    assert processes.backend_launched is True
    assert processes.frontend_launched is True


@pytest.mark.asyncio
async def test_start_reports_a_service_that_died_immediately(tmp_path, monkeypatch):
    """Redirecting child output removed the user's only crash signal. If a
    service exits on boot (bad DATABASE_URL, port taken), say so instead of
    reporting success."""
    from cli.core import builder as builder_mod
    from cli.tui import app as app_mod

    monkeypatch.setattr(builder_mod, "build_project", lambda cfg, *a, **k: None)
    monkeypatch.setattr(builder_mod, "is_built", lambda cfg: True)
    monkeypatch.setattr(app_mod, "STARTUP_GRACE_SECONDS", 0)

    app, _, processes = _make_app(tmp_path)
    processes.alive = lambda: ["frontend"]  # backend died on boot
    async with app.run_test() as pilot:
        await _submit(app, pilot, "start")
        text = _console_text(app).lower()
    assert "backend" in text
    assert "logs" in text, "the user must be pointed at where the crash actually landed"


@pytest.mark.asyncio
async def test_start_reports_success_when_both_services_survive(tmp_path, monkeypatch):
    from cli.core import builder as builder_mod
    from cli.tui import app as app_mod

    monkeypatch.setattr(builder_mod, "build_project", lambda cfg, *a, **k: None)
    monkeypatch.setattr(builder_mod, "is_built", lambda cfg: True)
    monkeypatch.setattr(app_mod, "STARTUP_GRACE_SECONDS", 0)

    app, _, processes = _make_app(tmp_path)
    processes.alive = lambda: ["backend", "frontend"]
    async with app.run_test() as pilot:
        await _submit(app, pilot, "start")
        assert "arriba" in _console_text(app)
