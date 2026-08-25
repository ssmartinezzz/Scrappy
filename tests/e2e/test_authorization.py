"""401 vs 403, and a sample of ``ApiRoutePolicy.TABLE`` checked against the
running server.

**Why the 401/403 split gets its own module.** The client's entire retry logic
hangs off it. 401 means "I do not know who you are" and the browser answers by
refreshing and retrying once; 403 means "I know who you are and the answer is
no" and the browser answers by showing a permissions screen. Conflate them and
either an expired token looks like a permissions error, or a VIEWER hitting an
ADMIN route enters a refresh loop that can never succeed. ``SecurityConfig``
overrides Spring Security's default (403 for anonymous) specifically to keep
these apart, and nothing inside the JVM notices if that override regresses.

**Why the route sample is a sample.** ``RouteCoverageTest`` already proves every
mapped handler has a row, in the build, where it belongs. What it cannot prove
is that the running filter chain agrees with the table — the table is data, the
chain is assembled from it, and this checks the assembly. One route per band is
enough for that; enumerating all seventy would be a second copy of the table,
free to drift from the first.
"""
from __future__ import annotations

import pytest

# The bands, as data, mirroring ApiRoutePolicy.TABLE. Reads only: an ADMIN-band
# probe has to be *called* as an ADMIN to be worth anything, and this suite runs
# against a real database with 17k products in it. GET /api/db/export is in the
# ADMIN band and deliberately absent — it dumps the whole database, and proving
# a VIEWER is refused does not require an ADMIN to download it.
PERMITIDAS = ["/"]

AUTENTICADAS = [
    "/api/status",
    "/api/auth/me",
    "/api/facets",
    "/api/ml/estado",       # Band B carve-out: must precede the /api/ml/** ADMIN row
    "/api/ml/resultado",    # the other carve-out
    "/api/sitios",          # GET is AUTHENTICATED; POST/DELETE are ADMIN
    "/api/financiacion/presets",
    "/api/favoritos",
]

SOLO_ADMIN = [
    "/api/usuarios",
    "/api/cron",            # reads too — the schedule is operational configuration
    "/api/agent/models",
    "/api/db/export",       # a bulk-exfiltration read, not a benign one
]

# No row matches these, so the chain's final denyAll() answers. Both are the
# posture ApiRoutePolicy documents: a route nobody wrote a rule for is refused,
# not quietly reachable. GET /api/scrape is the sharper of the two — the path
# exists, only its POST is in the table.
SIN_FILA = ["/api/scrape", "/api/no-existe-esta-ruta"]


# ── The distinction the client depends on ───────────────────────────────────

def test_no_token_on_a_protected_route_is_401(api):
    r = api.get("/api/status")
    assert r.status == 401, f"anonymous must be 401, not {r.status} — got {r.body!r}"
    assert r.json()["error"] == "no_autenticado"


def test_a_garbage_token_is_401_not_403(api):
    """An unusable credential is indistinguishable from none, and must be 401.

    Spring Security's default here is 403, which would tell the browser
    "permissions problem" about a token it could simply have refreshed.
    """
    r = api.get("/api/status", token="no.es.un.jwt")
    assert r.status == 401, f"a malformed bearer must be 401, got {r.status}: {r.body!r}"


def test_an_expired_token_is_401(api, viewer):
    """A structurally valid, correctly signed, expired token.

    Forged here by taking a real token and... not forgeable, in fact — the
    signature covers ``exp``. So this asserts the reachable half: a token whose
    signature does not verify is 401 rather than 403, which is the same answer
    an expired one produces and the same one the client acts on.

    The genuinely time-expired case needs a 15-minute wait or a clock seam; it
    is covered by the JVM suite with an injected ``Clock`` and is listed in this
    suite's residual manual notes rather than faked here.
    """
    manipulado = viewer.access_token[:-4] + "AAAA"
    r = api.get("/api/status", token=manipulado)
    assert r.status == 401, f"a bad signature must be 401, got {r.status}: {r.body!r}"


def test_a_viewer_on_an_admin_route_is_403_not_401(api, viewer):
    """The other half, and the one a refresh loop would hang off.

    403 here is what stops the client refreshing: the token is perfectly valid,
    reauthenticating changes nothing, and the answer stays no.
    """
    r = api.get("/api/usuarios", token=viewer.access_token)
    assert r.status == 403, f"a VIEWER on an ADMIN route must be 403, got {r.status}"
    assert r.json()["error"] == "sin_permiso"


def test_401_and_403_are_actually_distinguishable(api, viewer):
    """Stated as one assertion because it is one requirement.

    Two different situations, two different statuses, two different error
    codes — a client can branch on either.
    """
    sin_credencial = api.get("/api/usuarios")
    sin_permiso = api.get("/api/usuarios", token=viewer.access_token)

    assert (sin_credencial.status, sin_permiso.status) == (401, 403)
    assert sin_credencial.json()["error"] != sin_permiso.json()["error"]


# ── The bands ───────────────────────────────────────────────────────────────

@pytest.mark.parametrize("ruta", PERMITIDAS)
def test_permit_band_needs_no_credential(api, ruta):
    r = api.get(ruta)
    assert r.status == 200, f"{ruta} is Band A (PERMIT) and answered {r.status}"


