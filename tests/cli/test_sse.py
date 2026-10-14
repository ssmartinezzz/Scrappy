"""Tests for cli.core.sse — the SSE wire parser and the reconnecting follower
over `GET /api/events` (backend: EventsController / StatusEventJson)."""
from __future__ import annotations

import io
import json
import urllib.error

import pytest

from cli.core.errors import RestError
from cli.core.rest import RestClient
from cli.core.sse import EventFollower, SseEvent, parse_sse


def _lines(text: str) -> io.BytesIO:
    return io.BytesIO(text.encode("utf-8"))


def _sse(*events: tuple[str, object]) -> list[bytes]:
    """A wire-format stream: one `event:`/`data:` block per entry."""
    out: list[bytes] = []
    for name, data in events:
        out.append(f"event: {name}\n".encode())
        out.append(f"data: {json.dumps(data)}\n".encode())
        out.append(b"\n")
    return out


# -- parser -----------------------------------------------------------------


def test_parser_dispatches_an_event_on_the_blank_line():
    events = list(parse_sse(_lines('event: scrape.status\ndata: {"status":"RUNNING"}\n\n')))

    assert events == [SseEvent("scrape.status", '{"status":"RUNNING"}')]


def test_parser_joins_multi_line_data_with_newlines():
    events = list(parse_sse(_lines("event: x\ndata: uno\ndata: dos\n\n")))

    assert events[0].data == "uno\ndos"


def test_parser_ignores_comments_and_pings():
    events = list(parse_sse(_lines(": ping\n\n:otro\nevent: resync\ndata: {}\n\n")))

    assert events == [SseEvent("resync", "{}")]


def test_parser_defaults_the_name_to_message():
    assert list(parse_sse(_lines("data: hola\n\n"))) == [SseEvent("message", "hola")]


def test_parser_accepts_crlf_and_a_field_without_space_after_the_colon():
    events = list(parse_sse(_lines("event:a\r\ndata:b\r\n\r\n")))

    assert events == [SseEvent("a", "b")]


def test_parser_drops_an_event_cut_off_before_its_blank_line():
    assert list(parse_sse(_lines('event: a\ndata: {"x":1}\n'))) == []


def test_parser_decodes_utf8_and_ignores_unknown_fields():
    events = list(parse_sse(_lines("id: 7\nretry: 100\nevent: a\ndata: Móvil\n\n")))

    assert events == [SseEvent("a", "Móvil")]


# -- RestClient.open_stream ---------------------------------------------------


class _Resp:
    def __init__(self, lines=()):
        self._lines = list(lines)
        self.closed = False

    def __iter__(self):
        return iter(self._lines)

    def read(self):
        return b'{"accessToken":"tok","expiresIn":3600}'

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def test_open_stream_logs_in_and_sends_bearer_and_event_stream_accept():
    seen = []

    def opener(request, timeout=None):
        seen.append(request)
        return _Resp()

    rest = RestClient("http://x", opener=opener, username="svc", password="pw")
    rest.open_stream("/api/events")

    login, stream = seen
    assert login.full_url.endswith("/api/auth/login")
    assert stream.full_url == "http://x/api/events"
    assert stream.get_header("Authorization") == "Bearer tok"
    assert stream.get_header("Accept") == "text/event-stream"


def test_open_stream_relogs_in_once_on_a_401():
    calls = []

    def opener(request, timeout=None):
        calls.append(request.full_url)
        if request.full_url.endswith("/api/events") and calls.count("http://x/api/events") == 1:
            raise urllib.error.HTTPError(request.full_url, 401, "no", {}, io.BytesIO(b"{}"))
        return _Resp()

    rest = RestClient("http://x", opener=opener, username="svc", password="pw")
    rest.open_stream("/api/events")

    assert calls.count("http://x/api/auth/login") == 2


# -- follower -----------------------------------------------------------------


