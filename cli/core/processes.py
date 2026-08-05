"""Backend + frontend subprocess lifecycle: launch, track, and tear down
through a single funnel so neither process is ever left orphaned
(design.md §5, ported from `menu.ps1` D4/D5).

**Stdio containment.** Every child is launched with all three standard
streams bound: stdout+stderr to its own file under `scraper/logs/`, stdin
to DEVNULL. This is not tidiness — an inherited stdout is a rendering bug.
The CLI draws a full-screen Textual frame on the terminal; a child that
inherits it writes raw ANSI into the middle of that frame (Spring Boot's
banner, Vite's re-render + screen clear) and Textual has no way to know it
must repaint. The result is the shredded UI this redirection fixes. PIPE is
not an option either: nobody drains it, so the child blocks forever once
the pipe buffer fills.
"""
from __future__ import annotations

import logging
import os
import platform
import signal
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import IO, Callable, Optional

from cli.core.config import Config
from cli.core.errors import ProcessError
from cli.core.logs import log_dir, open_log, service_log_path

logger = logging.getLogger(__name__)

PopenFactory = Callable[..., "subprocess.Popen"]
TaskkillFn = Callable[[int], None]
KillpgFn = Callable[[int, int], None]


@dataclass
class ManagedProcess:
    """A launched child plus the log file it writes to. `log_file` is held
    so teardown can close it — the previous code opened a log per launch
    and never closed it, leaking one fd every time."""

    name: str
    popen: "subprocess.Popen"
    log_path: Optional[Path] = None
    log_file: Optional[IO[bytes]] = None


def _default_popen(cmd, *, cwd, **kwargs) -> "subprocess.Popen":
    return subprocess.Popen(cmd, cwd=str(cwd), **kwargs)


def _java_exe(cfg: Config) -> str:
    """The vendored JDK `java` if the installer provisioned it, else PATH
    `java`. Windows vendors its own JDK under `_tools/jdk21`; the POSIX
    installer assumes a system JDK (documented gap), so PATH is the
    fallback rather than a hard error."""
    exe = "java.exe" if platform.system() == "Windows" else "java"
    vendored = cfg.tools.jdk21 / "bin" / exe
    return str(vendored) if vendored.is_file() else "java"


