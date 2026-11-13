import json

import httpx
import pytest

from scrappy_mcp.client import ApiClient


class FakeBackend:
    """Minimal stand-in for the backend: issues tokens and records every request."""

    def __init__(self):
        self.requests: list[httpx.Request] = []
        self.logins = 0
        self.valid_token = None
        self.expire_next = False
        self.reply = None

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.url.path == "/api/auth/login":
            body = json.loads(request.content)
            if body != {"username": "bot", "password": "pw"}:
                return httpx.Response(401, json={"error": {"code": "credenciales"}})
            self.logins += 1
            self.valid_token = f"tok{self.logins}"
            return httpx.Response(200, json={"data": {"accessToken": self.valid_token}})
        if request.headers.get("Authorization") != f"Bearer {self.valid_token}":
            return httpx.Response(401, json={"error": {"code": "token_invalido"}})
        if self.expire_next:
            self.expire_next = False
            self.valid_token = "rotated-away"
            return httpx.Response(401, json={"error": {"code": "token_vencido"}})
        if request.url.path == "/api/csv":
            return httpx.Response(200, text="a;b\n1;2", headers={"content-type": "text/csv"})
        if request.url.path == "/api/boom":
            return httpx.Response(409, json={"error": {"code": "ocupado"}})
        if self.reply is not None:
            return httpx.Response(200, json=self.reply)
        body = json.loads(request.content) if request.content else None
        return httpx.Response(200, json={"data": {"method": request.method, "path": request.url.path,
                                                  "q": dict(request.url.params), "body": body}})


@pytest.fixture
def backend():
    return FakeBackend()


@pytest.fixture
def client(backend):
    return ApiClient("http://api.test", "bot", "pw", transport=httpx.MockTransport(backend.handler))
