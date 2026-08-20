"""The cold-start ("bootstrap") refresh admission matrix, over real HTTP.

A page that has just loaded holds no CSRF nonce — the access token and the
nonce both live in memory and memory died with the reload. Its first
``POST /api/auth/refresh`` therefore *cannot* carry ``X-Refresh-CSRF``, and
whether that absence is forgiven is decided by
``AuthEndpoints.esBootstrapAdmitido`` from two headers alone.

This matrix is the reason the whole e2e exercise exists. It cannot be tested
honestly anywhere else: ``Origin`` and ``Sec-Fetch-Site`` are *forbidden header
names* in a browser, so their values are facts about the topology rather than
about the client, and a same-origin run (``vite dev``) would produce a set of
values no shipped install ever produces. See ``conftest.py``'s topology guard.

Every row below states its headers explicitly. Nothing is inherited from a
session object, because the point is precisely which headers were on the wire.
"""
from __future__ import annotations

import pytest

from tests.e2e.conftest import SEC_FETCH_SITE_REAL, Sesion, login


@pytest.fixture
def sesion_fresca(api, crear_usuario) -> Sesion:
    """A brand-new, never-rotated session per test.

    Per-test rather than shared: a rotation in one row would leave the next row
    presenting a spent token, and the CSRF check runs *before* the token's state
    is even looked at (``RefreshTokenService.rotar``), so a shared token would
    still produce the expected 403s — for entirely the wrong reason, and the
    test would keep passing after the admission check was deleted.
    """
    cuenta = crear_usuario("VIEWER")
    return login(api, cuenta["username"], cuenta["password"])


def _refresh(api, sesion, **headers):
    return api.post("/api/auth/refresh", cookies=sesion.cookie(), headers=headers)


# ── The one row that must be admitted ───────────────────────────────────────

def test_allowlisted_origin_and_same_site_is_admitted(api, sesion_fresca, app_origin):
    """The exact shape both shipped installs produce on a cold reload.

    ``same-site``, never ``same-origin``: the SPA is on :5173 (or :8080 under
    Docker) and the API on :3000, so the browser reports same-site. Requiring
    ``same-origin`` would have 403'd the cold start on *both* shipped installs
    while passing under ``vite dev`` — the failure shape discovery #926 caught.
    """
    r = _refresh(api, sesion_fresca, Origin=app_origin, **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL})

    assert r.status == 200, f"bootstrap refresh answered {r.status}: {r.body!r}"
    cuerpo = r.json()
    assert cuerpo["accessToken"], "an admitted bootstrap must hand back an access token"
    assert cuerpo["csrfNonce"], "and a fresh nonce, or the client can never refresh again"
    assert cuerpo["csrfNonce"] != sesion_fresca.nonce, "the nonce rotates with the token"


def test_same_origin_is_also_admitted_but_no_shipped_install_produces_it(
    api, sesion_fresca, app_origin
):
    """``same-origin`` is in the trusted set, and that is deliberate breadth.

    Pinned so nobody "tightens" the set down to ``same-origin`` alone later —
    that is exactly the rejected design. This row proves the value is accepted;
    it does NOT claim any install sends it. Only ``vite dev`` does.
    """
    r = _refresh(api, sesion_fresca, Origin=app_origin, **{"Sec-Fetch-Site": "same-origin"})
    assert r.status == 200, f"same-origin should also be admitted, got {r.status}"


# ── Everything else fails closed ────────────────────────────────────────────

def test_origin_from_a_non_allowlisted_port_is_refused(api, sesion_fresca):
    """Another ``localhost`` port is the attacker this nonce exists for.

    ``SameSite=Strict`` does not help: it scopes by registrable domain and
    ignores the port, so ``http://localhost:9999`` is same-site with the backend
    and the browser attaches the refresh cookie. ``Origin`` is the signal that
    carries the port, and it is checked byte-for-byte against
    ``APP_CORS_ALLOWED_ORIGINS``.

    Two layers refuse this, and the assertion is on the status rather than the
    body because of it: credentialed CORS rejects the un-allow-listed origin
    before the request reaches the controller ("Invalid CORS request"), and the
    bootstrap check would refuse it after. Both fail closed; whichever answers
    first, the answer is 403 and no token is issued.
    """
    r = _refresh(
        api, sesion_fresca,
        Origin="http://localhost:9999",
        **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL},
    )
    assert r.status == 403, f"a foreign localhost port must not bootstrap, got {r.status}"
    assert "accessToken" not in r.json(), f"refused and yet issued a token: {r.body!r}"


def test_absent_origin_is_refused(api, sesion_fresca):
    r = _refresh(api, sesion_fresca, **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL})
    assert r.status == 403, f"no Origin must fail closed, got {r.status}"
    assert r.json()["error"] == "csrf_invalido"


