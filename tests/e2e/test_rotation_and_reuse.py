"""Rotation, the grace window, and reuse detection — against the real clock.

``RefreshTokenService`` is a state machine whose most interesting transition is
timed:

    ACTIVE ──present──► rotate, issue successor
    ROTATED ──present again──┬── within 10 s ──► replay the SAME successor
                             └── after 10 s   ──► REUSE: burn the family

The unit suite drives that machine with an injected ``Clock``. This module
drives it with the real one, which is the only way to find out whether the
window a browser client actually experiences behaves the way the fixed-clock
tests say it does — the previous change's fixed clocks all landed on exact
second boundaries and hid three genuine bugs.

**On defeating the window.** ``GRACIA`` is a compile-time constant with no
runtime override and no clock seam reachable over HTTP, so there is exactly one
honest way to observe the post-grace branch: wait longer than the window. The
one test that needs it sleeps ``GRACIA + 1`` seconds and says so. Writing it
without the sleep would have meant asserting the *replay* branch and calling it
reuse detection — a test that stays green after the detector is deleted.
"""
from __future__ import annotations

import time

import pytest

from tests.e2e.conftest import SEC_FETCH_SITE_REAL, Sesion, login

# RefreshTokenService.GRACIA. Duplicated here because the Java constant is not
# reachable from Python; if it changes there, this test starts sleeping too
# little and fails loudly rather than silently sitting inside the window.
GRACIA_SEGUNDOS = 10


@pytest.fixture
def sesion(api, crear_usuario) -> Sesion:
    cuenta = crear_usuario("VIEWER")
    return login(api, cuenta["username"], cuenta["password"])


def _rotar(api, token: str, nonce: str | None, app_origin: str):
    headers = {"Origin": app_origin, "Sec-Fetch-Site": SEC_FETCH_SITE_REAL}
    if nonce:
        headers["X-Refresh-CSRF"] = nonce
    return api.post("/api/auth/refresh", cookies={"refresh": token}, headers=headers)


def test_rotation_issues_a_fresh_pair(api, sesion, app_origin):
    r = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert r.status == 200, f"{r.status}: {r.body!r}"

    sucesor = r.cookies["refresh"].valor
    assert sucesor != sesion.refresh_token, "the refresh token must rotate"
    assert r.json()["csrfNonce"] != sesion.nonce, (
        "the nonce must rotate with the token — a nonce that outlives its "
        "token is a nonce an attacker only has to steal once"
    )
    assert r.json()["accessToken"], "and a new access token comes with it"


def test_within_the_grace_window_the_same_successor_is_replayed(api, sesion, app_origin):
    """The safety net for a client that fires two refreshes at once.

    The proof that this is a *replay* and not a second rotation is that the
    successor pair is byte-identical. A second rotation would mint a different
    token, leave the first successor spent, and the client that received it
    would trip reuse detection on its next refresh — the self-inflicted logout
    the window exists to prevent.

    Immediately after, with no sleep: staying inside the window is the point of
    this row, and the next test is the one that leaves it.
    """
    primera = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert primera.status == 200

    segunda = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert segunda.status == 200, (
        f"a same-token retry inside the {GRACIA_SEGUNDOS}s window must be "
        f"replayed, not refused — got {segunda.status}: {segunda.body!r}"
    )
    assert segunda.cookies["refresh"].valor == primera.cookies["refresh"].valor, (
        "the replay handed back a DIFFERENT refresh token — that is a second "
        "rotation wearing a replay's clothes, and it strands the first successor"
    )
    assert segunda.json()["csrfNonce"] == primera.json()["csrfNonce"]
    assert segunda.json()["accessToken"] == primera.json()["accessToken"]


@pytest.mark.slow
def test_after_the_grace_window_reuse_burns_the_whole_family(api, sesion, app_origin):
    """Real reuse detection, observed by outliving the window.

    Sleeps ``GRACIA + 1`` seconds on purpose. There is no clock seam over HTTP,
    so the alternative is not a faster test — it is a test that never leaves the
    replay branch and therefore never exercises the detector at all.

    Two things must be true afterwards, and the second is the one that matters:
    the reused token is refused with ``sesion_invalidada``, *and* the perfectly
    innocent successor is dead too. Revoking only the presented token would let
    a thief who copied a token keep using the successor they were handed.
    """
    rotacion = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert rotacion.status == 200
    sucesor = rotacion.cookies["refresh"].valor
    nonce_sucesor = rotacion.json()["csrfNonce"]

    time.sleep(GRACIA_SEGUNDOS + 1)

    reusado = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert reusado.status == 401, (
        f"reuse outside the window must be 401, got {reusado.status}: {reusado.body!r}"
    )
    assert reusado.json()["error"] == "sesion_invalidada"
    assert reusado.cookies["refresh"].borrada, (
        "reuse must also clear the cookie — leaving it in place makes the "
        "browser re-present a credential that can only ever fail from here on"
    )

    muerto = _rotar(api, sucesor, nonce_sucesor, app_origin)
    assert muerto.status == 401, (
        "the successor survived reuse detection — the family was not revoked, "
        f"only the presented token was. Got {muerto.status}: {muerto.body!r}"
    )
    assert muerto.json()["error"] == "refresh_invalido"


def test_logout_revokes_the_family_and_clears_the_cookie(api, sesion, app_origin):
    """``DELETE /api/auth/refresh``, not ``/api/auth/logout``.

    The cookie's ``Path`` is ``/api/auth/refresh``, so the browser attaches it
    to no other route — and without the cookie the server cannot tell which
    family to revoke. Logging out anywhere else would clear the browser's copy
    and leave the session alive on the server.
    """
    r = api.delete(
        "/api/auth/refresh",
        cookies=sesion.cookie(),
        headers={"X-Refresh-CSRF": sesion.nonce, "Origin": app_origin},
    )
    assert r.status == 200 and r.json()["cerrada"] is True
    assert r.cookies["refresh"].borrada

    despues = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert despues.status == 401, f"the family survived logout: {despues.body!r}"
    assert despues.json()["error"] == "refresh_invalido"


def test_logout_has_no_bootstrap_carve_out(api, sesion, app_origin):
    """A nonce-less DELETE must not close somebody's session.

    The bootstrap carve-out is scoped to rotation: recovering your own session
    after a reload is a need, logging another session out from a page that never
    held a nonce is not. This asserts the session is still *alive* afterwards,
    not merely that the call reported failure — a "false" return with the family
    quietly revoked would be the actual bug.
    """
    r = api.delete(
        "/api/auth/refresh",
        cookies=sesion.cookie(),
        headers={"Origin": app_origin, "Sec-Fetch-Site": SEC_FETCH_SITE_REAL},
    )
    assert r.json()["cerrada"] is False, "a nonce-less logout must not close the family"

    vive = _rotar(api, sesion.refresh_token, sesion.nonce, app_origin)
    assert vive.status == 200, (
        f"the nonce-less logout revoked the family anyway — {vive.status}: {vive.body!r}"
    )
