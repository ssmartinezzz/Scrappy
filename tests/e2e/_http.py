"""A deliberately dumb HTTP client for the end-to-end API suite.

Standard library only — ``http.client``, not ``urllib`` and not ``requests``.
Three reasons, all of them specific to what this suite has to prove:

* It asserts on headers a browser normally owns. ``Origin`` and
  ``Sec-Fetch-Site`` are forbidden header names in a browser precisely so a
  script cannot forge them; here we *are* the forger, and the client must send
  exactly what it is told, add nothing of its own, and never rewrite a value.
  ``urllib`` injects its own headers and normalises casing; ``http.client``
  hands the request over verbatim.
* It asserts on ``Set-Cookie`` *attributes* (``HttpOnly``, ``Secure``,
  ``SameSite``, ``Path``) and on the fact that a refresh token appears in the
  cookie and never in a body. A cookie jar that helpfully stores and re-sends
  cookies would hide exactly the mistake worth catching, so nothing here is
  automatic: every request states its own ``Cookie`` header.
* ``tests/cli/`` already tests this project's HTTP surface with the standard
  library (``cli/core/rest.py`` is stdlib ``urllib``), so this adds no
  third-party dependency to a repo that has none for Python.

Nothing here raises on a 4xx. A 401 and a 403 are *results* in this suite, not
accidents, and ``urllib``'s habit of turning them into exceptions is the single
most annoying thing about testing an authorization matrix with it.
"""
from __future__ import annotations

import http.client
import json as jsonlib
import urllib.parse
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Cookie:
    """One parsed ``Set-Cookie`` header."""

    nombre: str
    valor: str
    atributos: dict[str, str] = field(default_factory=dict)

    def tiene(self, flag: str) -> bool:
        return flag.lower() in self.atributos

    @property
    def borrada(self) -> bool:
        """A clearing cookie: same name and path, zero age, empty value."""
        return self.valor == "" or self.atributos.get("max-age") == "0"


class Respuesta:
    """Status, headers and body, with the parsing this suite actually needs."""

    def __init__(self, status: int, headers: list[tuple[str, str]], body: bytes) -> None:
        self.status = status
        self.headers = headers
        self.body = body

    def header(self, nombre: str) -> str | None:
        for k, v in self.headers:
            if k.lower() == nombre.lower():
                return v
        return None

    def json(self) -> dict:
        """The JSON body, or ``{}`` when there is not one.

        Empty rather than raising: several assertions in this suite are of the
        shape "status X *and* the body says Y", and a body that fails to parse
        should fail on the missing key with the body in the message, not on a
        ``JSONDecodeError`` three frames away from the interesting line.
        """
        if not self.body:
            return {}
        try:
            parsed = jsonlib.loads(self.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}
        return parsed if isinstance(parsed, dict) else {"_lista": parsed}

    def lista(self) -> list:
        parsed = jsonlib.loads(self.body.decode("utf-8"))
        assert isinstance(parsed, list), f"esperaba un array JSON, llegó {parsed!r}"
        return parsed

    @property
    def cookies(self) -> dict[str, Cookie]:
        salida: dict[str, Cookie] = {}
        for k, v in self.headers:
            if k.lower() != "set-cookie":
                continue
            partes = [p.strip() for p in v.split(";")]
            nombre, _, valor = partes[0].partition("=")
            atributos = {}
            for p in partes[1:]:
                ak, _, av = p.partition("=")
                atributos[ak.strip().lower()] = av.strip()
            salida[nombre.strip()] = Cookie(nombre.strip(), valor, atributos)
        return salida

    def __repr__(self) -> str:  # pragma: no cover - diagnostics only
        cuerpo = self.body[:300].decode("utf-8", "replace")
        return f"<Respuesta {self.status} {cuerpo!r}>"


class ApiClient:
    """One backend origin. Every call states its own credentials, in full."""

    def __init__(self, base_url: str) -> None:
        partes = urllib.parse.urlsplit(base_url)
        assert partes.scheme in ("http", "https"), f"base_url rara: {base_url!r}"
        self.origin = f"{partes.scheme}://{partes.netloc}"
        self._scheme = partes.scheme
        self._host = partes.hostname
        self._port = partes.port or (443 if partes.scheme == "https" else 80)

    def request(
        self,
        metodo: str,
        path: str,
        *,
        json_body=None,
        raw_body: bytes | None = None,
        token: str | None = None,
        cookies: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> Respuesta:
        cabeceras: dict[str, str] = dict(headers or {})
        cuerpo = raw_body
        if json_body is not None:
            cuerpo = jsonlib.dumps(json_body).encode("utf-8")
            cabeceras.setdefault("Content-Type", "application/json")
        if token:
            cabeceras["Authorization"] = f"Bearer {token}"
        if cookies:
            cabeceras["Cookie"] = "; ".join(f"{k}={v}" for k, v in cookies.items())

        conexion_cls = (
            http.client.HTTPSConnection if self._scheme == "https" else http.client.HTTPConnection
        )
        conexion = conexion_cls(self._host, self._port, timeout=30)
        try:
            conexion.request(metodo, path, body=cuerpo, headers=cabeceras)
            respuesta = conexion.getresponse()
            return Respuesta(respuesta.status, respuesta.getheaders(), respuesta.read())
        finally:
            conexion.close()

    # Sugar, so the tests read as the matrix they are.
    def get(self, path, **kw) -> Respuesta:
        return self.request("GET", path, **kw)

    def post(self, path, **kw) -> Respuesta:
        return self.request("POST", path, **kw)

    def put(self, path, **kw) -> Respuesta:
        return self.request("PUT", path, **kw)

    def delete(self, path, **kw) -> Respuesta:
        return self.request("DELETE", path, **kw)
