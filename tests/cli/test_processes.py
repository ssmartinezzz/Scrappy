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


# -- stdio containment -------------------------------------------------
#
# The TUI owns the terminal. A child that inherits our stdout/stderr writes
# raw ANSI over the rendered frame and shreds it. Every stream of every
# child must therefore be bound to a file (or DEVNULL) at launch — this is
# the regression suite for that.


def _capture_launch(tmp_path: Path, which: str) -> dict:
    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    captured: dict = {}

    def fake_popen_factory(cmd, *, cwd, **kwargs):
        captured.update(kwargs)
        captured["cmd"] = cmd
        return _FakePopen(cmd, cwd=cwd, **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=fake_popen_factory)
    if which == "backend":
        captured["managed"] = mgr.launch_backend(cfg, database_password="")
    else:
        captured["managed"] = mgr.launch_frontend(cfg)
    captured["mgr"] = mgr
    return captured


@pytest.mark.parametrize("which", ["backend", "frontend"])
def test_child_stdout_is_never_inherited_from_the_terminal(tmp_path: Path, which: str):
    """The bug this fixes: backend stdout (and the frontend's whole stdio)
    used to be inherited, so Spring Boot / Vite banners painted straight
    over the Textual frame."""
    captured = _capture_launch(tmp_path, which)
    stdout = captured.get("stdout")
    assert stdout is not None, f"{which} stdout was inherited from the parent terminal"
    assert stdout is not subprocess.PIPE, "an unread PIPE deadlocks once the child fills it"


@pytest.mark.parametrize("which", ["backend", "frontend"])
def test_child_stderr_is_never_inherited_from_the_terminal(tmp_path: Path, which: str):
    captured = _capture_launch(tmp_path, which)
    stderr = captured.get("stderr")
    assert stderr is not None, f"{which} stderr was inherited from the parent terminal"
    assert stderr is not subprocess.PIPE


@pytest.mark.parametrize("which", ["backend", "frontend"])
def test_child_stdin_is_devnull_so_it_can_never_steal_our_keystrokes(
    tmp_path: Path, which: str
):
    captured = _capture_launch(tmp_path, which)
    assert captured.get("stdin") == subprocess.DEVNULL


@pytest.mark.parametrize("which", ["backend", "frontend"])
def test_child_output_lands_in_its_own_service_log_file(tmp_path: Path, which: str):
    from cli.core.logs import service_log_path

    captured = _capture_launch(tmp_path, which)
    expected = service_log_path(_cfg(tmp_path), which)
    assert captured["managed"].log_path == expected
    assert expected.is_file(), "the log file should exist as soon as the child is launched"


@pytest.mark.parametrize("which", ["backend", "frontend"])
def test_teardown_closes_the_log_file_handle(tmp_path: Path, which: str):
    """The old code opened the stderr log and never closed it — one fd
    leaked per launch. Teardown owns the handle now."""
    captured = _capture_launch(tmp_path, which)
    handle = captured["stdout"]
    assert not handle.closed
    captured["mgr"].shutdown_all(killpg=lambda pid, sig: None)
    assert handle.closed


def test_frontend_preview_runs_without_a_tty_dependent_clear(tmp_path: Path):
    """`npm run preview` re-renders and clears the screen when it thinks it
    owns a TTY. `--clearScreen false` keeps it from trying even if a future
    change ever hands it one."""
    captured = _capture_launch(tmp_path, "frontend")
    assert "--clearScreen" in captured["cmd"]
    assert captured["cmd"][captured["cmd"].index("--clearScreen") + 1] == "false"


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


