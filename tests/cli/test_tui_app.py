"""Textual `App.run_test()`/`Pilot` tests (tasks 2.3.1 / 2.3.2).

Requires `textual` to be importable — this whole module is skipped rather
than failing the suite when it is not installed (e.g. a sandbox without
network access to pip-install it), per the apply-phase instructions:
report what ran vs. what's written-but-unrun, never claim green for tests
that didn't execute.
"""
from __future__ import annotations

import pytest

textual = pytest.importorskip("textual", reason="textual not installed in this environment")

from cli.core.config import Config, Ports, ToolchainPaths  # noqa: E402
from cli.core.health import Check  # noqa: E402
from cli.tui.app import FashionScraperApp  # noqa: E402
from cli.tui.widgets import HealthPanel, StatusPanel  # noqa: E402


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

    def launch_backend(self, cfg, database_password, env=None):
        self.backend_launched = True
        self.backend_env = env

    def launch_frontend(self, cfg, env=None):
        self.frontend_launched = True
        self.frontend_env = env

    def shutdown_all(self):
        self.shutdown_called = True


def _make_app(tmp_path, connect=lambda *a: False):
    rest = _FakeRest()
    processes = _FakeProcesses()
    app = FashionScraperApp(
        _cfg(tmp_path),
        rest=rest,
        processes=processes,
        opener=lambda url: opened.append(url),
        connect=connect,  # stubbed: health probes never touch a real port
    )
    return app, rest, processes


opened: list[str] = []


@pytest.mark.asyncio
async def test_status_key_binding_calls_rest_status_via_worker(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("t")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert rest.calls == [("status",)]


@pytest.mark.asyncio
async def test_scrape_key_binding_calls_rest_scrape(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("s")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert rest.calls == [("scrape",)]


@pytest.mark.asyncio
async def test_retrain_key_binding_calls_rest_entrenar(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("r")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert rest.calls == [("entrenar",)]


@pytest.mark.asyncio
async def test_list_sites_key_binding_calls_rest_listar_sitios(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("l")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert rest.calls == [("listar_sitios",)]


@pytest.mark.asyncio
async def test_add_site_key_binding_reads_input_fields_and_calls_rest(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        app.query_one("#site-nombre").value = 'a"b;$(x)'
        app.query_one("#site-url").value = "http://example.com"
        app.query_one("#site-plataforma").value = "tiendanube"
        await pilot.press("a")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert rest.calls == [("crear_sitio", 'a"b;$(x)', "http://example.com", "tiendanube")]


@pytest.mark.asyncio
async def test_delete_site_key_binding_calls_rest_eliminar_sitio(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        app.query_one("#site-nombre").value = "freres"
        await pilot.press("x")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert rest.calls == [("eliminar_sitio", "freres")]


@pytest.mark.asyncio
async def test_scrape_runs_off_the_ui_thread(tmp_path):
    """The worker backing the `s` binding must be a real thread worker,
    not a coroutine on the event loop -- this is what keeps a slow REST
    call from freezing rendering (design.md / task 2.3 instructions).

    Spies on `App.run_worker` (what `@work(thread=True)` calls under the
    hood -- verified against the installed textual==8.2.8 source) instead
    of inspecting `app.workers` after the fact, since a trivial fake REST
    call can finish and be reaped before a post-hoc check would see it.
    """
    app, rest, _ = _make_app(tmp_path)
    recorded_kwargs: dict = {}
    original_run_worker = app.run_worker

    def _spy_run_worker(*args, **kwargs):
        recorded_kwargs.update(kwargs)
        return original_run_worker(*args, **kwargs)

    app.run_worker = _spy_run_worker
    async with app.run_test() as pilot:
        await pilot.press("s")
        await app.workers.wait_for_complete()
        await pilot.pause()
    assert recorded_kwargs.get("thread") is True


@pytest.mark.asyncio
async def test_q_binding_tears_down_processes_and_exits(tmp_path):
    app, _, processes = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("q")
        await pilot.pause()
    assert processes.shutdown_called is True
    assert app.is_running is False


@pytest.mark.asyncio
async def test_ctrl_c_binding_tears_down_processes_and_exits(tmp_path):
    app, _, processes = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("ctrl+c")
        await pilot.pause()
    assert processes.shutdown_called is True
    assert app.is_running is False


@pytest.mark.asyncio
async def test_open_dashboard_key_binding_calls_opener(tmp_path):
    opened.clear()
    app, _, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("o")
        await pilot.pause()
    assert opened == [f"http://localhost:{app.cfg.ports.frontend}"]


@pytest.mark.asyncio
async def test_status_result_is_reflected_in_status_panel(tmp_path):
    app, rest, _ = _make_app(tmp_path)
    async with app.run_test() as pilot:
        await pilot.press("t")
        await app.workers.wait_for_complete()
        await pilot.pause()
        assert "ocioso" in app.query_one("#status", StatusPanel).status_text


def test_health_panel_rows_mark_up_and_down():
    """Pure row builder: an `ok` check gets the green ●, a down check the
    red ○ — no widget mounting required."""
    text = HealthPanel.rows([Check("Backend", True, ":3000"), Check("Postgres", False, ":5432")])
    assert "[b green]●[/]" in text and "Backend" in text
    assert "[b red]○[/]" in text and "Postgres" in text


@pytest.mark.asyncio
async def test_start_auto_builds_when_not_built(tmp_path, monkeypatch):
    """Pressing Start (u) with no jar/frontend build compiles first, then
    launches — the user shouldn't have to remember Build."""
    from cli.core import builder as builder_mod

    calls = {"build": 0}

    def fake_build(cfg, *a, **k):
        calls["build"] += 1

    monkeypatch.setattr(builder_mod, "build_project", fake_build)
    app, _, processes = _make_app(tmp_path)  # nothing built in tmp_path
    async with app.run_test() as pilot:
        await pilot.press("u")
        await app.workers.wait_for_complete()
        await pilot.pause()

    assert calls["build"] == 1
    assert processes.backend_launched is True


@pytest.mark.asyncio
async def test_health_panel_populates_on_mount_from_connect_probe(tmp_path):
    """On mount the health poll runs off-thread and fills the panel; the
    injected connect (backend port up, rest down) drives the markers."""

    def connect(host, port, timeout=0.35):
        return port == 3000  # only the backend is "up"

    app, _, _ = _make_app(tmp_path, connect=connect)
    async with app.run_test() as pilot:
        await app.workers.wait_for_complete()
        await pilot.pause()
        rendered = app.query_one("#health", HealthPanel).last_rows
    assert "Backend" in rendered and "Postgres" in rendered and "Frontend" in rendered
    assert "[b green]●[/] Backend" in rendered   # backend probe was up
    assert "[b red]○[/] Postgres" in rendered    # postgres probe was down
