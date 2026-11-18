"""Local operations: install, first-run setup, start/stop and diagnosis.

Everything else in this server talks to the REST API. These tools exist for the
moment there is no API yet — a fresh clone — so they work on the repository
next to this package and never need credentials. They run fixed commands only:
no tool argument reaches a shell, and no secret value is ever returned.
"""

from __future__ import annotations

import os
import platform
import re
import shutil
import socket
import subprocess
import threading
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx

from .client import ApiError

SERVICE_ACCOUNT_KEYS = ("CLI_SERVICE_ACCOUNT_USERNAME", "CLI_SERVICE_ACCOUNT_PASSWORD")
ADMIN_KEYS = ("ADMIN_BOOTSTRAP_USERNAME", "ADMIN_BOOTSTRAP_PASSWORD")
# The backend refuses to boot while any of these still holds an example value.
REQUIRED_SECRETS = ("AUTH_JWT_SECRET", "ADMIN_BOOTSTRAP_PASSWORD", "CLI_SERVICE_ACCOUNT_PASSWORD")
# Same rule as ar.scraper.security.Placeholders and cli/core/bootstrap.py (DOC-1).
_PLACEHOLDER_MARKERS = ("cambiame", "replace-me", "generado-por-el-instalador")

PORTS = {"postgres": 5432, "backend": 3000, "frontend": 5173}
USERNAME_RE = re.compile(r"^[A-Za-z0-9._@-]{1,64}$")
STEP_RE = re.compile(r"^\s*\[(\d/4|ML|CF|GF|OK|ERROR|AVISO)\]")
TAIL_LINES = 25


def default_repo_root() -> Path:
    override = os.environ.get("SCRAPPY_REPO_ROOT")
    return Path(override).resolve() if override else Path(__file__).resolve().parents[2]


def is_placeholder(value: str | None) -> bool:
    lowered = (value or "").strip().lower()
    return bool(lowered) and any(m in lowered for m in _PLACEHOLDER_MARKERS)


def read_env(path: Path) -> dict[str, str]:
    """`.env` as a flat dict, last occurrence wins — the same reading as the CLI."""
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        stripped = raw.strip()
        if stripped and not stripped.startswith("#") and "=" in raw:
            key, _, value = raw.partition("=")
            values[key.strip()] = value.strip()
    return values


def tail(path: Path, lines: int = TAIL_LINES) -> list[str]:
    if not path.is_file():
        return []
    return path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:]


def installer_command(system: str, root: Path) -> list[str]:
    if system == "Windows":
        return ["cmd", "/c", str(root / "INSTALAR_Y_CORRER.bat")]
    if system in ("Linux", "Darwin"):
        return ["bash", str(root / "Ejecutar_instalar.sh")]
    raise ApiError(0, f"sistema no soportado por los instaladores: {system}")


def cli_python(system: str, root: Path) -> Path:
    venv = root / "_tools" / "cli-venv"
    return venv / "Scripts" / "python.exe" if system == "Windows" else venv / "bin" / "python"


def resolve_credentials(environ: Mapping[str, str], root: Path) -> tuple[str, str, str]:
    """(user, password, source). Explicit variables win; otherwise the CLI's
    service account from `.env`, which the installer mints and the backend seeds."""
    user, password = environ.get("SCRAPPY_API_USER", ""), environ.get("SCRAPPY_API_PASSWORD", "")
    if user and password:
        return user, password, "SCRAPPY_API_USER/SCRAPPY_API_PASSWORD"
    env = read_env(root / ".env")
    user, password = (env.get(k, "") for k in SERVICE_ACCOUNT_KEYS)
    if user and password and not is_placeholder(password):
        return user, password, ".env (cuenta de servicio del CLI)"
    raise ApiError(0, "sin credenciales para la API: no hay SCRAPPY_API_USER/SCRAPPY_API_PASSWORD "
                      "ni cuenta de servicio en .env. Llamá a la tool `quickstart`.")


def port_open(port: int, host: str = "127.0.0.1") -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.5):
            return True
    except OSError:
        return False