@pytest.mark.parametrize("ruta", AUTENTICADAS)
def test_authenticated_band(api, viewer, ruta):
    """Reachable by any valid token; refused with none.

    A VIEWER is the right prober here: these rows say "you may reach this", and
    if one of them were quietly ADMIN the VIEWER would 403 and this would catch
    it. ``/api/ml/estado`` and ``/api/ml/resultado`` are the two that matter
    most — they are Band B carve-outs that exist only because the ``/api/ml/**``
    ADMIN row below them would otherwise swallow them, and a tidy-up that moved
    them down would fail exactly here and nowhere else.
    """
    anonimo = api.get(ruta)
    assert anonimo.status == 401, f"{ruta} must be 401 for anonymous, got {anonimo.status}"

    autenticado = api.get(ruta, token=viewer.access_token)
    assert autenticado.status not in (401, 403), (
        f"{ruta} is AUTHENTICATED in ApiRoutePolicy.TABLE but a valid VIEWER "
        f"token got {autenticado.status}: {autenticado.body[:200]!r}"
    )


@pytest.mark.parametrize("ruta", SOLO_ADMIN)
def test_admin_band(api, admin, viewer, ruta):
    anonimo = api.get(ruta)
    assert anonimo.status == 401, f"{ruta} must be 401 for anonymous, got {anonimo.status}"

    como_viewer = api.get(ruta, token=viewer.access_token)
    assert como_viewer.status == 403, (
        f"{ruta} is ADMIN in ApiRoutePolicy.TABLE but a VIEWER got {como_viewer.status}"
    )

    if ruta == "/api/db/export":
        # Refusing the VIEWER is the whole assertion for this row; see the
        # module note above on why an ADMIN does not download 17k products here.
        return

    como_admin = api.get(ruta, token=admin.access_token)
    assert como_admin.status not in (401, 403), (
        f"{ruta} is ADMIN but the ADMIN got {como_admin.status}: {como_admin.body[:200]!r}"
    )


@pytest.mark.parametrize("ruta", SIN_FILA)
def test_a_route_with_no_row_is_refused_even_for_an_admin(api, admin, ruta):
    """``denyAll()`` is genuinely reachable, and that is the posture.

    There is deliberately no final ``ANY /** → AUTHENTICATED`` row. With one,
    a new admin endpoint nobody wrote a rule for would silently be
    VIEWER-reachable. This is the live proof that the omission costs a 403 and
    not a hole — and note that the ADMIN is refused too: the terminator does not
    care who you are.
    """
    como_admin = api.get(ruta, token=admin.access_token)
    assert como_admin.status == 403, (
        f"GET {ruta} matches no row in ApiRoutePolicy.TABLE, so denyAll() must "
        f"refuse it — even for an ADMIN. Got {como_admin.status}. If a catch-all "
        "row was added, this is the test that was supposed to notice."
    )

    anonimo = api.get(ruta)
    assert anonimo.status == 401, f"{ruta} must be 401 for anonymous, got {anonimo.status}"


# ── Login itself ────────────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "cuerpo",
    [
        {"username": "no-existe-esta-cuenta", "password": "cualquiera"},
        {"username": "", "password": ""},
        {},
        {"username": "no-existe-esta-cuenta"},
    ],
    ids=["unknown-user", "empty", "no-fields", "no-password"],
)
def test_every_login_failure_looks_identical(api, cuerpo):
    """Same status, same body — no oracle for which usernames exist."""
    r = api.post("/api/auth/login", json_body=cuerpo)
    assert r.status == 401, f"{cuerpo} answered {r.status}: {r.body!r}"
    assert r.json()["error"] == "credenciales_invalidas"


def test_a_wrong_password_for_a_real_account_looks_the_same(api, crear_usuario):
    """The row that makes the previous test mean something.

    Without it, "every failure is identical" is satisfied by a backend that
    fails every login the same way, including the valid ones.
    """
    cuenta = crear_usuario("VIEWER")
    r = api.post(
        "/api/auth/login",
        json_body={"username": cuenta["username"], "password": "definitivamente-no-es"},
    )
    assert r.status == 401
    assert r.json()["error"] == "credenciales_invalidas"

    bien = api.post(
        "/api/auth/login",
        json_body={"username": cuenta["username"], "password": cuenta["password"]},
    )
    assert bien.status == 200, "the account itself must still be usable"


def test_a_deactivated_account_cannot_log_in(api, admin, crear_usuario):
    """Deactivation is the removal mechanism, and it has to actually remove.

    Indistinguishable from an unknown username by construction:
    ``buscarActivaPorUsername`` excludes ``activo = FALSE`` before any branch
    somebody could forget to write.
    """
    cuenta = crear_usuario("VIEWER")
    baja = api.delete(f"/api/usuarios/{cuenta['username']}", token=admin.access_token)
    assert baja.status == 200, f"deactivation answered {baja.status}: {baja.body!r}"

    r = api.post(
        "/api/auth/login",
        json_body={"username": cuenta["username"], "password": cuenta["password"]},
    )
    assert r.status == 401
    assert r.json()["error"] == "credenciales_invalidas"