def test_absent_sec_fetch_site_is_refused(api, sesion_fresca, app_origin):
    """Absent is a refusal, not a shrug — a closed decision, not provisional.

    An old browser that omits ``Sec-Fetch-Site`` gets a login screen instead of
    a silent session recovery. That is the intended trade: treating "absent" as
    "probably fine" would hand the whole gate to any client willing to omit it.
    """
    r = _refresh(api, sesion_fresca, Origin=app_origin)
    assert r.status == 403, f"no Sec-Fetch-Site must fail closed, got {r.status}"
    assert r.json()["error"] == "csrf_invalido"


@pytest.mark.parametrize("valor", ["cross-site", "none"])
def test_untrusted_sec_fetch_site_values_are_refused(api, sesion_fresca, app_origin, valor):
    """``cross-site`` is a foreign page; ``none`` is a typed URL or a bookmark.

    Neither is an app cold-starting itself, and this row reaches the bootstrap
    check for real: the ``Origin`` is allow-listed, so CORS lets it through and
    the refusal can only come from ``esBootstrapAdmitido``.
    """
    r = _refresh(api, sesion_fresca, Origin=app_origin, **{"Sec-Fetch-Site": valor})
    assert r.status == 403, f"Sec-Fetch-Site: {valor} must fail closed, got {r.status}"
    assert r.json()["error"] == "csrf_invalido"


def test_a_present_but_wrong_nonce_is_never_forgiven(api, sesion_fresca, app_origin):
    """The bootstrap path widens "no nonce", never "the right nonce".

    A wrong nonce with otherwise perfect bootstrap headers must still be 403.
    If this ever passes, ``bootstrapAdmitido`` has become a weak-nonce mode —
    an attacker on an allow-listed origin could then guess.
    """
    r = _refresh(
        api, sesion_fresca,
        Origin=app_origin,
        **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL, "X-Refresh-CSRF": "no-es-el-nonce"},
    )
    assert r.status == 403, f"a wrong nonce must be refused, got {r.status}"
    assert r.json()["error"] == "csrf_invalido"


def test_a_refused_csrf_leaves_the_token_intact(api, sesion_fresca, app_origin):
    """403 must not consume the token — otherwise CSRF becomes a forced logout.

    ``RefreshTokenService.rotar`` checks the nonce *before* touching the token
    for exactly this reason: rotate-then-refuse would spend the real client's
    token, so its next legitimate refresh would trip reuse detection. Blocking
    the attack would hand the attacker the logout anyway.
    """
    refusado = _refresh(
        api, sesion_fresca,
        Origin=app_origin,
        **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL, "X-Refresh-CSRF": "no-es-el-nonce"},
    )
    assert refusado.status == 403

    legitimo = api.post(
        "/api/auth/refresh",
        cookies=sesion_fresca.cookie(),
        headers={
            "Origin": app_origin,
            "Sec-Fetch-Site": SEC_FETCH_SITE_REAL,
            "X-Refresh-CSRF": sesion_fresca.nonce,
        },
    )
    assert legitimo.status == 200, (
        f"the token was consumed by the refused request — got {legitimo.status}: {legitimo.body!r}"
    )


def test_a_revoked_token_is_401_even_with_perfect_bootstrap_headers(
    api, sesion_fresca, app_origin
):
    """Bootstrap grants no other leniency, and 401 says so precisely.

    Logout revokes the family. Presenting the dead token afterwards, with
    flawless bootstrap headers, must be 401 — "your credential is finished",
    which tells the client to authenticate — never 403 ("permissions"), and
    never an admission.
    """
    cerrado = api.delete(
        "/api/auth/refresh",
        cookies=sesion_fresca.cookie(),
        headers={"X-Refresh-CSRF": sesion_fresca.nonce, "Origin": app_origin},
    )
    assert cerrado.status == 200 and cerrado.json()["cerrada"] is True

    r = _refresh(api, sesion_fresca, Origin=app_origin, **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL})
    assert r.status == 401, f"a revoked token must be 401, got {r.status}: {r.body!r}"
    assert r.json()["error"] == "refresh_invalido"


# ── The cookie itself ───────────────────────────────────────────────────────

def test_the_refresh_token_travels_only_in_the_cookie(api, sesion_fresca, app_origin):
    """Never in a body, and with the four attributes that make it survivable.

    ``HttpOnly`` keeps an injected script from walking away with a fourteen-day
    credential; ``Path`` narrows it to one endpoint out of ~70, which is what
    makes it a scoped exception to "no cookies" rather than a reversal of it.
    """
    r = _refresh(api, sesion_fresca, Origin=app_origin, **{"Sec-Fetch-Site": SEC_FETCH_SITE_REAL})
    assert r.status == 200

    galleta = r.cookies["refresh"]
    assert galleta.valor, "the successor must actually be set"
    assert galleta.valor not in r.body.decode("utf-8"), (
        "the refresh token appeared in the response BODY — a client could then "
        "read it from JavaScript, which is the whole point of HttpOnly"
    )
    assert galleta.tiene("httponly")
    assert galleta.tiene("secure")
    assert galleta.atributos.get("samesite") == "Strict"
    assert galleta.atributos.get("path") == "/api/auth/refresh"
