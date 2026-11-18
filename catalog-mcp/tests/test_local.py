import json
import os
import subprocess
from pathlib import Path

import httpx
import pytest
from mcp import Client

from scrappy_mcp.client import ApiClient, ApiError
from scrappy_mcp.local import (
    LocalOps,
    cli_python,
    installer_command,
    is_placeholder,
    resolve_credentials,
)
from scrappy_mcp.server import build_server

pytestmark = pytest.mark.anyio

GOOD_ENV = ("AUTH_JWT_SECRET=s3cr3t-real\nADMIN_BOOTSTRAP_USERNAME=admin\n"
            "ADMIN_BOOTSTRAP_PASSWORD=admin-pass-real\nCLI_SERVICE_ACCOUNT_USERNAME=cli-service\n"
            "CLI_SERVICE_ACCOUNT_PASSWORD=svc-pass-real\n")


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
def repo(tmp_path) -> Path:
    (tmp_path / ".env.example").write_text(
        "AUTH_JWT_SECRET=generado-por-el-instalador\nADMIN_BOOTSTRAP_USERNAME=admin\n"
        "ADMIN_BOOTSTRAP_PASSWORD=cambiame-por-una-password-real\n"
        "CLI_SERVICE_ACCOUNT_USERNAME=cli-service\nCLI_SERVICE_ACCOUNT_PASSWORD=replace-me-with-random-too\n",
        encoding="utf-8")
    return tmp_path


def installed(repo: Path, system="Linux") -> None:
    python = cli_python(system, repo)
    python.parent.mkdir(parents=True)
    python.write_text("")


class FakePopen:
    def __init__(self, calls, code=None):
        self.calls, self.code = calls, code

    def __call__(self, cmd, **kwargs):
        self.calls.append((cmd, kwargs))
        proc = self

        class Stdin:
            def write(self, text):
                proc.calls.append(("stdin", text))

            def flush(self):
                pass

        self.stdin = Stdin()
        return self

    def poll(self):
        return self.code

    def wait(self, timeout=None):
        self.code = 0
        return 0