def _npm_cmd(cfg: Config) -> str:
    """The vendored `npm` if present, else PATH `npm`. Node's Windows zip is
    flat (`npm.cmd`); the POSIX tarball nests binaries under `bin/`."""
    if platform.system() == "Windows":
        vendored = cfg.tools.node / "npm.cmd"
    else:
        vendored = cfg.tools.node / "bin" / "npm"
    return str(vendored) if vendored.is_file() else "npm"


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
        env: Optional[dict] = None,
        python_exe: Optional[str] = None,
        python_dir: Optional[str] = None,
    ) -> ManagedProcess:
        """Start `scraper/scraper.jar` on `cfg.ports.backend`.

        `env` is the parsed `.env` (DATABASE_URL / DATABASE_USERNAME /
        APP_CORS_ALLOWED_ORIGINS / …). It is merged over `os.environ` into
        the backend's process environment because the backend fails fast at
        startup (`RequiredEnvVarsGuard`) when those are absent — inheriting
        our own environment is NOT enough, since the CLI never loads `.env`
        into its own process (it only writes the file). Passing them here is
        the fix for "Start doesn't bring the backend up".

        `-DDATABASE_PASSWORD=<value>` is ALWAYS appended, even when the
        password is an empty string. Windows deletes empty environment
        variables (both `set VAR=` and `.NET SetEnvironmentVariable('')`),
        so an empty trust-auth password would read as "missing" to
        `RequiredEnvVarsGuard` if passed as an env var. Passing it as a JVM
        system property instead is load-bearing — see design.md §5.3 /
        `menu.ps1:197-204`.
        """
        scraper_dir = cfg.repo_root / "scraper"
        jar = scraper_dir / "scraper.jar"
        if not jar.is_file():
            raise ProcessError(
                "scraper/scraper.jar todavía no existe — el backend no se puede lanzar.",
                action="Corré `build` primero.",
            )
        log_path = service_log_path(cfg, "backend")

        cmd = [
            _java_exe(cfg),
            "-Xmx768m",
            "-Dfile.encoding=UTF-8",
            f"-DDATABASE_PASSWORD={database_password}",
        ]
        if python_exe:
            cmd.append(f"-DPYTHON_EXE={python_exe}")
        if python_dir:
            cmd.append(f"-DPYTHON_DIR={python_dir}")
        cmd += ["-jar", "scraper.jar"]

        child_env = {**os.environ, **(env or {})}

        log_file = self._open_service_log(cfg, "backend")
        try:
            popen = self.popen_factory(
                cmd,
                cwd=scraper_dir,
                env=child_env,
                **self._stdio_kwargs(log_file),
                **self._spawn_kwargs(),
            )
        except Exception as exc:
            log_file.close()
            raise ProcessError(
                f"Failed to launch backend: {exc}",
                action=f"Check {log_path} and confirm the vendored JDK + scraper.jar exist.",
            ) from exc

        managed = ManagedProcess(
            name="backend", popen=popen, log_path=log_path, log_file=log_file
        )
        self._procs.append(managed)
        return managed

    def launch_frontend(self, cfg: Config, env: Optional[dict] = None) -> ManagedProcess:
        """Start `npm run preview -- --port <frontend port> --strictPort`
        on `cfg.ports.frontend`. `--strictPort` fails loudly on a port
        clash rather than silently drifting to Vite's default 4173.

        `--clearScreen false` stops Vite from emitting a screen-clear +
        re-render; combined with the stdio redirection below it means the
        preview server cannot touch our terminal even in the odd case
        where it believes it has one.

        `env` (the parsed `.env`) is merged over `os.environ` for parity
        with the backend launch. Requires `frontend/dist` to exist —
        `npm run preview` only serves a prior `npm run build`."""
        frontend_dir = cfg.repo_root / "frontend"
        if not (frontend_dir / "dist").is_dir():
            raise ProcessError(
                "frontend/dist todavía no existe — el frontend no está buildeado.",
                action="Corré `build` primero; `npm run preview` sirve dist/.",
            )
        cmd = [
            _npm_cmd(cfg),
            "run",
            "preview",
            "--",
            "--port",
            str(cfg.ports.frontend),
            "--strictPort",
            "--clearScreen",
            "false",
        ]
        child_env = {**os.environ, **(env or {})}
        log_path = service_log_path(cfg, "frontend")
        log_file = self._open_service_log(cfg, "frontend")
        try:
            popen = self.popen_factory(
                cmd,
                cwd=frontend_dir,
                env=child_env,
                **self._stdio_kwargs(log_file),
                **self._spawn_kwargs(),
            )
        except Exception as exc:
            log_file.close()
            raise ProcessError(
                f"Failed to launch frontend: {exc}",
                action="Confirm the vendored Node + `npm run build` (frontend/dist) exist.",
            ) from exc

        managed = ManagedProcess(
            name="frontend", popen=popen, log_path=log_path, log_file=log_file
        )
        self._procs.append(managed)
        return managed

    @staticmethod
    def _open_service_log(cfg: Config, service: str) -> IO[bytes]:
        """Open the service log, translating a filesystem failure into the
        same guided `ProcessError` as every other launch failure — a
        read-only `scraper/logs/` should not surface as a raw `OSError`."""
        try:
            return open_log(cfg, service)
        except OSError as exc:
            raise ProcessError(
                f"No se pudo abrir el log de {service}: {exc}",
                action=f"Verificá permisos y espacio en {log_dir(cfg)}.",
            ) from exc

    @staticmethod
    def _stdio_kwargs(log_file: IO[bytes]) -> dict:
        """Bind all three streams away from the terminal. stdout and stderr
        share one file (interleaved, the way you'd read them on a console);
        stdin is DEVNULL so a child can never consume the keystrokes the
        user is typing at our prompt."""
        return {
            "stdout": log_file,
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
        }

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
            finally:
                # Always close, even when the kill failed: the fd is ours,
                # and leaking one per launch is how `start` twice runs out.
                if managed.log_file is not None:
                    try:
                        managed.log_file.close()
                    except Exception:  # noqa: BLE001
                        pass
        self._procs.clear()

    def alive(self) -> list[str]:
        """Names of the tracked processes that have not exited.

        Redirecting child stdio fixed the rendering bug but removed the only
        signal a user had that a service died on boot — its crash output
        used to land (destructively) on the terminal. The presenters call
        this right after `start` so an immediate death is reported instead
        of being reported as success.

        A child whose object exposes no `poll` is treated as running:
        absence of evidence of death is not evidence of death.
        """
        names = []
        for managed in self._procs:
            poll = getattr(managed.popen, "poll", None)
            if poll is None or poll() is None:
                names.append(managed.name)
        return names

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
