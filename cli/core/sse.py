"""Server-sent events client for `GET /api/events` (stdlib only).

`parse_sse` is the pure wire-format parser. `EventFollower` layers the
session on top: it authenticates through `RestClient`, reconnects with backoff
and re-reads `GET /api/status` on every `resync` and every reconnect, so a
consumer only ever sees full-state `status` events plus the incremental ones.
"""
from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Iterator, Optional

from cli.core.errors import RestError

EVENTS_PATH = "/api/events"
BACKOFF_SECONDS = (1.0, 2.0, 4.0, 8.0, 15.0)
MAX_FAILURES = 6

# Everything else on the stream (ml.status, db.changed) is not scrape state.
# `scrape.cloudflare` is no state either: only the notice follower reads it.
_FOLLOWED = {"scrape.status", "scrape.progress", "scrape.cloudflare"}


@dataclass(frozen=True)
class SseEvent:
    name: str
    data: str


@dataclass(frozen=True)
class StreamEvent:
    """What the follower yields. `status` carries a full `/api/status`
    payload; `reconnecting` carries `{attempt, error}`; the rest are the
    server's own `scrape.status` / `scrape.progress` payloads."""

    name: str
    data: Any


def parse_sse(lines: Iterable[bytes]) -> Iterator[SseEvent]:
    """Yield one `SseEvent` per blank-line-terminated block. Comments (`:`),
    `id` and `retry` are ignored; an event cut off before its blank line is
    dropped, as the SSE spec says."""
    name = "message"
    data: list[str] = []
    for raw in lines:
        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
        if not line:
            if data:
                yield SseEvent(name, "\n".join(data))
            name, data = "message", []
            continue
        if line.startswith(":"):
            continue
        field, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field == "event":
            name = value
        elif field == "data":
            data.append(value)


class EventFollower:
    """Follows the status stream until `close()` or until the backend stays
    unreachable for `max_failures` consecutive attempts."""

    def __init__(
        self,
        rest,
        *,
        backoff: tuple[float, ...] = BACKOFF_SECONDS,
        max_failures: int = MAX_FAILURES,
        sleep: Optional[Callable[[float], object]] = None,
    ) -> None:
        self._rest = rest
        self._backoff = backoff
        self._max_failures = max_failures
        self._stop = threading.Event()
        self._sleep = sleep or self._stop.wait
        self._response = None

    def close(self) -> None:
        """Safe from any thread; also unblocks a read waiting on the socket."""
        self._stop.set()
        response = self._response
        if response is not None:
            try:
                response.close()
            except OSError:
                pass

    def events(self) -> Iterator[StreamEvent]:
        failures = 0
        reconnect = False
        while not self._stop.is_set():
            error: Exception
            try:
                if reconnect:
                    yield StreamEvent("status", self._rest.status())
                self._response = self._rest.open_stream(EVENTS_PATH)
                for sse in parse_sse(self._response):
                    failures = 0
                    converted = self._convert(sse)
                    if converted is not None:
                        yield converted
                error = RestError("el stream de eventos se cerró", action="")
            except (RestError, OSError, ValueError) as exc:
                if self._stop.is_set():
                    return
                error = exc
            finally:
                self._close_response()

            if self._stop.is_set():
                return
            failures += 1
            if failures >= self._max_failures:
                raise error if isinstance(error, RestError) else RestError(str(error), action="")
            yield StreamEvent("reconnecting", {"attempt": failures, "error": str(error)})
            self._sleep(self._backoff[min(failures - 1, len(self._backoff) - 1)])
            reconnect = True

    def _convert(self, sse: SseEvent) -> Optional[StreamEvent]:
        if sse.name == "resync":
            return StreamEvent("status", self._rest.status())
        if sse.name != "snapshot" and sse.name not in _FOLLOWED:
            return None
        try:
            payload = json.loads(sse.data)
        except ValueError:
            return None
        if sse.name == "snapshot":
            status = payload.get("status") if isinstance(payload, dict) else None
            return StreamEvent("status", status) if isinstance(status, dict) else None
        return StreamEvent(sse.name, payload)

    def _close_response(self) -> None:
        response, self._response = self._response, None
        if response is not None:
            try:
                response.close()
            except OSError:
                pass
