"""HTTP client for the Scrappy API: the single choke point every MCP tool goes through.

The policy lives here, not in the tools, so no tool (the generic one included)
can issue a request the user ruled out: nothing that deletes data.
"""

from __future__ import annotations

import posixpath
import threading
from typing import Any

import httpx

ALLOWED_METHODS = {"GET", "POST", "PUT", "PATCH"}
# Not a DELETE, but it throws away the offer to resume an interrupted run.
FORBIDDEN_PATHS = {("POST", "/api/scrape/discard")}


class ForbiddenOperation(Exception):
    """The request was refused locally and never sent."""


class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:2000]}" if status else body)
        self.status = status


class ApiClient:
    def __init__(self, base_url: str, username: str, password: str,
                 transport: httpx.BaseTransport | None = None, timeout: float = 120.0):
        self._username = username
        self._password = password
        self._http = httpx.Client(base_url=base_url, transport=transport, timeout=timeout)
        self._token: str | None = None
        self._lock = threading.Lock()

    def request(self, method: str, path: str, params: dict | None = None, json: Any = None) -> Any:
        method, path = self._check(method, path)
        try:
            response = self._send(method, path, params, json)
            if response.status_code == 401:
                self._login()
                response = self._send(method, path, params, json)
        except httpx.TransportError as e:
            raise ApiError(0, f"backend not reachable at {self._http.base_url}: {e}") from e
        if response.is_error:
            raise ApiError(response.status_code, response.text)
        if "json" in response.headers.get("content-type", ""):
            return response.json()
        return response.text

    @staticmethod
    def _check(method: str, path: str) -> tuple[str, str]:
        method = method.strip().upper()
        if method not in ALLOWED_METHODS:
            raise ForbiddenOperation(f"{method} is not allowed: this server never deletes data")
        # A relative or absolute-URL path could escape the configured host; ".."
        # could climb out of /api. Both are refused rather than resolved.
        normalized = posixpath.normpath(path.split("?")[0])
        if not path.startswith("/") or ".." in path or not normalized.startswith("/api/"):
            raise ForbiddenOperation(f"only /api/* paths are allowed: {path!r}")
        if (method, normalized) in FORBIDDEN_PATHS:
            raise ForbiddenOperation(f"{method} {normalized} is not allowed: it discards data")
        return method, normalized

    def _send(self, method, path, params, json) -> httpx.Response:
        if self._token is None:
            self._login()
        return self._http.request(method, path, params=_clean(params), json=json,
                                  headers={"Authorization": f"Bearer {self._token}"})

    def _login(self) -> None:
        with self._lock:
            response = self._http.post("/api/auth/login",
                                       json={"username": self._username, "password": self._password})
            if response.is_error:
                raise ApiError(response.status_code, response.text)
            self._token = response.json()["data"]["accessToken"]


def _clean(params: dict | None) -> dict | None:
    """Drop unset filters so the backend applies its own defaults."""
    if not params:
        return None
    return {k: v for k, v in params.items() if v is not None and v != ""}
