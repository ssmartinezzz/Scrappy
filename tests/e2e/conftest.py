"""End-to-end API suite — fixtures, topology guard and test accounts.

**What this suite is.** ``frontend-auth-ui`` shipped with a Phase 8 that read
"manual verification against a real backend and browser (mandatory gate)". This
is layer 1 of its automated replacement: every check runs against a *live*
backend over real HTTP, because the reason Phase 8 was manual in the first
place is that the unit suites cannot see this layer. The previous change's
1540 green tests missed three real bugs — one of them an ``iat`` (seconds) vs
``password_changed_at`` (microseconds) comparison that 401'd the user who had
just changed their password — precisely because every one of them ran inside
the JVM against a fixed clock on an exact second boundary.

**The topology rule.** These tests assume, and assert, the cross-origin
topology this project actually ships:

    SPA http://localhost:5173  ──►  API http://localhost:3000

``vite dev`` proxies ``/api`` and makes the SPA *same-origin* with the backend.
That is the one topology this project never ships (Engram discovery #926:
portable/POSIX is ``:5173`` → ``:3000``, Docker is ``:8080`` → ``:3000``), and a
green run under it would prove nothing about ``Origin`` checking, ``SameSite``
or the bootstrap admission path — the exact blind spot that let a broken
``Sec-Fetch-Site: same-origin`` mechanism get recommended in the first place.
``_topologia_cruzada`` below fails the whole session, loudly, rather than
letting a same-origin run pass quietly.

**Conventions.** Follows ``tests/cli/``: plain pytest, stdlib only, fixtures in
this file, the repo root on ``sys.path``. Configuration comes from the
environment (``tests/e2e/run-e2e.sh`` exports all of it); nothing here holds a
credential, and in particular nothing here holds a password that also appears
in ``.env.example`` — ``AdminSeeder.PLACEHOLDER`` exists so the backend refuses
to seed with the example value, and hardcoding it would be a test that only
passes on a misconfigured install.
"""
from __future__ import annotations

import os
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tests.e2e._http import ApiClient, Respuesta  # noqa: E402  (after sys.path)

# The header a browser sends on a legitimate cross-origin-but-same-site
# request, which is what BOTH shipped installs produce. Never `same-origin`:
# that only happens under `vite dev`. See discovery #926.
SEC_FETCH_SITE_REAL = "same-site"


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "slow: takes seconds of real wall clock because the behaviour under test "
        "is itself timed (the refresh grace window). Not skippable — the branch "
        "it reaches has no other honest route.",
    )


# ── Configuration ───────────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def api_base_url() -> str:
    return os.environ.get("E2E_API_BASE_URL", "http://localhost:3000")


@pytest.fixture(scope="session")
def app_origin() -> str:
    """The SPA's origin — the value that must be in ``APP_CORS_ALLOWED_ORIGINS``."""
    return os.environ.get("E2E_APP_ORIGIN", "http://localhost:5173")


@pytest.fixture(scope="session")
def backend_log() -> Path:
    """Where ``ConsoleChannel`` writes the password-reset link."""
    ruta = os.environ.get("E2E_BACKEND_LOG")
    if ruta:
        return Path(ruta)
    return _REPO_ROOT / "scraper" / "logs" / "scraper.log"


@pytest.fixture(scope="session")
def bootstrap_headers(app_origin: str) -> dict[str, str]:
    """Exactly what a real cold page load sends, and nothing else.

    No ``X-Refresh-CSRF``: a page that has just loaded holds no nonce, which is
    the entire reason the bootstrap admission path exists.
    """
    return {"Origin": app_origin, "Sec-Fetch-Site": SEC_FETCH_SITE_REAL}


# ── The topology guard — fails loudly, never quietly ─────────────────────────

@pytest.fixture(scope="session", autouse=True)
def _topologia_cruzada(api_base_url: str, app_origin: str) -> None:
    if api_base_url.rstrip("/") == app_origin.rstrip("/"):
        pytest.fail(
            "SAME-ORIGIN TOPOLOGY DETECTED — this suite refuses to run.\n"
            f"  E2E_API_BASE_URL = {api_base_url}\n"
            f"  E2E_APP_ORIGIN   = {app_origin}\n"
            "These two must be different origins. Every installation this "
            "project ships is cross-origin (:5173 or :8080 -> :3000); only "
            "`vite dev`, with its /api proxy, is same-origin. A same-origin "
            "run cannot exercise Origin checking, SameSite, or the bootstrap "
            "admission path at all, so a green result here would mean nothing.",
            pytrace=False,
        )


@pytest.fixture(scope="session")
def api(api_base_url: str) -> ApiClient:
    cliente = ApiClient(api_base_url)
    try:
        vivo = cliente.get("/")
    except OSError as e:
        pytest.fail(
            f"no backend at {api_base_url} ({e}). Start one with "
            "tests/e2e/run-e2e.sh, or point E2E_API_BASE_URL at a running one.",
            pytrace=False,
        )
    assert vivo.status == 200, f"GET / answered {vivo.status}, expected the liveness 200"
    return cliente


