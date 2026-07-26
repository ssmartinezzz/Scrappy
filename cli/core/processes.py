"""Backend + frontend subprocess lifecycle: launch, track, and tear down
through a single funnel so neither process is ever left orphaned
(design.md §5, ported from `menu.ps1` D4/D5).
"""
from __future__ import annotations

import logging
import os
import platform
import signal
import subprocess
from dataclasses import dataclass, field
from typing import Callable, Optional

from cli.core.config import Config
from cli.core.errors import ProcessError

logger = logging.getLogger(__name__)

PopenFactory = Callable[..., "subprocess.Popen"]
TaskkillFn = Callable[[int], None]
KillpgFn = Callable[[int, int], None]


@dataclass
class ManagedProcess:
    name: str
    popen: "subprocess.Popen"


def _default_popen(cmd, *, cwd, **kwargs) -> "subprocess.Popen":
    return subprocess.Popen(cmd, cwd=str(cwd), **kwargs)


@dataclass
class ProcessManager:
    """Tracks the backend + frontend subprocesses and owns their teardown.

    `is_windows` and `popen_factory` are injection points so tests can
    exercise both the Windows (`taskkill /T /F`) and Linux
    (`killpg` SIGTERM -> SIGKILL) teardown paths from a single CI OS,
    without spawning real processes.
    """

    is_windows: bool = field(default_factory=lambda: platform.system() == "Windows")
    popen_factory: PopenFactory = _default_popen
    _procs: list[ManagedProcess] = field(default_factory=list)

    def launch_backend(
        self,
        cfg: Config,
        database_password: str,
        python_exe: Optional[str] = None,
        python_dir: Optional[str] = None,
    ) -> ManagedProcess:
        """Start `scraper/scraper.jar` on `cfg.ports.backend`.

        `-DDATABASE_PASSWORD=<value>` is ALWAYS appended, even when the
        password is an empty string. Windows deletes empty environment
        variables (both `set VAR=` and `.NET SetEnvironmentVariable('')`),
        so an empty trust-auth password would read as "missing" to the
        backend's `RequiredEnvVarsGuard` if passed as an env var. Passing
        it as a JVM system property instead is load-bearing — see
        design.md §5.3 / `menu.ps1:197-204`.
        """
        scraper_dir = cfg.repo_root / "scraper"
        log_dir = scraper_dir / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        err_log_path = log_dir / "backend-launcher.err.log"

        cmd = [
            "java",
            "-Xmx768m",
            "-Dfile.encoding=UTF-8",
            f"-DDATABASE_PASSWORD={database_password}",
        ]
        if python_exe:
            cmd.append(f"-DPYTHON_EXE={python_exe}")
        if python_dir:
            cmd.append(f"-DPYTHON_DIR={python_dir}")
        cmd += ["-jar", "scraper.jar"]

        err_log = open(err_log_path, "ab")
        try:
            popen = self.popen_factory(
                cmd, cwd=scraper_dir, stderr=err_log, **self._spawn_kwargs()
            )
        except Exception as exc:
            raise ProcessError(
                f"Failed to launch backend: {exc}",
                action=f"Check {err_log_path} and confirm scraper/scraper.jar exists.",
            ) from exc

        managed = ManagedProcess(name="backend", popen=popen)
        self._procs.append(managed)
        return managed

    def launch_frontend(self, cfg: Config) -> ManagedProcess:
        """Start `npm run preview -- --port <frontend port> --strictPort`
        on `cfg.ports.frontend`. `--strictPort` fails loudly on a port
        clash rather than silently drifting to Vite's default 4173."""
        frontend_dir = cfg.repo_root / "frontend"
        cmd = [
            "npm",
            "run",
            "preview",
            "--",
            "--port",
            str(cfg.ports.frontend),
            "--strictPort",
        ]
        try:
            popen = self.popen_factory(cmd, cwd=frontend_dir, **self._spawn_kwargs())
        except Exception as exc:
            raise ProcessError(
                f"Failed to launch frontend: {exc}",
                action="Confirm `npm install`/`npm run build` completed under frontend/.",
            ) from exc

        managed = ManagedProcess(name="frontend", popen=popen)
        self._procs.append(managed)
        return managed

    def _spawn_kwargs(self) -> dict:
        if self.is_windows:
            # subprocess.CREATE_NEW_PROCESS_GROUP only exists on Windows;
            # fall back to 0 so is_windows=True is still testable on Linux CI.
            return {"creationflags": getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)}
        return {"start_new_session": True}

    def shutdown_all(
        self,
        timeout: float = 5.0,
        taskkill: Optional[TaskkillFn] = None,
        killpg: Optional[KillpgFn] = None,
    ) -> None:
        """Tear down every tracked process, tolerating already-dead PIDs.

        Windows: `taskkill /PID <pid> /T /F` (whole tree — Vite preview
        spawns a child node). Linux: SIGTERM the process group, escalate
        to SIGKILL if it hasn't exited within `timeout`.
        """
        for managed in list(self._procs):
            pid = managed.popen.pid
            try:
                if self.is_windows:
                    self._teardown_windows(pid, taskkill)
                else:
                    self._teardown_posix(managed.popen, pid, timeout, killpg)
            except ProcessLookupError:
                pass  # already dead — tolerated
            except Exception:  # noqa: BLE001 - teardown must never crash the CLI
                logger.warning(
                    "Teardown of %s (pid=%s) hit a non-fatal error",
                    managed.name, pid, exc_info=True,
                )
        self._procs.clear()

    def _teardown_windows(self, pid: int, taskkill: Optional[TaskkillFn]) -> None:
        kill = taskkill or (
            lambda p: subprocess.run(["taskkill", "/PID", str(p), "/T", "/F"], check=False)
        )
        kill(pid)

    def _teardown_posix(
        self,
        popen: "subprocess.Popen",
        pid: int,
        timeout: float,
        killpg: Optional[KillpgFn],
    ) -> None:
        kill = killpg or (lambda p, sig: os.killpg(os.getpgid(p), sig))
        kill(pid, signal.SIGTERM)
        try:
            popen.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            kill(pid, signal.SIGKILL)
