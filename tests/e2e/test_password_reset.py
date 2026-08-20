"""Password reset, from "I forgot" to "every session of that user is gone".

**The whole loop is exercised, including the delivery channel.** With the
default ``console`` channel the link is written to ``scraper.log``, and this
module reads it back out of that file. That is not a shortcut around the
feature — it *is* the feature as shipped: ``ConsoleChannel`` exists so a
single-laptop install can reset a password without an SMTP server, and the
operator's documented procedure is exactly "read the link out of the log".

**Rate-limit budget — read this before adding a test here.**
``ResetRateLimiter`` allows 3 requests per hour per address, **10 per hour per
IP**, and 100 globally. Every request in this suite comes from ``127.0.0.1``, so
the per-IP cap is the binding one and the whole module has a budget of ten.
It spends **five**. The limiter is in-memory and per-process, so
``tests/e2e/run-e2e.sh`` starting a fresh backend resets it; running pytest
three times in an hour against one long-lived backend will exhaust it, and the
symptom is a reset link that never appears in the log (the limiter is silent by
design — a 429 would answer "does this address exist?" for anybody willing to
ask twice). ``_esperar_token`` says so in its failure message rather than
leaving you to rediscover it.
"""
from __future__ import annotations

import re
import time
import uuid

from tests.e2e.conftest import SEC_FETCH_SITE_REAL, esperar, login

# ConsoleChannel writes `║  Link:  http://…/reset-password#token=<token>`.
# The token is Base64-url without padding (PasswordResetService.aleatorio).
_ENLACE = re.compile(r"/reset-password#token=([A-Za-z0-9_-]+)")


def _esperar_token(log, email: str) -> str:
    """The token from the most recent reset block addressed to ``email``.

    Polled rather than read once: ``PasswordResetService.solicitar`` hands the
    account lookup and the delivery to a virtual thread and returns, which is
    what makes the known and unknown branches indistinguishable in time. The
    202 therefore arrives *before* the link is written.
    """

    def _buscar():
        if not log.exists():
            return None
        texto = log.read_text(encoding="utf-8", errors="replace")
        marca = texto.rfind(email)
        if marca < 0:
            return None
        m = _ENLACE.search(texto, marca)
        return m.group(1) if m else None

    token = esperar(_buscar, timeout=15.0)
    assert token, (
        f"no reset link for {email!r} ever appeared in {log}.\n"
        "Most likely causes, in order:\n"
        "  1. The per-IP rate limit (10/h from 127.0.0.1) is exhausted. It is "
        "     in-memory: restart the backend, or re-run tests/e2e/run-e2e.sh.\n"
        "  2. PASSWORD_RESET_CHANNEL is not `console`.\n"
        "  3. E2E_BACKEND_LOG points at the wrong file."
    )
    return token


def test_request_is_always_202_with_an_identical_body(api, crear_usuario, backend_log):
    """Real, unknown and malformed addresses are answered the same way.

    Compared byte-for-byte across the three rather than each against a literal:
    a backend that changed its wording would still pass a per-response check,
    and what actually matters is that the three are indistinguishable from one
    another. A form that answers differently for a known address is a list of
    this system's users, free to anyone with a wordlist.

    Spends 3 of the module's 10-request budget.
    """
    cuenta = crear_usuario("VIEWER", con_email=True)

    real = api.post("/api/auth/password-reset/request", json_body={"email": cuenta["email"]})
    desconocida = api.post(
        "/api/auth/password-reset/request",
        json_body={"email": f"nadie-{uuid.uuid4().hex}@e2e.invalid"},
    )
    malformada = api.post(
        "/api/auth/password-reset/request", json_body={"email": "esto no es una direccion"}
    )

    for etiqueta, r in (("real", real), ("unknown", desconocida), ("malformed", malformada)):
        assert r.status == 202, f"the {etiqueta} address answered {r.status}: {r.body!r}"

    assert real.body == desconocida.body == malformada.body, (
        "the three responses differ, so the endpoint is an account oracle:\n"
        f"  real      {real.body!r}\n"
        f"  unknown   {desconocida.body!r}\n"
        f"  malformed {malformada.body!r}"
    )

    # And the one that should have produced a link did. Without this the test
    # above is satisfied by a backend that silently does nothing at all.
    assert _esperar_token(backend_log, cuenta["email"])


