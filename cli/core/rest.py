"""Stdlib-only REST client of the EXISTING backend API (no `requests`
dependency, no new backend endpoints beyond `POST /api/auth/login` — spec:
cli-rest-contract).

Authentication is deliberately the simplest thing that works for a headless
client: the CLI keeps its service-account credentials in `.env`, so when its
access token expires it logs in again. It never holds a refresh token, never
rotates one, and never touches a cookie — that entire surface exists for the
browser, which cannot keep a credential around to re-authenticate with. Keeping
it out is not an omission; a client that grew cookie handling would be carrying
session state nobody designed for it.

Site payloads are always built with `json.dumps`, never string
concatenation or interpolation, so hostile input (e.g. `a"b;$(x)`) becomes
a single well-formed JSON string field and can never alter the JSON
structure. There is no shell anywhere in this module's call path —
`urllib.request` never spawns one — which is the structural replacement
for the deleted `menu.Tests.ps1`/`menu_test.sh` security property (spec:
legacy-launcher-retirement, "Injection-Safety Test Replacement").
"""
from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Optional, Sequence

from cli.core.errors import RestError

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 30.0

LOGIN_PATH = "/api/auth/login"

# Re-authenticate slightly before the token actually expires, so a call that
# starts valid cannot finish invalid. Small enough not to waste a token's life,
# large enough to cover a slow request and a little clock drift.
_EXPIRY_SKEW_SECONDS = 30.0


@dataclass
class TokenHolder:
    """The one mutable thing in this module.

    `RestClient` is frozen because its *configuration* should not change under
    anyone. The token, by nature, must — so it lives here, behind a small object
    the client merely points at. That keeps the immutability where it is worth
    having without pretending a session is immutable.
    """

    access_token: Optional[str] = None
    expires_at: float = 0.0

    def set(self, access_token: str, expires_in: float, now: float) -> None:
        self.access_token = access_token
        self.expires_at = now + float(expires_in)

    def clear(self) -> None:
        self.access_token = None
        self.expires_at = 0.0

    def is_valid(self, now: float) -> bool:
        return bool(self.access_token) and now < self.expires_at - _EXPIRY_SKEW_SECONDS


def _urlencode(params: dict[str, Any]) -> str:
    """Build a query string, dropping `None` values and lowercasing bools
    the way Java/Spring's boolean converter expects (`true`/`false`)."""
    clean: list[tuple[str, str]] = []
    for key, value in params.items():
        if value is None:
            continue
        if isinstance(value, bool):
            clean.append((key, str(value).lower()))
        elif isinstance(value, (list, tuple, set)):
            clean.extend((key, str(item)) for item in value)
        else:
            clean.append((key, str(value)))
    return urllib.parse.urlencode(clean)