@dataclass
class Job:
    """One background process with its log; at most one per kind at a time."""

    log: Path
    process: subprocess.Popen | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def running(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def state(self) -> dict[str, Any]:
        if self.process is None:
            return {"estado": "nunca corrió en esta sesión", "log": str(self.log), "tail": tail(self.log)}
        code = self.process.poll()
        if code is None:
            estado = "corriendo"
        elif code == 0:
            estado = "terminó OK"
        else:
            estado = "falló"
        return {"estado": estado, "exit_code": code, "log": str(self.log), "tail": tail(self.log)}


class LocalOps:
    def __init__(self, root: Path | None = None, system: str | None = None,
                 environ: Mapping[str, str] | None = None,
                 popen: Callable[..., subprocess.Popen] = subprocess.Popen,
                 run: Callable[..., subprocess.CompletedProcess] = subprocess.run,
                 which: Callable[[str], str | None] = shutil.which,
                 port_check: Callable[[int], bool] = port_open,
                 api_url: str | None = None,
                 http_transport: httpx.BaseTransport | None = None):
        self.root = root or default_repo_root()
        self.system = system or platform.system()
        self.environ = environ if environ is not None else os.environ
        self.popen, self.run, self.which, self.port_check = popen, run, which, port_check
        self.api_url = api_url or self.environ.get("SCRAPPY_API_URL") or "http://localhost:3000"
        self.http_transport = http_transport
        logs = self.root / "logs"
        self.install = Job(logs / "install-mcp.log")
        self.services = Job(self.root / "scraper" / "logs" / "cli-mcp.log")

    # ── Install ─────────────────────────────────────────────────────────

    def start_install(self) -> dict[str, Any]:
        with self.install._lock:
            if self.install.running():
                return {"iniciado": False, "motivo": "ya hay una instalación corriendo", **self.install.state()}
            cmd = installer_command(self.system, self.root)
            self.install.log.parent.mkdir(parents=True, exist_ok=True)
            env = {**self.environ, "SCRAPPY_INSTALL_ONLY": "1"}
            with open(self.install.log, "wb") as log:
                self.install.process = self.popen(cmd, cwd=self.root, env=env, stdin=subprocess.DEVNULL,
                                                  stdout=log, stderr=subprocess.STDOUT)
        return {"iniciado": True, "comando": cmd, "log": str(self.install.log),
                "siguiente": "Llamá a `estado_instalacion` hasta que termine (tarda varios minutos)."}

    def install_status(self) -> dict[str, Any]:
        state = self.install.state()
        pasos = [line.strip() for line in state["tail"] if STEP_RE.match(line)]
        state["paso_actual"] = pasos[-1] if pasos else None
        if state["estado"] == "terminó OK":
            state["siguiente"] = "Llamá a `configurar_admin`."
        elif state["estado"] == "falló":
            state["siguiente"] = "Leé `tail` (las líneas [ERROR]); `diagnosticar` dice qué falta."
        return state

    # ── First-run setup ─────────────────────────────────────────────────

    def configure_admin(self, usuario: str, password: str | None) -> dict[str, Any]:
        if not USERNAME_RE.match(usuario or ""):
            raise ApiError(0, "usuario inválido: letras, números y . _ @ - (sin espacios)")
        python = cli_python(self.system, self.root)
        if not python.exists():
            raise ApiError(0, f"falta {python}: corré `instalar` primero.")
        done = self.run([str(python), "-m", "cli", "--set-admin", usuario], cwd=self.root,
                        input=(password or "") + "\n", capture_output=True, text=True, timeout=120)
        if done.returncode != 0:
            raise ApiError(0, (done.stderr or done.stdout).strip())
        return {"ok": True, "detalle": done.stdout.strip(),
                "password": "generada y guardada en .env (ADMIN_BOOTSTRAP_PASSWORD); no se muestra acá"
                            if not password else "la indicada, guardada en .env",
                "siguiente": "Llamá a `levantar_servicios`."}

    # ── Services ────────────────────────────────────────────────────────

    def start_services(self, modo: str) -> dict[str, Any]:
        if modo not in ("local", "lan"):
            raise ApiError(0, "modo debe ser 'local' o 'lan'")
        with self.services._lock:
            if self.services.running():
                return {"iniciado": False, "motivo": "ya los levantó esta sesión", **self.services.state()}
            if self.port_check(PORTS["backend"]):
                return {"iniciado": False,
                        "motivo": f"ya hay algo escuchando en :{PORTS['backend']} (otro CLI o backend). "
                                  "Usá `diagnosticar`; si es otro CLI, bajalo desde ahí."}
            env = read_env(self.root / ".env")
            pendientes = [k for k in REQUIRED_SECRETS if not env.get(k) or is_placeholder(env[k])]
            if pendientes:
                raise ApiError(0, f".env sin configurar ({', '.join(pendientes)}): "
                                  "llamá a `configurar_admin` primero.")
            python = cli_python(self.system, self.root)
            if not python.exists():
                raise ApiError(0, f"falta {python}: corré `instalar` primero.")
            self.services.log.parent.mkdir(parents=True, exist_ok=True)
            with open(self.services.log, "wb") as log:
                self.services.process = self.popen(
                    [str(python), "-m", "cli", "--plain"], cwd=self.root,
                    env={**self.environ, "PYTHONUNBUFFERED": "1"},
                    stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
            self.services.process.stdin.write(f"start {modo}\n")
            self.services.process.stdin.flush()
        return {"iniciado": True, "log": str(self.services.log),
                "aviso": "Viven mientras viva este server MCP: al cerrar la sesión se bajan.",
                "siguiente": "La primera vez compila (varios minutos). Llamá a `diagnosticar` "
                             "hasta que el backend responda."}

    def stop_services(self) -> dict[str, Any]:
        with self.services._lock:
            if not self.services.running():
                return {"detenido": False, "motivo": "esta sesión no levantó servicios"}
            process = self.services.process
            try:
                process.stdin.write("quit\n")
                process.stdin.flush()
                process.wait(timeout=60)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
        return {"detenido": True, **self.services.state()}

    # ── Diagnosis ───────────────────────────────────────────────────────

    def _login(self, user: str, password: str) -> tuple[bool | None, str]:
        """True/False = the backend answered; None = it did not."""
        try:
            with httpx.Client(base_url=self.api_url, transport=self.http_transport, timeout=5) as http:
                response = http.post("/api/auth/login", json={"username": user, "password": password})
        except httpx.HTTPError as e:
            return None, f"sin respuesta de {self.api_url}: {e}"
        return response.status_code == 200, f"HTTP {response.status_code}"

    def _java_major(self) -> int | str | None:
        """The major version; a string when java exists but could not answer."""
        try:
            out = self.run(["java", "-version"], capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired:
            return "no respondió `java -version` en 60 s"
        except OSError as e:
            return f"no se pudo ejecutar: {e}"
        match = re.search(r'"(\d+)', (out.stderr or "") + (out.stdout or ""))
        return int(match.group(1)) if match else "salida de `java -version` irreconocible"

    def _toolchain(self) -> list[dict[str, Any]]:
        tools = self.root / "_tools"
        if self.system == "Windows":
            vendored = {"jdk21": "JDK 21", "maven": "Maven", "node": "Node", "pgsql": "PostgreSQL"}
            return [_check(f"toolchain: {name}", (tools / d).is_dir(), str(tools / d), "Llamá a `instalar`.")
                    for d, name in vendored.items()]
        brew = self.system == "Darwin"
        checks = []
        for cmd, pkg in (("mvn", "maven"), ("node", "node"), ("npm", "node"), ("python3", "python3")):
            hint = f"brew install {pkg}" if brew else f"Instalá '{pkg}' con tu gestor de paquetes"
            checks.append(_check(f"toolchain: {cmd}", bool(self.which(cmd)), self.which(cmd) or "no está en PATH", hint))
        major = self._java_major() if self.which("java") else None
        ok = isinstance(major, int) and major >= 21
        checks.append(_check("toolchain: java 21+", ok, f"java {major or 'ausente'}",
                             "brew install openjdk@21" if brew else "Instalá un JDK 21+"))
        pg = bool(self.which("pg_ctl") and self.which("initdb")) or bool(self.which("docker"))
        checks.append(_check("toolchain: postgres o docker", pg, "pg_ctl/initdb o docker",
                             "brew install postgresql@16 (o Docker)" if brew else "Instalá postgresql o Docker"))
        return checks

    def _env_checks(self, env_path: Path, env: dict[str, str]) -> list[dict[str, Any]]:
        checks = [_check(".env existe", env_path.is_file(), str(env_path), "Llamá a `configurar_admin`.")]
        if not env_path.is_file():
            return checks
        pendientes = [k for k in REQUIRED_SECRETS if not env.get(k) or is_placeholder(env[k])]
        checks.append(_check("secretos de .env configurados", not pendientes, ", ".join(pendientes) or "ok",
                             "Llamá a `configurar_admin` (genera la password del admin)."))
        faltan = sorted(set(read_env(self.root / ".env.example")) - set(env))
        checks.append(_check(".env tiene todas las claves de .env.example", not faltan, ", ".join(faltan) or "ok",
                             "Llamá a `configurar_admin`: reconcilia las que faltan."))
        return checks

    def _jar_check(self) -> dict[str, Any]:
        scraper = self.root / "scraper"
        jar, built = scraper / "scraper.jar", sorted((scraper / "target").glob("fashion-scraper-*.jar"))
        if not jar.is_file():
            return _check("jar del backend al día", False, "sin compilar", "`levantar_servicios` compila si falta.")
        if built and built[-1].stat().st_mtime > jar.stat().st_mtime:
            return _check("jar del backend al día", False, "desactualizado frente a scraper/target",
                          f"Copiá {built[-1].name} a scraper/scraper.jar")
        return _check("jar del backend al día", True, str(jar), "")

    def _login_checks(self, backend_up: bool, env: dict[str, str]) -> list[dict[str, Any]]:
        login_servicio, detalle = None, "backend apagado"
        if backend_up:
            try:
                user, password, source = resolve_credentials(self.environ, self.root)
                login_servicio, detalle = self._login(user, password)
                detalle = f"{source}: {detalle}"
            except ApiError as e:
                detalle = str(e)
        checks = [
            _check("backend responde en :3000", backend_up, self.api_url,
                   "Llamá a `levantar_servicios`; si ya lo hiciste, mirá `logs_locales`."),
            _check("el MCP puede loguearse", bool(login_servicio), detalle, _seeder_hint("la cuenta del MCP")),
        ]
        admin, password = env.get(ADMIN_KEYS[0], "admin"), env.get(ADMIN_KEYS[1], "")
        if backend_up and password and not is_placeholder(password):
            ok, det = self._login(admin, password)
            checks.append(_check(f"login del admin '{admin}' con la password de .env", bool(ok), det,
                                 _seeder_hint("el admin")))
        return checks

    def diagnose(self) -> dict[str, Any]:
        python = cli_python(self.system, self.root)
        env_path = self.root / ".env"
        env = read_env(env_path)
        puertos = {name: self.port_check(port) for name, port in PORTS.items()}

        checks = [
            *self._toolchain(),
            _check("uv + venv del CLI", python.exists(), str(python), "Llamá a `instalar`."),
            *self._env_checks(env_path, env),
            self._jar_check(),
            _check("postgres escucha en :5432", puertos["postgres"], "",
                   "Corré `instalar` de nuevo: arranca Postgres."),
            *self._login_checks(puertos["backend"], env),
        ]

        errores = [line for line in tail(self.root / "scraper" / "logs" / "error.log", 200)
                   if re.search(r"ERROR|\[AUTH\]|\[GUARD\]", line)][-10:]
        fallidos = [c for c in checks if not c["ok"]]
        return {"sistema": self.system, "raiz": str(self.root), "ok": not fallidos, "checks": checks,
                "a_resolver": [{"check": c["check"], "como": c["arreglo"]} for c in fallidos],
                "ultimos_errores_backend": errores,
                "instalacion": self.install_status() if self.install.process else None}

    def logs(self, servicio: str, lineas: int) -> dict[str, Any]:
        files = {"backend": self.root / "scraper" / "logs" / "backend.log",
                 "frontend": self.root / "scraper" / "logs" / "frontend.log",
                 "errores": self.root / "scraper" / "logs" / "error.log",
                 "cli": self.services.log, "instalacion": self.install.log}
        if servicio not in files:
            raise ApiError(0, f"servicio desconocido: {servicio} (opciones: {', '.join(files)})")
        return {"log": str(files[servicio]), "lineas": tail(files[servicio], max(1, min(lineas, 400)))}

    def quickstart(self) -> dict[str, Any]:
        d = self.diagnose()
        failed = {c["check"] for c in d["checks"] if not c["ok"]}

        def done(*prefixes: str) -> bool:
            return not any(f.startswith(p) for f in failed for p in prefixes)

        pasos = [
            ("1. Toolchain (Java 21, Maven, Node, Postgres, uv)", done("toolchain", "uv", "postgres"),
             "instalar → estado_instalacion hasta 'terminó OK'"),
            ("2. .env y credenciales del admin", done(".env", "secretos"),
             "configurar_admin (sin password: se genera y queda sólo en .env)"),
            ("3. Backend + frontend", done("backend", "jar"), "levantar_servicios → diagnosticar"),
            ("4. El MCP y el admin pueden loguearse", done("el MCP", "login del admin"),
             "diagnosticar; si falla, leé `a_resolver`"),
        ]
        siguiente = next((p for p in pasos if not p[1]), None)
        return {
            "pasos": [{"paso": p, "hecho": ok, "tools": tools} for p, ok, tools in pasos],
            "siguiente": f"{siguiente[0]} → {siguiente[2]}" if siguiente else
                         "Listo: usá `estado`, `buscar_productos`, etc. Dashboard en http://localhost:5173",
            "notas": [
                "Docker es otra vía: `docker compose up` (ver docs/MCP.md); estas tools cubren la nativa.",
                "La password del admin se lee en .env (ADMIN_BOOTSTRAP_PASSWORD); nunca la devuelve el MCP.",
                ("El seeder sólo crea cuentas que no existen: cambiar .env después del primer "
                 "arranque no cambia ninguna password."),
            ],
            "a_resolver": d["a_resolver"],
        }


def _check(name: str, ok: bool, detail: str, fix: str) -> dict[str, Any]:
    return {"check": name, "ok": ok, "detalle": detail, "arreglo": "" if ok else fix}


def _seeder_hint(who: str) -> str:
    return (f"Si el backend responde 401, {who} ya existía en la base con OTRA password: el seeder "
            "(AdminSeeder, ON CONFLICT DO NOTHING) nunca pisa una cuenta, así que cambiar .env no la "
            "actualiza. Volvé a poner en .env la password original, o cambiala desde el dashboard.")
