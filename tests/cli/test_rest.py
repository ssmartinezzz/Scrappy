"""Tests for cli.core.rest — stdlib REST client + the injection-safety
ordering gate (tasks.md 1.5.1-1.5.2).

1.5.1 [ORDERING GATE]: this test replaces the deleted
`menu.Tests.ps1`/`menu_test.sh` security property and MUST be green before
the legacy launcher files are ever deleted (slice 3, tasks 3.3.2-3.3.5).
"""
from __future__ import annotations

import json
import subprocess
import urllib.request

import pytest

from cli.core.rest import RestClient

HOSTILE_NAME = 'a"b;$(x)'


class _FakeResponse:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload

    def read(self) -> bytes:
        return self._payload

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc) -> bool:
        return False


def _recording_opener(calls: list):
    def _opener(request: "urllib.request.Request", timeout=None):
        calls.append(request)
        return _FakeResponse(b'{"ok": true}')

    return _opener


def test_injection_safety_hostile_name_round_trips_as_a_single_json_field():
    """1.5.1 [ORDERING GATE] — json.dumps structural safety."""
    body = json.dumps({"nombre": HOSTILE_NAME, "url": "https://x.test", "plataforma": "tiendanube"})
    parsed = json.loads(body)

    assert parsed["nombre"] == HOSTILE_NAME
    assert len(parsed) == 3  # hostile input never adds/removes JSON fields


def test_injection_safety_client_never_invokes_a_shell_subprocess(monkeypatch: pytest.MonkeyPatch):
    """1.5.1 [ORDERING GATE] — no shell=True, no shell subprocess anywhere
    in rest.py's call path."""
    shelled_out: list = []

    def _forbidden(*args, **kwargs):
        shelled_out.append((args, kwargs))
        raise AssertionError("cli.core.rest must never invoke a subprocess")

    monkeypatch.setattr(subprocess, "run", _forbidden)
    monkeypatch.setattr(subprocess, "Popen", _forbidden)

    captured: list = []
    client = RestClient(base_url="http://localhost:3000", opener=_recording_opener(captured))

    client.crear_sitio(HOSTILE_NAME, "https://x.test", "tiendanube")

    assert not shelled_out
    sent = json.loads(captured[0].data)
    assert sent == {"nombre": HOSTILE_NAME, "url": "https://x.test", "plataforma": "tiendanube"}


def test_hostile_site_name_survives_url_path_encoding_on_delete():
    """Delete path also never string-concatenates raw input into the URL."""
    captured: list = []
    client = RestClient(base_url="http://localhost:3000", opener=_recording_opener(captured))

    client.eliminar_sitio(HOSTILE_NAME)

    assert HOSTILE_NAME not in captured[0].full_url  # raw hostile chars never land unescaped
    assert '"' not in captured[0].full_url
    assert ";" not in captured[0].full_url


def test_menu_actions_map_to_existing_endpoints_only():
    """1.5.2 — method/URL/params match exactly the existing endpoints; no
    other endpoint is ever invoked."""
    captured: list = []
    client = RestClient(base_url="http://localhost:3000", opener=_recording_opener(captured))

    client.status()
    client.scrape(precio_min=10, precio_max=100, sitios=["freres", "vcp"], force_retrain=True)
    client.entrenar()
    client.listar_sitios()
    client.crear_sitio("nuevo", "https://nuevo.test", "shopify")
    client.eliminar_sitio("nuevo")

    seen = [(req.get_method(), req.full_url.split("?")[0]) for req in captured]
    assert seen == [
        ("GET", "http://localhost:3000/api/status"),
        ("POST", "http://localhost:3000/api/scrape"),
        ("POST", "http://localhost:3000/api/ml/entrenar"),
        ("GET", "http://localhost:3000/api/sitios"),
        ("POST", "http://localhost:3000/api/sitios"),
        ("DELETE", "http://localhost:3000/api/sitios/nuevo"),
    ]

    scrape_req = captured[1]
    assert "precioMin=10" in scrape_req.full_url
    assert "precioMax=100" in scrape_req.full_url
    assert "forceRetrain=true" in scrape_req.full_url
    assert "sitios=freres" in scrape_req.full_url
    assert "sitios=vcp" in scrape_req.full_url

    crear_req = captured[4]
    assert json.loads(crear_req.data) == {
        "nombre": "nuevo",
        "url": "https://nuevo.test",
        "plataforma": "shopify",
    }