# ── Sessions ────────────────────────────────────────────────────────────────

@dataclass
class Sesion:
    """One logged-in browser's worth of state, held the way a browser holds it.

    ``access_token`` is what a real client keeps in memory, ``nonce`` likewise,
    and ``refresh_token`` is the value the browser would keep in an ``HttpOnly``
    cookie and never be able to read. We can read it here — that is the
    difference between a test and a browser — but nothing in this suite ever
    puts it anywhere but a ``Cookie`` header.
    """

    username: str
    password: str
    access_token: str
    nonce: str | None
    refresh_token: str | None

    def cookie(self) -> dict[str, str]:
        assert self.refresh_token, f"{self.username} has no refresh cookie (service account?)"
        return {"refresh": self.refresh_token}


def login(api: ApiClient, username: str, password: str) -> Sesion:
    r = api.post("/api/auth/login", json_body={"username": username, "password": password})
    assert r.status == 200, f"login as {username!r} answered {r.status}: {r.body!r}"
    cuerpo = r.json()
    galleta = r.cookies.get("refresh")
    return Sesion(
        username=username,
        password=password,
        access_token=cuerpo["accessToken"],
        nonce=cuerpo.get("csrfNonce"),
        refresh_token=galleta.valor if galleta else None,
    )


@pytest.fixture(scope="session")
def admin(api: ApiClient) -> Sesion:
    usuario = os.environ.get("E2E_ADMIN_USERNAME")
    password = os.environ.get("E2E_ADMIN_PASSWORD")
    if not usuario or not password:
        pytest.fail(
            "E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD are unset. "
            "tests/e2e/run-e2e.sh exports both from its generated "
            "tests/e2e/.e2e-secrets.env; export them yourself to run pytest "
            "directly. They are deliberately not defaulted: a default would "
            "be a committed password.",
            pytrace=False,
        )
    sesion = login(api, usuario, password)
    yo = api.get("/api/auth/me", token=sesion.access_token)
    assert yo.status == 200, f"GET /api/auth/me as the admin answered {yo.status}"
    assert "ADMIN" in yo.json()["roles"], (
        f"{usuario!r} is not an ADMIN — it answered roles={yo.json()['roles']}. "
        "The runner seeds e2e-admin via ADMIN_BOOTSTRAP_USERNAME; if that "
        "username already existed with another role, AdminSeeder left it alone."
    )
    return sesion


# ── Disposable accounts, created through the real API ───────────────────────
#
# Created with POST /api/usuarios as ADMIN, never with SQL: the point is to
# exercise the shipped path, and a row inserted behind the endpoint's back
# would not prove the endpoint works. Cleaned up with DELETE
# /api/usuarios/{username}, which DEACTIVATES rather than deletes (see
# UsuarioAdminEndpoints' "Deactivate, never delete") — so every account is
# uniquely named per run and deactivated rows simply accumulate, inert.

def _sufijo() -> str:
    return uuid.uuid4().hex[:10]


@pytest.fixture
def crear_usuario(api: ApiClient, admin: Sesion):
    """Factory: makes accounts, deactivates every one of them afterwards."""
    creados: list[str] = []

    def _crear(rol: str = "VIEWER", *, con_email: bool = False, password: str | None = None):
        username = f"e2e-{rol.lower()}-{_sufijo()}"
        password = password or f"pw-{uuid.uuid4().hex}"
        cuerpo = {"username": username, "password": password, "role": rol}
        if con_email:
            cuerpo["email"] = f"{username}@e2e.invalid"
        r = api.post("/api/usuarios", json_body=cuerpo, token=admin.access_token)
        assert r.status == 201, f"POST /api/usuarios answered {r.status}: {r.body!r}"
        creados.append(username)
        return {"username": username, "password": password, "email": cuerpo.get("email")}

    yield _crear

    for username in creados:
        api.delete(f"/api/usuarios/{username}", token=admin.access_token)


@pytest.fixture
def viewer(api: ApiClient, crear_usuario) -> Sesion:
    cuenta = crear_usuario("VIEWER")
    # Log in immediately after creation on purpose. `password_changed_at` is
    # written with microsecond precision and a JWT's `iat` is whole seconds —
    # the pair that produced the "logged out the moment you change your
    # password" bug the unit suite could not see, because its clocks all landed
    # on exact second boundaries. Here the two genuinely disagree.
    return login(api, cuenta["username"], cuenta["password"])


# ── Small helpers shared by more than one module ────────────────────────────

def esperar(condicion, *, timeout: float = 5.0, intervalo: float = 0.1):
    """Poll ``condicion`` until it returns something truthy, or give up.

    Used only where the backend genuinely does work off the request thread —
    ``PasswordResetService.solicitar`` dispatches on a virtual thread by design,
    so the reset link appears in the log *after* the 202 has been written.
    """
    limite = time.monotonic() + timeout
    while time.monotonic() < limite:
        valor = condicion()
        if valor:
            return valor
        time.sleep(intervalo)
    return None


def cuerpo_de_error(r: Respuesta) -> str:
    return r.json().get("error", "")