@dataclass(frozen=True)
class RestClient:
    """Thin stdlib HTTP client bound to the backend's base URL.

    `opener` is an injection point for tests (defaults to
    `urllib.request.urlopen`) so no test needs a live backend or network.
    """

    base_url: str
    timeout: float = DEFAULT_TIMEOUT
    opener: Any = field(default=None)

    # Service-account credentials, read from `.env` by the caller. Both unset is
    # the pre-authentication behaviour, unchanged: no login, no header. That is
    # what keeps an installation that has not regenerated its `.env` working.
    username: Optional[str] = None
    password: Optional[str] = None

    now: Callable[[], float] = field(default=time.time)
    tokens: TokenHolder = field(default_factory=TokenHolder)

    @property
    def _autentica(self) -> bool:
        return bool(self.username and self.password)

    def _open(self, request: "urllib.request.Request"):
        urlopen = self.opener or urllib.request.urlopen
        return urlopen(request, timeout=self.timeout)

    def _login(self) -> None:
        """`POST /api/auth/login` with the `.env` credentials.

        Never `/api/auth/refresh`: the CLI has its own credential and no browser,
        so re-authenticating is strictly simpler than maintaining a rotating
        refresh session — and it cannot trip the backend's reuse detection.
        """
        url = self.base_url.rstrip("/") + LOGIN_PATH
        body = json.dumps({"username": self.username, "password": self.password}).encode("utf-8")
        request = urllib.request.Request(
            url, data=body, headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with self._open(request) as response:
                payload = json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as exc:
            # Loud, and never a silent skip or a retry loop: a cronjob whose
            # service account was disabled or rotated must fail visibly.
            raise RestError(
                f"El login de la cuenta de servicio '{self.username}' fue rechazado "
                f"(HTTP {exc.code}).",
                action=(
                    "Revisá CLI_SERVICE_ACCOUNT_USERNAME/CLI_SERVICE_ACCOUNT_PASSWORD en .env "
                    "y que la cuenta siga activa en la base."
                ),
            ) from exc
        except urllib.error.URLError as exc:
            raise RestError(
                f"POST {url} failed: {exc}",
                action="Confirm the backend is running on the configured port.",
            ) from exc

        token = payload.get("accessToken")
        if not token:
            raise RestError(
                f"El backend aceptó el login de '{self.username}' pero no devolvió un accessToken.",
                action="Revisá la versión del backend: POST /api/auth/login debe devolver accessToken.",
            )
        # Only the token's lifetime is ever logged, never the token itself.
        self.tokens.set(token, payload.get("expiresIn", 0), self.now())
        logger.debug("Access token obtenido para '%s' (%ss)", self.username, payload.get("expiresIn"))

    def _request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[dict[str, Any]] = None,
        json_body: Optional[dict[str, Any]] = None,
    ) -> dict:
        url = self.base_url.rstrip("/") + path
        if params:
            query = _urlencode(params)
            if query:
                url = f"{url}?{query}"

        data = None
        headers: dict[str, str] = {}
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json"

        if self._autentica and not self.tokens.is_valid(self.now()):
            self._login()

        raw = self._enviar(method, url, data, headers)

        if not raw:
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    def _enviar(self, method: str, url: str, data, headers: dict[str, str]) -> bytes:
        """One attempt, plus exactly one re-login-and-retry on a 401.

        The retry is capped at one on purpose. A backend that keeps answering
        401 after a fresh login is telling us the credential is wrong, not that
        it is stale, and looping on that turns a broken cronjob into a login
        flood against the very account that is already failing.
        """
        for reintento in (False, True):
            enviados = dict(headers)
            if self._autentica and self.tokens.access_token:
                enviados["Authorization"] = f"Bearer {self.tokens.access_token}"
            request = urllib.request.Request(url, data=data, headers=enviados, method=method)
            try:
                with self._open(request) as response:
                    return response.read()
            except urllib.error.HTTPError as exc:
                if exc.code == 401 and self._autentica and not reintento:
                    self.tokens.clear()
                    self._login()
                    continue
                if exc.code == 401 and not self._autentica:
                    # The likeliest cause by far, now that the API is gated: an
                    # installation whose `.env` predates authentication. Pointing
                    # at the port would send them chasing a backend that is up
                    # and answering exactly as designed.
                    raise RestError(
                        f"{method} {url} devolvió 401 y este cliente no tiene credenciales.",
                        action=(
                            "El backend ahora exige autenticación. Corré el paso de env del CLI "
                            "para que tu .env gane CLI_SERVICE_ACCOUNT_USERNAME/"
                            "CLI_SERVICE_ACCOUNT_PASSWORD, o completalas a mano."
                        ),
                    ) from exc
                if exc.code == 401 and self._autentica:
                    raise RestError(
                        f"{method} {url} sigue devolviendo 401 después de reautenticar como "
                        f"'{self.username}'.",
                        action=(
                            "La credencial de servicio no sirve o la cuenta está desactivada — "
                            "revisá .env y la tabla usuario."
                        ),
                    ) from exc
                raise RestError(
                    f"{method} {url} failed: {exc}",
                    action="Confirm the backend is running on the configured port.",
                ) from exc
            except urllib.error.URLError as exc:
                raise RestError(
                    f"{method} {url} failed: {exc}",
                    action="Confirm the backend is running on the configured port.",
                ) from exc
        raise AssertionError("unreachable: el loop siempre retorna o levanta")

    # -- existing endpoints only (spec: cli-rest-contract, "No New Backend
    # Endpoints") --------------------------------------------------------

    def status(self) -> dict:
        return self._request("GET", "/api/status")

    def scrape(
        self,
        precio_min: Optional[float] = None,
        precio_max: Optional[float] = None,
        sitios: Optional[Sequence[str]] = None,
        force_retrain: Optional[bool] = None,
    ) -> dict:
        params = {
            "precioMin": precio_min,
            "precioMax": precio_max,
            "sitios": sitios,
            "forceRetrain": force_retrain,
        }
        return self._request("POST", "/api/scrape", params=params)

    def entrenar(self) -> dict:
        return self._request("POST", "/api/ml/entrenar")

    def listar_sitios(self) -> dict:
        return self._request("GET", "/api/sitios")

    def crear_sitio(self, nombre: str, url: str, plataforma: str = "tiendanube") -> dict:
        """`POST /api/sitios`. The payload is ALWAYS built with
        `json.dumps` — never string concatenation — so a hostile `nombre`
        like `a"b;$(x)` is encoded as a single JSON string field and never
        alters the JSON structure (spec: "Structurally-Safe Site JSON
        Serialization")."""
        body = {"nombre": nombre, "url": url, "plataforma": plataforma}
        return self._request("POST", "/api/sitios", json_body=body)

    def eliminar_sitio(self, nombre: str) -> dict:
        """`DELETE /api/sitios/{nombre}` — `nombre` is percent-encoded into
        the path, never interpolated raw, so it cannot alter the URL
        structure either."""
        encoded = urllib.parse.quote(nombre, safe="")
        return self._request("DELETE", f"/api/sitios/{encoded}")


def build_rest_client(cfg) -> RestClient:
    """A `RestClient` carrying the service-account credentials from `.env`.

    Both keys absent — an installation whose `.env` predates authentication —
    yields exactly the client this project had before: no login, no header.
    That is what lets the dashboard and every cronjob keep working while the
    backend gates nothing yet.
    """
    from cli.core.env_file import parse_env

    env = parse_env(cfg.repo_root / ".env")
    return RestClient(
        base_url=f"http://localhost:{cfg.ports.backend}",
        username=env.get("CLI_SERVICE_ACCOUNT_USERNAME") or None,
        password=env.get("CLI_SERVICE_ACCOUNT_PASSWORD") or None,
    )