def test_a_real_child_writes_to_its_log_and_never_to_our_stdout(tmp_path: Path, capfd):
    """End-to-end regression for the bug this fixes.

    Everything above asserts on the kwargs handed to `Popen`. This one
    spawns a REAL process that spams stdout and stderr — exactly what
    Spring Boot and Vite do — and proves the output lands in the service
    log with not one byte reaching the terminal the console is drawing on.

    The popen_factory rewrites only the command; the stdio kwargs are the
    ones `launch_backend` actually produced, and `subprocess.Popen` is the
    real one.
    """
    import sys

    from cli.core.logs import service_log_path

    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    spam = (
        "import sys;"
        "sys.stdout.write('BANNER-ON-STDOUT\\n');"
        "sys.stderr.write('WARN-ON-STDERR\\n');"
        "sys.stdout.flush();sys.stderr.flush()"
    )

    def rewriting_factory(cmd, *, cwd, **kwargs):
        kwargs.pop("env", None)  # keep the child minimal; stdio is the subject
        return subprocess.Popen([sys.executable, "-c", spam], cwd=str(cwd), **kwargs)

    mgr = ProcessManager(is_windows=False, popen_factory=rewriting_factory)
    managed = mgr.launch_backend(cfg, database_password="")
    managed.popen.wait(timeout=30)
    mgr.shutdown_all(killpg=lambda pid, sig: None)

    written = service_log_path(cfg, "backend").read_text(encoding="utf-8")
    assert "BANNER-ON-STDOUT" in written
    assert "WARN-ON-STDERR" in written, "stderr must be merged into the same log"

    captured = capfd.readouterr()
    assert "BANNER-ON-STDOUT" not in captured.out
    assert "BANNER-ON-STDOUT" not in captured.err
    assert "WARN-ON-STDERR" not in captured.out
    assert "WARN-ON-STDERR" not in captured.err


def test_a_failing_log_open_is_reported_as_an_actionable_process_error(tmp_path: Path):
    """Opening the log sits on the launch path; a permissions or disk
    problem there must arrive as the same guided ProcessError as every
    other launch failure, not as a raw OSError."""
    from cli.core import processes as processes_mod

    _prep(tmp_path)
    cfg = _cfg(tmp_path)

    def boom(*a, **k):
        raise PermissionError("read-only filesystem")

    original = processes_mod.open_log
    processes_mod.open_log = boom
    try:
        mgr = ProcessManager(
            is_windows=False, popen_factory=lambda cmd, *, cwd, **kw: _FakePopen(cmd, cwd=cwd)
        )
        with pytest.raises(ProcessError) as excinfo:
            mgr.launch_backend(cfg, database_password="")
    finally:
        processes_mod.open_log = original

    assert excinfo.value.action, "a ProcessError on the launch path must carry an action"


def test_shutdown_all_twice_does_not_close_a_handle_twice_or_re_kill(tmp_path: Path):
    """`stop` followed by `quit` is an ordinary sequence in the console."""
    captured = _capture_launch(tmp_path, "backend")
    mgr = captured["mgr"]
    kills: list[int] = []
    mgr.shutdown_all(killpg=lambda pid, sig: kills.append(pid))
    mgr.shutdown_all(killpg=lambda pid, sig: kills.append(pid))
    assert kills == [4242], "the second teardown must be a no-op"
    assert captured["stdout"].closed


# -- launch liveness ---------------------------------------------------
#
# Redirecting child output fixed the rendering bug but removed the only
# signal a user had that a service died on boot: its crash used to land
# (destructively) on the terminal. `alive` restores an honest answer.


def test_alive_reports_a_running_child(tmp_path: Path):
    captured = _capture_launch(tmp_path, "backend")
    assert captured["mgr"].alive() == ["backend"]


def test_alive_omits_a_child_that_already_exited(tmp_path: Path):
    class _Exited(_FakePopen):
        def poll(self):
            return 1

    _prep(tmp_path)
    cfg = _cfg(tmp_path)
    mgr = ProcessManager(
        is_windows=False, popen_factory=lambda cmd, *, cwd, **kw: _Exited(cmd, cwd=cwd)
    )
    mgr.launch_backend(cfg, database_password="")
    assert mgr.alive() == []


def test_alive_treats_a_child_with_no_poll_as_running(tmp_path: Path):
    """_FakePopen has no poll(); absence of evidence of death is not
    evidence of death."""
    captured = _capture_launch(tmp_path, "frontend")
    assert captured["mgr"].alive() == ["frontend"]
