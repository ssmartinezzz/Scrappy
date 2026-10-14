"""Always-on listener for the backend's `scrape.cloudflare` events.

`watch` only follows the stream while a scrape is being watched; a Cloudflare
barrier is worth a console line even when nobody is. This keeps its own
follower alive from `start` until `stop`/quit and hands each notice to `emit`.
The backend takes seconds to boot, so connection trouble is retried silently.
"""
from __future__ import annotations

import threading
from typing import Callable, Optional

from cli.core.progress import cloudflare_line
from cli.core.sse import EventFollower

RETRY_SECONDS = 5.0
JOIN_SECONDS = 2.0


class CloudflareNotices:
    def __init__(
        self,
        rest,
        follower_factory: Callable[[object], EventFollower],
        emit: Callable[[str, str], object],
        *,
        retry_seconds: float = RETRY_SECONDS,
    ) -> None:
        self._rest = rest
        self._factory = follower_factory
        self._emit = emit
        self._retry = retry_seconds
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None
        self._stop: Optional[threading.Event] = None
        self._follower = None

    def start(self) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            stop = self._stop = threading.Event()
            self._thread = threading.Thread(
                target=self._run, args=(stop,), name="cloudflare-notices", daemon=True
            )
            self._thread.start()

    def stop(self) -> None:
        with self._lock:
            thread, stop, follower = self._thread, self._stop, self._follower
            self._thread = self._stop = self._follower = None
        if stop is None:
            return
        stop.set()
        if follower is not None:
            follower.close()
        if thread is not None and thread is not threading.current_thread():
            thread.join(JOIN_SECONDS)

    def _run(self, stop: threading.Event) -> None:
        while not stop.is_set():
            try:
                self._follow_once(stop)
            except Exception:  # noqa: BLE001 - a notice listener must never take the console down
                pass
            stop.wait(self._retry)

    def _follow_once(self, stop: threading.Event) -> None:
        follower = self._factory(self._rest)
        try:
            with self._lock:
                if stop.is_set():
                    return
                self._follower = follower
            for event in follower.events():
                if event.name == "scrape.cloudflare":
                    self._notify(event.data)
        finally:
            follower.close()

    def _notify(self, payload) -> None:
        bloqueada = isinstance(payload, dict) and payload.get("estado") == "bloqueada"
        self._emit("err" if bloqueada else "info", cloudflare_line(payload))
