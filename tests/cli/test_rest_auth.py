"""Tests for the CLI's authentication (slice 3 of user-accounts-and-roles).

The CLI is a headless client and its needs are much smaller than a browser's.
It holds its own credentials in `.env`, so when a token expires it simply logs
in again — it never holds a refresh token, never rotates one, and never touches
a cookie. That whole surface exists for the browser, and the tests below pin its
absence rather than trusting a comment: a client that quietly grew cookie
handling would be sharing session state nobody designed for it.

`RestClient` stays a frozen dataclass. The token has to change over time, so the
mutability lives in the `TokenHolder` it points at, not in the client itself —
the client's configuration remains immutable, which is the property that was
worth keeping.

**The API is gated since the enforcement slice**, so these credentials are no
longer decorative: without them every call is a 401. An unconfigured client still
sends no header and attempts no login — the behaviour it always had — but it now
fails, and it says so in terms of the credential rather than of the port, which
is the one thing somebody upgrading actually needs to read.
"""
from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from dataclasses import FrozenInstanceError

import pytest

from cli.core.errors import RestError
from cli.core.rest import RestClient, TokenHolder

USUARIO = "cli-service"
PASSWORD = "la-password-del-servicio"


class _FakeResponse:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload

    def read(self) -> bytes:
        return self._payload

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc) -> bool:
        return False


def _http_error(code: int, url: str = "http://localhost:3000/api/status"):
    return urllib.error.HTTPError(url, code, "no", hdrs=None, fp=None)


class _Backend:
    """Records every request and answers scripted responses."""

    def __init__(self, *, token: str = "token-1", fail_next_with: list[int] | None = None):
        self.requests: list[urllib.request.Request] = []
        self.tokens = [token, "token-2", "token-3"]
        self.emitidos: list[str] = []
        self.fail_next_with = list(fail_next_with or [])
        self.login_status = 200

    def __call__(self, request: urllib.request.Request, timeout=None):
        self.requests.append(request)
        if request.full_url.endswith("/api/auth/login"):
            if self.login_status != 200:
                raise _http_error(self.login_status, request.full_url)
            token = self.tokens[len(self.emitidos) % len(self.tokens)]
            self.emitidos.append(token)
            return _FakeResponse(
                json.dumps({"accessToken": token, "tokenType": "Bearer", "expiresIn": 900}).encode()
            )
        if self.fail_next_with:
            raise _http_error(self.fail_next_with.pop(0), request.full_url)
        return _FakeResponse(b'{"ok": true}')

    @property
    def logins(self) -> list[urllib.request.Request]:
        return [r for r in self.requests if r.full_url.endswith("/api/auth/login")]

    @property
    def no_login(self) -> list[urllib.request.Request]:
        return [r for r in self.requests if not r.full_url.endswith("/api/auth/login")]


def _client(backend: _Backend, **kwargs) -> RestClient:
    return RestClient(
        base_url="http://localhost:3000",
        opener=backend,
        username=USUARIO,
        password=PASSWORD,
        **kwargs,
    )


def test_the_first_call_logs_in_and_then_carries_the_bearer_token():
    backend = _Backend()

    _client(backend).status()

    assert len(backend.logins) == 1
    login = backend.logins[0]
    assert login.get_method() == "POST"
    assert json.loads(login.data) == {"username": USUARIO, "password": PASSWORD}

    llamada = backend.no_login[0]
    assert llamada.get_header("Authorization") == "Bearer token-1"


def test_the_token_is_reused_across_calls():
    backend = _Backend()
    client = _client(backend)

    client.status()
    client.listar_sitios()
    client.entrenar()

    assert len(backend.logins) == 1, "one login should serve every call while the token is valid"
    assert all(r.get_header("Authorization") == "Bearer token-1" for r in backend.no_login)


def test_a_401_triggers_exactly_one_re_login_and_one_retry():
    backend = _Backend(fail_next_with=[401])
    client = _client(backend)

    client.status()

    assert len(backend.logins) == 2, "expired token → log in again, once"
    assert backend.no_login[-1].get_header("Authorization") == "Bearer token-2", (
        "the retry has to carry the NEW token, not the one that just failed"
    )


def test_a_second_401_after_re_login_fails_loudly_instead_of_looping():
    backend = _Backend(fail_next_with=[401, 401])
    client = _client(backend)

    with pytest.raises(RestError) as exc:
        client.status()

    assert len(backend.logins) == 2, "one retry, not an infinite re-login loop"
    assert USUARIO in str(exc.value)


def test_a_failed_login_is_loud_and_names_the_account():
    backend = _Backend()
    backend.login_status = 401

    with pytest.raises(RestError) as exc:
        _client(backend).status()

    mensaje = str(exc.value)
    assert USUARIO in mensaje
    assert PASSWORD not in mensaje, "an error message is not a place for a credential"


def test_an_expired_token_is_replaced_before_the_call_not_after_a_401():
    reloj = {"ahora": 1_000.0}
    backend = _Backend()
    client = _client(backend, now=lambda: reloj["ahora"])

    client.status()
    reloj["ahora"] += 1_000  # past the 900s lifetime
    client.status()

    assert len(backend.logins) == 2
    assert not any(isinstance(r, urllib.error.HTTPError) for r in backend.requests)
    assert backend.no_login[-1].get_header("Authorization") == "Bearer token-2"


def test_without_credentials_nothing_changes():
    backend = _Backend()

    RestClient(base_url="http://localhost:3000", opener=backend).status()

    assert backend.logins == [], "an unconfigured client must not try to authenticate"
    assert backend.no_login[0].get_header("Authorization") is None


def test_the_client_never_calls_refresh_and_never_handles_a_cookie():
    backend = _Backend(fail_next_with=[401])
    client = _client(backend)

    client.status()

    urls = [r.full_url for r in backend.requests]
    assert not any("/api/auth/refresh" in u for u in urls), (
        "the refresh/rotation surface is browser-only; the CLI re-authenticates from .env"
    )
    assert all(r.get_header("Cookie") is None for r in backend.requests)


def test_a_non_401_error_is_not_retried():
    backend = _Backend(fail_next_with=[500])
    client = _client(backend)

    with pytest.raises(RestError):
        client.status()

    assert len(backend.logins) == 1, "a 500 is not an authentication problem"


def test_the_client_is_still_frozen():
    client = _client(_Backend())

    with pytest.raises(FrozenInstanceError):
        client.base_url = "http://otro"  # type: ignore[misc]


def test_the_token_holder_carries_the_mutable_state():
    holder = TokenHolder()
    assert holder.access_token is None

    holder.set("t", expires_in=900, now=100.0)

    assert holder.access_token == "t"
    assert holder.is_valid(now=100.0)
    assert not holder.is_valid(now=1_100.0)


def test_the_password_is_never_logged(caplog):
    backend = _Backend()

    with caplog.at_level(logging.DEBUG):
        _client(backend).status()

    assert PASSWORD not in caplog.text


def test_an_unconfigured_client_gets_a_useful_message_now_that_the_api_is_gated():
    """The upgrade path: a .env that predates authentication."""
    backend = _Backend(fail_next_with=[401])
    client = RestClient(base_url="http://localhost:3000", opener=backend)

    with pytest.raises(RestError) as exc:
        client.status()

    mensaje = str(exc.value)
    assert "credenciales" in mensaje, (
        "pointing at the port sends them chasing a backend that is up and answering as designed"
    )
    assert "CLI_SERVICE_ACCOUNT_USERNAME" in mensaje
    assert backend.logins == [], "with nothing configured there is nothing to log in with"