def test_reset_end_to_end_invalidates_every_existing_session(
    api, crear_usuario, backend_log, app_origin
):
    """The link works, the new password works, and every old session is dead.

    "Every session" is the part worth testing: a reset that changed the password
    but left the intruder's refresh family alive would look completely successful
    to the user who requested it.

    Two sessions are opened first, from what are effectively two devices, so the
    assertion is about the *family sweep* (``revocarTodasLasDe``) and not merely
    about the one session that happened to ask.

    Spends 1 of the module's 10-request budget.
    """
    cuenta = crear_usuario("VIEWER", con_email=True)
    dispositivo_a = login(api, cuenta["username"], cuenta["password"])
    dispositivo_b = login(api, cuenta["username"], cuenta["password"])
    assert dispositivo_a.refresh_token != dispositivo_b.refresh_token

    # Both are live before the reset — otherwise "dead afterwards" proves nothing.
    for nombre, s in (("A", dispositivo_a), ("B", dispositivo_b)):
        assert api.get("/api/auth/me", token=s.access_token).status == 200, f"device {nombre}"

    # `iat` is whole seconds, `password_changed_at` is microseconds, and the
    # filter compares against the TRUNCATED second — a token minted in the very
    # same second as the change deliberately survives. Stepping over the second
    # boundary keeps this test aimed at the revocation and away from that
    # documented carve-out, instead of being quietly decided by scheduling.
    time.sleep(1.1)

    pedido = api.post("/api/auth/password-reset/request", json_body={"email": cuenta["email"]})
    assert pedido.status == 202

    token = _esperar_token(backend_log, cuenta["email"])
    nueva = f"nueva-{uuid.uuid4().hex}"

    confirmacion = api.post(
        "/api/auth/password-reset/confirm", json_body={"token": token, "password": nueva}
    )
    assert confirmacion.status == 200, f"confirm answered {confirmacion.status}: {confirmacion.body!r}"
    assert confirmacion.json()["ok"] is True

    # 1. The old password is gone.
    vieja = api.post(
        "/api/auth/login",
        json_body={"username": cuenta["username"], "password": cuenta["password"]},
    )
    assert vieja.status == 401, "the old password still works after a reset"

    # 2. The new one works.
    fresca = login(api, cuenta["username"], nueva)
    assert fresca.access_token

    # 3. Both pre-reset access tokens are refused — this is the
    #    password_changed_at check, the one the JVM suite's second-aligned
    #    clocks could not see.
    for nombre, s in (("A", dispositivo_a), ("B", dispositivo_b)):
        r = api.get("/api/auth/me", token=s.access_token)
        assert r.status == 401, (
            f"device {nombre}'s pre-reset access token still works ({r.status}) — "
            "a stolen token survives the reset that was supposed to end it"
        )

    # 4. And both pre-reset refresh families are revoked, so neither device can
    #    quietly mint itself a new access token and carry on.
    for nombre, s in (("A", dispositivo_a), ("B", dispositivo_b)):
        r = api.post(
            "/api/auth/refresh",
            cookies=s.cookie(),
            headers={
                "Origin": app_origin,
                "Sec-Fetch-Site": SEC_FETCH_SITE_REAL,
                "X-Refresh-CSRF": s.nonce,
            },
        )
        assert r.status == 401, (
            f"device {nombre}'s refresh family survived the reset ({r.status}: {r.body!r})"
        )


def test_a_used_token_cannot_be_used_twice(api, backend_log, crear_usuario):
    """Single use, and the refusal says nothing about why.

    Spends 1 of the module's 10-request budget; the second ``/confirm`` costs
    nothing, since only ``/request`` is rate limited.
    """
    cuenta = crear_usuario("VIEWER", con_email=True)
    api.post("/api/auth/password-reset/request", json_body={"email": cuenta["email"]})
    token = _esperar_token(backend_log, cuenta["email"])

    primera = api.post(
        "/api/auth/password-reset/confirm",
        json_body={"token": token, "password": f"pw-{uuid.uuid4().hex}"},
    )
    assert primera.status == 200

    segunda = api.post(
        "/api/auth/password-reset/confirm",
        json_body={"token": token, "password": f"pw-{uuid.uuid4().hex}"},
    )
    assert segunda.status == 400, f"a consumed token was accepted again: {segunda.body!r}"
    assert segunda.json()["error"] == "reseteo_invalido"


def test_confirm_refuses_a_short_password_the_same_way_it_refuses_a_bad_token(api):
    """The backend deliberately does not distinguish the two, and must not.

    The reset screen is told not to invent a distinction the backend refuses to
    give; this is the check that the backend really refuses to give it.
    """
    token_falso = api.post(
        "/api/auth/password-reset/confirm",
        json_body={"token": "no-es-un-token-real", "password": "suficientemente-larga"},
    )
    corta = api.post(
        "/api/auth/password-reset/confirm",
        json_body={"token": "no-es-un-token-real", "password": "corta"},
    )

    assert token_falso.status == corta.status == 400
    assert token_falso.body == corta.body, (
        "a bad token and a short password are distinguishable:\n"
        f"  bad token {token_falso.body!r}\n"
        f"  short pw  {corta.body!r}"
    )