class _FakeRest:
    """`open_stream` plays a script: a list of byte lines, or an exception."""

    def __init__(self, *script, status=None):
        self.script = list(script)
        self.opened = 0
        self.status_calls = 0
        self._status = status or {"status": "RUNNING"}

    def open_stream(self, path):
        assert path == "/api/events"
        self.opened += 1
        step = self.script.pop(0)
        if isinstance(step, Exception):
            raise step
        return _Resp(step)

    def status(self):
        self.status_calls += 1
        return self._status


def _follower(rest, **kw):
    sleeps: list[float] = []
    kw.setdefault("sleep", sleeps.append)
    return EventFollower(rest, **kw), sleeps


def test_follower_turns_the_snapshot_into_a_status_event():
    snapshot = {"status": {"status": "RUNNING", "progreso": {"total": 2}}, "ml": {}}
    rest = _FakeRest(_sse(("snapshot", snapshot), ("scrape.status", {"status": "DONE", "mensaje": "ok"})))
    follower, _ = _follower(rest, max_failures=1)

    events = []
    with pytest.raises(RestError):
        for ev in follower.events():
            events.append(ev)

    assert [(e.name, e.data) for e in events[:2]] == [
        ("status", {"status": "RUNNING", "progreso": {"total": 2}}),
        ("scrape.status", {"status": "DONE", "mensaje": "ok"}),
    ]


def test_follower_passes_progress_and_drops_events_it_does_not_follow():
    progress = {"total": 3, "completados": 1, "productos": 10, "sitios": []}
    rest = _FakeRest(_sse(("ml.status", {"running": True}), ("db.changed", {"table": "x"}),
                          ("scrape.progress", progress)))
    follower, _ = _follower(rest, max_failures=1)
    gen = follower.events()

    ev = next(gen)

    assert (ev.name, ev.data) == ("scrape.progress", progress)


def test_follower_passes_the_cloudflare_event():
    notice = {"sitio": "fullh4rd", "estado": "detectada", "detalle": "resolviendo via FlareSolverr"}
    rest = _FakeRest(_sse(("scrape.cloudflare", notice)))
    follower, _ = _follower(rest, max_failures=1)

    ev = next(follower.events())

    assert (ev.name, ev.data) == ("scrape.cloudflare", notice)


def test_follower_rereads_status_on_resync():
    rest = _FakeRest(_sse(("resync", {})), status={"status": "DONE", "mensaje": "fin"})
    follower, _ = _follower(rest, max_failures=1)
    gen = follower.events()

    ev = next(gen)

    assert (ev.name, ev.data) == ("status", {"status": "DONE", "mensaje": "fin"})
    assert rest.status_calls == 1


def test_follower_reconnects_with_backoff_and_resyncs_status():
    rest = _FakeRest([], _sse(("scrape.status", {"status": "RUNNING", "mensaje": ""})))
    follower, sleeps = _follower(rest, backoff=(1.0, 2.0), max_failures=3)
    gen = follower.events()

    first = next(gen)
    second = next(gen)
    third = next(gen)

    assert first.name == "reconnecting" and first.data["attempt"] == 1
    assert second.name == "status"  # status re-read before reopening
    assert third.name == "scrape.status"
    assert sleeps == [1.0]
    assert rest.opened == 2


def test_follower_backoff_grows_and_gives_up_after_max_failures():
    err = RestError("down", action="x")
    rest = _FakeRest(err, err, err)
    follower, sleeps = _follower(rest, backoff=(1.0, 2.0), max_failures=3)

    with pytest.raises(RestError):
        for _ in follower.events():
            pass

    assert sleeps == [1.0, 2.0]
    assert rest.opened == 3


def test_follower_close_ends_the_iteration():
    rest = _FakeRest(_sse(("scrape.status", {"status": "RUNNING", "mensaje": ""})))
    follower, _ = _follower(rest)
    gen = follower.events()
    next(gen)

    follower.close()

    assert list(gen) == []