def ops(repo, system="Linux", environ=None, popen=None, run=None, ports=(), login=None, which=None):
    def backend(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        ok = login and login.get(body["username"]) == body["password"]
        return httpx.Response(200 if ok else 401, json={"data": {"accessToken": "t"}})

    return LocalOps(root=repo, system=system, environ=environ or {},
                    popen=popen or FakePopen([]),
                    run=run or (lambda *a, **k: subprocess.CompletedProcess(a, 0, 'openjdk version "21.0.2"', "")),
                    which=which or (lambda cmd: f"/usr/bin/{cmd}"),
                    port_check=lambda port: port in ports,
                    http_transport=httpx.MockTransport(backend))


# ── Platform choice ─────────────────────────────────────────────────────

@pytest.mark.parametrize("system,expected", [
    ("Linux", ["bash", "Ejecutar_instalar.sh"]),
    ("Darwin", ["bash", "Ejecutar_instalar.sh"]),
    ("Windows", ["cmd", "/c", "INSTALAR_Y_CORRER.bat"]),
])
def test_each_os_runs_its_own_installer(repo, system, expected):
    cmd = installer_command(system, repo)
    assert cmd[:-1] == expected[:-1]
    assert Path(cmd[-1]) == repo / expected[-1]


def test_an_unknown_os_is_refused(repo):
    with pytest.raises(ApiError, match="FreeBSD"):
        installer_command("FreeBSD", repo)


def test_cli_venv_layout_differs_on_windows(repo):
    assert cli_python("Windows", repo).parts[-2:] == ("Scripts", "python.exe")
    assert cli_python("Darwin", repo).parts[-2:] == ("bin", "python")


# ── Install job ─────────────────────────────────────────────────────────

def test_install_runs_in_install_only_mode_without_stdin(repo):
    calls = []
    local = ops(repo, popen=FakePopen(calls))

    result = local.start_install()

    cmd, kwargs = calls[0]
    assert result["iniciado"]
    assert cmd[0] == "bash"
    assert kwargs["env"]["SCRAPPY_INSTALL_ONLY"] == "1"
    assert kwargs["stdin"] == subprocess.DEVNULL


def test_a_second_install_while_one_runs_is_refused(repo):
    calls = []
    local = ops(repo, popen=FakePopen(calls))
    local.start_install()

    assert local.start_install()["iniciado"] is False
    assert len(calls) == 1


def test_install_status_reports_the_current_step_and_next_tool(repo):
    local = ops(repo, popen=FakePopen([], code=0))
    local.start_install()
    local.install.log.write_text("[1/4] Verificando internet...\n       OK\n[4/4] uv...\n[OK] Toolchain listo\n")

    status = local.install_status()

    assert status["estado"] == "terminó OK"
    assert status["paso_actual"].startswith("[OK]")
    assert "configurar_admin" in status["siguiente"]


def test_a_failed_install_points_at_the_error_lines(repo):
    local = ops(repo, popen=FakePopen([], code=1))
    local.start_install()
    local.install.log.write_text("[2/4] toolchain\n  [ERROR] Falta 'mvn' en PATH.\n")

    status = local.install_status()

    assert status["estado"] == "falló"
    assert status["paso_actual"].strip().startswith("[ERROR]")


# ── Credentials ─────────────────────────────────────────────────────────

def test_explicit_variables_win_over_env_file(repo):
    (repo / ".env").write_text(GOOD_ENV)
    user, password, source = resolve_credentials({"SCRAPPY_API_USER": "bot", "SCRAPPY_API_PASSWORD": "pw"}, repo)
    assert (user, password) == ("bot", "pw")
    assert "SCRAPPY_API_USER" in source


def test_falls_back_to_the_cli_service_account(repo):
    (repo / ".env").write_text(GOOD_ENV)
    assert resolve_credentials({"SCRAPPY_API_USER": ""}, repo)[:2] == ("cli-service", "svc-pass-real")


def test_a_placeholder_service_password_is_not_a_credential(repo):
    (repo / ".env").write_text(GOOD_ENV.replace("svc-pass-real", "replace-me-with-random-too"))
    with pytest.raises(ApiError, match="quickstart"):
        resolve_credentials({}, repo)


def test_api_client_asks_for_credentials_only_at_login(repo):
    asked = []

    def credentials():
        asked.append(1)
        raise ApiError(0, "sin credenciales")

    api = ApiClient("http://api.test", credentials=credentials,
                    transport=httpx.MockTransport(lambda r: httpx.Response(200)))
    assert asked == []
    with pytest.raises(ApiError, match="sin credenciales"):
        api.request("GET", "/api/status")


@pytest.mark.parametrize("value", ["cambiame-por-una-password-real", "replace-me-x", "generado-por-el-instalador"])
def test_example_values_are_placeholders(value):
    assert is_placeholder(value)


# ── Admin setup ─────────────────────────────────────────────────────────

def test_configure_admin_passes_the_password_on_stdin_never_argv(repo):
    installed(repo)
    seen = {}

    def run(cmd, **kwargs):
        seen.update(cmd=cmd, input=kwargs["input"])
        return subprocess.CompletedProcess(cmd, 0, "escritas en .env", "")

    result = ops(repo, run=run).configure_admin("jefa", "una-password-larga")

    assert seen["cmd"][-2:] == ["--set-admin", "jefa"]
    assert "una-password-larga" not in " ".join(seen["cmd"])
    assert seen["input"] == "una-password-larga\n"
    assert "una-password-larga" not in json.dumps(result)


def test_configure_admin_without_password_never_returns_one(repo):
    installed(repo)
    run = lambda cmd, **kw: subprocess.CompletedProcess(cmd, 0, "ok", "")
    result = ops(repo, run=run).configure_admin("admin", None)
    assert "no se muestra" in result["password"]


@pytest.mark.parametrize("usuario", ["", "dos palabras", "a;rm -rf", "x" * 65])
def test_configure_admin_refuses_odd_usernames(repo, usuario):
    installed(repo)
    local = ops(repo)
    with pytest.raises(ApiError, match="usuario"):
        local.configure_admin(usuario, None)


def test_configure_admin_before_install_says_what_to_run(repo):
    local = ops(repo)
    with pytest.raises(ApiError, match="instalar"):
        local.configure_admin("admin", None)


def test_configure_admin_surfaces_the_cli_refusal(repo):
    installed(repo)
    run = lambda cmd, **kw: subprocess.CompletedProcess(cmd, 2, "", "Error: la password debe tener 12")
    local = ops(repo, run=run)
    with pytest.raises(ApiError, match="12"):
        local.configure_admin("admin", "corta")


# ── Services ────────────────────────────────────────────────────────────

def test_services_refuse_to_start_on_placeholder_secrets(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV.replace("admin-pass-real", "cambiame-por-una-password-real"))
    local = ops(repo)
    with pytest.raises(ApiError, match="ADMIN_BOOTSTRAP_PASSWORD"):
        local.start_services("local")


def test_services_refuse_when_the_backend_port_is_taken(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    assert ops(repo, ports={3000}).start_services("local")["iniciado"] is False


def test_services_start_the_plain_cli_and_type_start(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    calls = []

    result = ops(repo, popen=FakePopen(calls)).start_services("lan")

    assert result["iniciado"]
    assert calls[0][0][-3:] == ["-m", "cli", "--plain"]
    assert ("stdin", "start lan\n") in calls


def test_an_unknown_mode_is_refused(repo):
    local = ops(repo)
    with pytest.raises(ApiError, match="modo"):
        local.start_services("prod; rm -rf /")


def test_stop_quits_the_cli_it_started(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    calls = []
    local = ops(repo, popen=FakePopen(calls))
    local.start_services("local")

    assert local.stop_services()["detenido"]
    assert ("stdin", "quit\n") in calls


# ── Diagnosis and quickstart ────────────────────────────────────────────

def test_fresh_clone_quickstart_says_install_first(repo):
    q = ops(repo, which=lambda cmd: None).quickstart()
    assert q["siguiente"].startswith("1.")
    assert "instalar" in q["siguiente"]
    assert not q["pasos"][0]["hecho"]


def test_after_install_quickstart_asks_for_the_admin(repo):
    installed(repo)
    q = ops(repo, ports={5432}).quickstart()
    assert q["siguiente"].startswith("2.")
    assert "configurar_admin" in q["siguiente"]


def test_configured_but_stopped_quickstart_asks_to_start(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    q = ops(repo, ports={5432}).quickstart()
    assert q["siguiente"].startswith("3.")
    assert "levantar_servicios" in q["siguiente"]


def test_everything_up_quickstart_is_done(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    (repo / "scraper").mkdir()
    (repo / "scraper" / "scraper.jar").write_text("")
    local = ops(repo, ports={5432, 3000, 5173},
                login={"cli-service": "svc-pass-real", "admin": "admin-pass-real"})

    q = local.quickstart()

    assert all(p["hecho"] for p in q["pasos"]), q["a_resolver"]
    assert q["siguiente"].startswith("Listo")


def test_a_rejected_admin_login_explains_the_seeder_trap(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    local = ops(repo, ports={5432, 3000}, login={"cli-service": "svc-pass-real"})

    d = local.diagnose()

    admin = next(c for c in d["checks"] if c["check"].startswith("login del admin"))
    assert not admin["ok"]
    assert "ON CONFLICT DO NOTHING" in admin["arreglo"]


def test_diagnose_flags_a_stale_jar(repo):
    target = repo / "scraper" / "target"
    target.mkdir(parents=True)
    jar = repo / "scraper" / "scraper.jar"
    jar.write_text("")
    built = target / "fashion-scraper-1.1.0.jar"
    built.write_text("")
    os.utime(jar, (1, 1))

    check = next(c for c in ops(repo).diagnose()["checks"] if c["check"].startswith("jar"))

    assert not check["ok"]
    assert "fashion-scraper-1.1.0.jar" in check["arreglo"]


def test_diagnose_never_leaks_secret_values(repo):
    installed(repo)
    (repo / ".env").write_text(GOOD_ENV)
    text = json.dumps(ops(repo, ports={5432, 3000}).diagnose())
    for secret in ("s3cr3t-real", "admin-pass-real", "svc-pass-real"):
        assert secret not in text


def test_macos_hints_use_brew(repo):
    d = ops(repo, system="Darwin", which=lambda cmd: None).diagnose()
    java = next(c for c in d["checks"] if c["check"] == "toolchain: java 21+")
    assert "brew" in java["arreglo"]


def test_windows_checks_the_vendored_toolchain(repo):
    d = ops(repo, system="Windows").diagnose()
    names = {c["check"] for c in d["checks"]}
    assert "toolchain: JDK 21" in names
    assert "toolchain: java 21+" not in names


def test_logs_refuse_unknown_services(repo):
    local = ops(repo)
    with pytest.raises(ApiError, match="servicio"):
        local.logs("../../etc/passwd", 10)


# ── Through the MCP ─────────────────────────────────────────────────────

async def test_local_tools_are_registered_and_work_without_a_backend(repo):
    api = ApiClient("http://api.test", credentials=lambda: ("x", "y"),
                    transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    server = build_server(api, ops(repo, which=lambda cmd: None))

    async with Client(server) as c:
        names = {t.name for t in (await c.list_tools()).tools}
        result = await c.call_tool("quickstart", {})

    assert {"quickstart", "instalar", "estado_instalacion", "configurar_admin", "levantar_servicios",
            "bajar_servicios", "diagnosticar", "logs_locales"} <= names
    assert "instalar" in result.content[0].text


def test_a_slow_java_is_reported_as_slow_not_missing(repo):
    def run(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd, 60)

    java = next(c for c in ops(repo, run=run).diagnose()["checks"] if c["check"] == "toolchain: java 21+")
    assert not java["ok"]
    assert "no respondió" in java["detalle"]


def test_java_17_is_too_old(repo):
    run = lambda *a, **k: subprocess.CompletedProcess(a, 0, "", 'openjdk version "17.0.9"')
    java = next(c for c in ops(repo, run=run).diagnose()["checks"] if c["check"] == "toolchain: java 21+")
    assert not java["ok"]
    assert "17" in java["detalle"]
