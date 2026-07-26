"""Tests for cli.core.processes — backend/frontend lifecycle & teardown
funnel (tasks.md 1.6.1-1.6.2)."""
from __future__ import annotations

import signal
import subprocess
from pathlib import Path

from cli.core.config import Config, Ports, ToolchainPaths
from cli.core.processes import ProcessManager


def _cfg(repo_root: Path) -> Config:
    tools = ToolchainPaths(
        jdk21=repo_root / "_tools" / "jdk21",
        maven=repo_root / "_tools" / "maven",
        node=repo_root / "_tools" / "node",
        cli_venv=repo_root / "_tools" / "cli-venv",
        pgsql=repo_root / "_tools" / "pgsql",
    )
    return Config(repo_root=repo_root, tools=tools, ports=Ports())


class _FakePopen:
    def __init__(self, cmd, cwd=None, **kwargs) -> None:
        self.cmd = cmd
        self.cwd = cwd
        self.kwargs = kwargs
        self.pid = 4242
        self.wait_calls = 0

    def wait(self, timeout=None):
        self.wait_calls += 1
        return 0


class _NeverExitsPopen(_FakePopen):
    def wait(self, timeout=None):
        raise subprocess.TimeoutExpired(cmd=self.cmd, timeout=timeout)


def test_backend_launch_always_appends_database_password_even_when_empty(tmp_path: Path):
    """1.6.1 — regression test for the Windows empty-env-var contract
    ported from menu.ps1:197-204."""
    (tmp_path / "scraper").mkdir()
    cfg = _cfg(tmp_path)
    captured = {}

    def fake_popen_factory(cmd, *, cwd, **kwargs):
        captured["cmd"] = cmd
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=fake_popen_factory)
    mgr.launch_backend(cfg, database_password="")

    assert "-DDATABASE_PASSWORD=" in captured["cmd"]


def test_backend_launch_appends_nonempty_password_too(tmp_path: Path):
    (tmp_path / "scraper").mkdir()
    cfg = _cfg(tmp_path)
    captured = {}

    def fake_popen_factory(cmd, *, cwd, **kwargs):
        captured["cmd"] = cmd
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=fake_popen_factory)
    mgr.launch_backend(cfg, database_password="s3cret")

    assert "-DDATABASE_PASSWORD=s3cret" in captured["cmd"]


def test_teardown_windows_path_calls_taskkill_tree_kill_for_every_tracked_pid(tmp_path: Path):
    """1.6.2 — Windows path: `taskkill /T /F` for every tracked PID."""
    (tmp_path / "scraper").mkdir()
    (tmp_path / "frontend").mkdir()
    cfg = _cfg(tmp_path)

    mgr = ProcessManager(
        is_windows=True,
        popen_factory=lambda cmd, *, cwd, **kw: _FakePopen(cmd, cwd=cwd),
    )
    mgr.launch_backend(cfg, database_password="")
    mgr.launch_frontend(cfg)

    killed_pids: list[int] = []
    mgr.shutdown_all(taskkill=lambda pid: killed_pids.append(pid))

    assert killed_pids == [4242, 4242]  # both tracked processes torn down
    assert mgr._procs == []


def test_teardown_posix_path_sigterm_then_sigkill_on_timeout(tmp_path: Path):
    """1.6.2 — Linux path: killpg SIGTERM -> escalate to SIGKILL on timeout."""
    (tmp_path / "scraper").mkdir()
    cfg = _cfg(tmp_path)

    mgr = ProcessManager(
        is_windows=False,
        popen_factory=lambda cmd, *, cwd, **kw: _NeverExitsPopen(cmd, cwd=cwd),
    )
    mgr.launch_backend(cfg, database_password="")

    signals_sent: list[tuple[int, int]] = []
    mgr.shutdown_all(killpg=lambda pid, sig: signals_sent.append((pid, sig)))

    assert signals_sent == [(4242, signal.SIGTERM), (4242, signal.SIGKILL)]


def test_teardown_posix_path_no_escalation_when_process_exits_cleanly(tmp_path: Path):
    (tmp_path / "scraper").mkdir()
    cfg = _cfg(tmp_path)

    mgr = ProcessManager(
        is_windows=False,
        popen_factory=lambda cmd, *, cwd, **kw: _FakePopen(cmd, cwd=cwd),
    )
    mgr.launch_backend(cfg, database_password="")

    signals_sent: list[tuple[int, int]] = []
    mgr.shutdown_all(killpg=lambda pid, sig: signals_sent.append((pid, sig)))

    assert signals_sent == [(4242, signal.SIGTERM)]  # no SIGKILL needed


def test_teardown_tolerates_already_dead_pid(tmp_path: Path):
    (tmp_path / "scraper").mkdir()
    cfg = _cfg(tmp_path)

    mgr = ProcessManager(
        is_windows=False,
        popen_factory=lambda cmd, *, cwd, **kw: _FakePopen(cmd, cwd=cwd),
    )
    mgr.launch_backend(cfg, database_password="")

    def dead_killpg(pid, sig):
        raise ProcessLookupError("already dead")

    mgr.shutdown_all(killpg=dead_killpg)  # must not raise

    assert mgr._procs == []
