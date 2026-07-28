"""Tests for cli.core.processes — backend/frontend lifecycle & teardown
funnel (tasks.md 1.6.1-1.6.2)."""
from __future__ import annotations

import signal
import subprocess
from pathlib import Path

import pytest

from cli.core.config import Config, Ports, ToolchainPaths
from cli.core.errors import ProcessError
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


def _prep(tmp_path: Path) -> None:
    """Create the build artifacts the launch_* methods now require before
    they will start a process (jar for the backend, dist/ for the
    frontend). Idempotent."""
    (tmp_path / "scraper").mkdir(exist_ok=True)
    (tmp_path / "scraper" / "scraper.jar").write_text("jar", encoding="utf-8")
    (tmp_path / "frontend").mkdir(exist_ok=True)
    (tmp_path / "frontend" / "dist").mkdir(exist_ok=True)


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
    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    captured = {}

    def fake_popen_factory(cmd, *, cwd, **kwargs):
        captured["cmd"] = cmd
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=fake_popen_factory)
    mgr.launch_backend(cfg, database_password="")

    assert "-DDATABASE_PASSWORD=" in captured["cmd"]


def test_backend_launch_appends_nonempty_password_too(tmp_path: Path):
    _prep(tmp_path)
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
    _prep(tmp_path)
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
    _prep(tmp_path)
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
    _prep(tmp_path)
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
    _prep(tmp_path)
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


# -- env export + vendored toolchain + build pre-checks -----------------
# (the "Start doesn't bring the backend up" fix)


def test_backend_launch_exports_env_into_child_process(tmp_path: Path):
    """The backend fails fast without DATABASE_URL/USERNAME/CORS, so the
    parsed .env MUST reach the child's environment (merged over os.environ,
    not replacing it)."""
    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    captured: dict = {}

    def factory(cmd, *, cwd, **kwargs):
        captured.update(kwargs)
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=factory)
    mgr.launch_backend(
        cfg,
        database_password="",
        env={
            "DATABASE_URL": "jdbc:postgresql://localhost:5432/scraper",
            "APP_CORS_ALLOWED_ORIGINS": "http://localhost:5173",
        },
    )

    child_env = captured["env"]
    assert child_env["DATABASE_URL"] == "jdbc:postgresql://localhost:5432/scraper"
    assert child_env["APP_CORS_ALLOWED_ORIGINS"] == "http://localhost:5173"
    assert "PATH" in child_env  # merged over the ambient environment


def test_backend_uses_vendored_java_when_present(tmp_path: Path):
    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    java_dir = tmp_path / "_tools" / "jdk21" / "bin"
    java_dir.mkdir(parents=True)
    (java_dir / "java").write_text("#!/bin/sh\n", encoding="utf-8")
    captured: dict = {}

    def factory(cmd, *, cwd, **kwargs):
        captured["cmd"] = cmd
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=factory)
    mgr.launch_backend(cfg, database_password="")

    assert captured["cmd"][0] == str(java_dir / "java")


def test_backend_falls_back_to_path_java_when_vendored_absent(tmp_path: Path):
    _prep(tmp_path)  # no _tools/jdk21/bin/java
    cfg = _cfg(tmp_path)
    captured: dict = {}

    def factory(cmd, *, cwd, **kwargs):
        captured["cmd"] = cmd
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=factory)
    mgr.launch_backend(cfg, database_password="")

    assert captured["cmd"][0] == "java"


def test_backend_launch_raises_when_jar_missing(tmp_path: Path):
    (tmp_path / "scraper").mkdir()  # dir exists, jar does NOT
    cfg = _cfg(tmp_path)
    mgr = ProcessManager(is_windows=False, popen_factory=lambda *a, **k: None)

    with pytest.raises(ProcessError):
        mgr.launch_backend(cfg, database_password="")


def test_frontend_launch_exports_env_and_uses_npm(tmp_path: Path):
    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    captured: dict = {}

    def factory(cmd, *, cwd, **kwargs):
        captured["cmd"] = cmd
        captured.update(kwargs)
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=factory)
    mgr.launch_frontend(cfg, env={"VITE_API_BASE_URL": "http://localhost:3000"})

    assert captured["env"]["VITE_API_BASE_URL"] == "http://localhost:3000"
    assert captured["cmd"][0] == "npm"  # no vendored node -> PATH fallback
    assert captured["cmd"][1:3] == ["run", "preview"]


def test_frontend_launch_raises_when_dist_missing(tmp_path: Path):
    (tmp_path / "frontend").mkdir()  # dir exists, dist/ does NOT
    cfg = _cfg(tmp_path)
    mgr = ProcessManager(is_windows=False, popen_factory=lambda *a, **k: None)

    with pytest.raises(ProcessError):
        mgr.launch_frontend(cfg)
