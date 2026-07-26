"""Stdlib-only REST client of the EXISTING backend API (no `requests`
dependency, no new backend endpoints — spec: cli-rest-contract).

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
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional, Sequence

from cli.core.errors import RestError

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 30.0


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

    def _open(self, request: "urllib.request.Request"):
        urlopen = self.opener or urllib.request.urlopen
        return urlopen(request, timeout=self.timeout)

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

        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self._open(request) as response:
                raw = response.read()
        except urllib.error.URLError as exc:
            raise RestError(
                f"{method} {url} failed: {exc}",
                action="Confirm the backend is running on the configured port.",
            ) from exc

        if not raw:
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

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
