"""Capability-detection routing tests (task 2.1.1 / 2.1.2).

These tests exercise `detect_interactive()`/`is_legacy_cmd_without_ansi()`
as pure functions and `main()`'s wiring via monkeypatched injection
points — none of it needs Textual installed (spec: native-cli-orchestration,
"Graceful TUI Degradation" is a *routing* concern decided once in
`cli/__main__.py`, per design.md §2.1).
"""
from __future__ import annotations

import sys

import pytest

from cli.__main__ import detect_interactive, is_legacy_cmd_without_ansi, main
from cli.core.config import Config, Ports, ToolchainPaths


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


# -- detect_interactive() ------------------------------------------------


def test_no_color_env_forces_plain():
    assert detect_interactive(
        argv=[], environ={"NO_COLOR": "1"}, stdout_isatty=True, stdin_isatty=True
    ) is False


def test_term_dumb_forces_plain():
    assert detect_interactive(
        argv=[], environ={"TERM": "dumb"}, stdout_isatty=True, stdin_isatty=True
    ) is False


def test_non_tty_stdout_forces_plain():
    assert detect_interactive(
        argv=[], environ={}, stdout_isatty=False, stdin_isatty=True
    ) is False


def test_non_tty_stdin_forces_plain():
    assert detect_interactive(
        argv=[], environ={}, stdout_isatty=True, stdin_isatty=False
    ) is False


def test_plain_flag_forces_plain():
    assert detect_interactive(
        argv=["--plain"], environ={}, stdout_isatty=True, stdin_isatty=True
    ) is False


def test_default_tty_environment_is_interactive():
    assert detect_interactive(
        argv=[], environ={}, stdout_isatty=True, stdin_isatty=True
    ) is True


# -- is_legacy_cmd_without_ansi() / task 2.1.2 ----------------------------


def test_legacy_cmd_exe_without_ansi_routes_to_plain():
    assert is_legacy_cmd_without_ansi(system="Windows", environ={}) is True
    assert detect_interactive(
        argv=[],
        environ={},
        stdout_isatty=True,
        stdin_isatty=True,
        system="Windows",
    ) is False


def test_windows_terminal_session_is_not_legacy():
    assert is_legacy_cmd_without_ansi(system="Windows", environ={"WT_SESSION": "abc"}) is False


def test_ansicon_console_is_not_legacy():
    assert is_legacy_cmd_without_ansi(system="Windows", environ={"ANSICON": "1"}) is False


def test_non_windows_is_never_legacy_cmd():
    assert is_legacy_cmd_without_ansi(system="Linux", environ={}) is False


# -- main() routing wiring ------------------------------------------------


def test_main_routes_to_tui_when_interactive(tmp_path, monkeypatch):
    """Requires Textual to be importable — `cli.tui.app` genuinely imports
    it. Skipped (not failed) when Textual isn't installed; the piped/
    plain-routing tests below cover the no-Textual environment."""
    pytest.importorskip("textual", reason="textual not installed in this environment")
    calls = []
    monkeypatch.setattr("cli.tui.app.run", lambda cfg, force_env=False: calls.append(("tui", cfg, force_env)) or 0)
    monkeypatch.setattr("cli.plain.runner.run", lambda cfg, force_env=False: calls.append(("plain", cfg, force_env)) or 0)

    cfg = _cfg(tmp_path)
    rc = main(
        argv=[],
        cfg=cfg,
        environ={},
        stdout_isatty=lambda: True,
        stdin_isatty=lambda: True,
    )
    assert rc == 0
    assert calls == [("tui", cfg, False)]


def test_main_routes_to_plain_when_piped(tmp_path, monkeypatch):
    """Deliberately does NOT patch/import `cli.tui.app` — a piped stdout
    must never trigger the Textual import at all, so this test also
    proves the routing works with Textual absent from the interpreter."""
    calls = []
    monkeypatch.setattr("cli.plain.runner.run", lambda cfg, force_env=False: calls.append(("plain", cfg, force_env)) or 0)

    cfg = _cfg(tmp_path)
    rc = main(
        argv=[],
        cfg=cfg,
        environ={},
        stdout_isatty=lambda: False,
        stdin_isatty=lambda: True,
    )
    assert rc == 0
    assert calls == [("plain", cfg, False)]


def test_main_routes_to_plain_when_no_color_set(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr("cli.plain.runner.run", lambda cfg, force_env=False: calls.append(cfg) or 0)

    cfg = _cfg(tmp_path)
    rc = main(
        argv=[],
        cfg=cfg,
        environ={"NO_COLOR": "1"},
        stdout_isatty=lambda: True,
        stdin_isatty=lambda: True,
    )
    assert rc == 0
    assert calls == [cfg]


def test_main_force_env_flag_is_forwarded(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr("cli.plain.runner.run", lambda cfg, force_env=False: calls.append(force_env) or 0)

    cfg = _cfg(tmp_path)
    main(
        argv=["--plain", "--regenerate"],
        cfg=cfg,
        environ={},
        stdout_isatty=lambda: True,
        stdin_isatty=lambda: True,
    )
    assert calls == [True]


def test_main_falls_back_to_plain_when_textual_not_importable(tmp_path, monkeypatch):
    """If Textual can't be imported at all, `main()` must not crash — it
    degrades to the plain runner instead (stronger than the spec strictly
    requires, but 'never crash' is the rule everywhere in this module)."""
    monkeypatch.delitem(sys.modules, "cli.tui.app", raising=False)
    monkeypatch.setitem(sys.modules, "textual", None)

    calls = []
    monkeypatch.setattr("cli.plain.runner.run", lambda cfg, force_env=False: calls.append(cfg) or 0)

    cfg = _cfg(tmp_path)
    rc = main(
        argv=[],
        cfg=cfg,
        environ={},
        stdout_isatty=lambda: True,
        stdin_isatty=lambda: True,
    )
    assert rc == 0
    assert calls == [cfg]
