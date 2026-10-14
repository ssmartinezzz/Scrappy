"""CloudflareNotices: the always-on listener behind the console's Cloudflare lines."""
from __future__ import annotations

import threading

from cli.core.errors import RestError
from cli.core.notices import CloudflareNotices
from cli.core.sse import StreamEvent

WAIT = 5


class _Follower:
    """Yields its events, then parks until closed, like a live stream."""

    def __init__(self, *events, raises=None):
        self._events = list(events)
        self._raises = raises
        self.closed = threading.Event()

    def events(self):
        yield from self._events
        if self._raises:
            raise self._raises
        self.closed.wait(WAIT)

    def close(self):
        self.closed.set()


def _cf(estado="detectada", detalle="resolviendo via FlareSolverr"):
    return StreamEvent("scrape.cloudflare", {"sitio": "fullh4rd", "estado": estado, "detalle": detalle})


def _notices(followers, lines, retry=0.01):
    made = []

    def factory(_rest):
        follower = followers[min(len(made), len(followers) - 1)]
        made.append(follower)
        return follower

    def emit(level, line):
        lines.append((level, line))

    return CloudflareNotices(object(), factory, emit, retry_seconds=retry), made


def _until(predicate):
    done = threading.Event()
    for _ in range(int(WAIT / 0.01)):
        if predicate():
            return True
        done.wait(0.01)
    return False


def test_a_cloudflare_event_becomes_one_info_line():
    lines = []
    notices, _ = _notices([_Follower(_cf())], lines)
    notices.start()
    try:
        assert _until(lambda: lines)
    finally:
        notices.stop()
    assert lines == [("info", "[Cloudflare] fullh4rd: detectada — resolviendo via FlareSolverr")]


def test_a_blocked_barrier_is_an_error_line():
    lines = []
    notices, _ = _notices([_Follower(_cf("bloqueada", "no se pudo resolver el challenge"))], lines)
    notices.start()
    try:
        assert _until(lambda: lines)
    finally:
        notices.stop()
    assert lines[0][0] == "err"


def test_every_other_event_is_silent():
    lines = []
    follower = _Follower(
        StreamEvent("reconnecting", {"attempt": 1, "error": "x"}),
        StreamEvent("status", {"status": "RUNNING"}),
        StreamEvent("scrape.status", {"status": "DONE"}),
        StreamEvent("scrape.progress", {"total": 1}),
        _cf("resuelta", "clearance obtenido"),
    )
    notices, _ = _notices([follower], lines)
    notices.start()
    try:
        assert _until(lambda: lines)
    finally:
        notices.stop()
    assert [line for _, line in lines] == ["[Cloudflare] fullh4rd: resuelta — clearance obtenido"]


def test_start_twice_runs_one_follower():
    notices, made = _notices([_Follower()], [])
    notices.start()
    notices.start()
    try:
        assert _until(lambda: made)
        threading.Event().wait(0.1)
    finally:
        notices.stop()
    assert len(made) == 1


def test_stop_closes_the_follower_and_start_runs_a_fresh_one():
    first, second = _Follower(), _Follower()
    notices, made = _notices([first, second], [])
    notices.start()
    assert _until(lambda: made)
    notices.stop()
    assert first.closed.is_set()

    notices.start()
    try:
        assert _until(lambda: len(made) == 2)
    finally:
        notices.stop()
    assert made[1] is second


def test_a_dead_stream_is_retried_instead_of_giving_up():
    lines = []
    failing = _Follower(raises=RestError("backend caído", action=""))
    working = _Follower(_cf())
    notices, made = _notices([failing, working], lines)
    notices.start()
    try:
        assert _until(lambda: lines)
    finally:
        notices.stop()
    assert len(made) == 2


def test_stop_without_start_is_harmless():
    notices, _ = _notices([_Follower()], [])
    notices.stop()
