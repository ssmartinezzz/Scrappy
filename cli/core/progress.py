"""Scrape progress model and plain-text rendering (headless: no textual/rich).

Fed with the payloads `cli.core.sse.EventFollower` yields, it mirrors what the
frontend reducer keeps. The percentage is only ever finished sites over total
sites: no timers, no caps, so it can go down when a new run starts.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Optional

TERMINAL_STATES = frozenset({"done", "error", "skipped"})
BAR_WIDTH = 20
ERROR_WIDTH = 40


@dataclass(frozen=True)
class SiteRow:
    nombre: str
    estado: str
    count: Optional[int]  # None until the site finishes; the backend sends 0 before
    dur_ms: int
    error: Optional[str]
    running_for: Optional[float]  # seconds since this view first saw it en_curso


class ScrapeView:
    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self.status = ""
        self.mensaje = ""
        self.started_at: Optional[str] = None
        self.progreso: Optional[dict] = None
        self._seen_running: dict[str, float] = {}
        self._run_seen_at: Optional[float] = None

    def apply(self, name: str, data: dict[str, Any]) -> None:
        if name == "status":
            self._set_status(data.get("status", ""), data.get("mensaje", ""))
            self.started_at = (data.get("run") or {}).get("startedAt")
            self._set_progreso(data.get("progreso"))
        elif name == "scrape.status":
            restarted = data.get("status") == "RUNNING" and self.status != "RUNNING"
            self._set_status(data.get("status", ""), data.get("mensaje", ""))
            if restarted:
                self.started_at = None
                self._set_progreso(None)
        elif name == "scrape.progress":
            self._set_progreso(data)

    def _set_status(self, status: str, mensaje: str) -> None:
        if status == "RUNNING" and self.status != "RUNNING":
            self._run_seen_at = self._clock()
        self.status, self.mensaje = status, mensaje

    def _set_progreso(self, progreso: Optional[dict]) -> None:
        self.progreso = progreso
        now = self._clock()
        en_curso = {s["nombre"] for s in self._sitios() if s.get("estado") == "en_curso"}
        self._seen_running = {n: self._seen_running.get(n, now) for n in en_curso}

    def _sitios(self) -> list[dict]:
        return (self.progreso or {}).get("sitios") or []

    @property
    def running(self) -> bool:
        return self.status == "RUNNING"

    @property
    def total(self) -> int:
        return int((self.progreso or {}).get("total") or len(self._sitios()))

    @property
    def terminal(self) -> int:
        return sum(1 for s in self._sitios() if s.get("estado") in TERMINAL_STATES)

    @property
    def productos(self) -> int:
        return int((self.progreso or {}).get("productos") or 0)

    @property
    def percent(self) -> int:
        return self.terminal * 100 // self.total if self.total else 0

    def elapsed(self, now: Optional[datetime] = None) -> Optional[float]:
        if self.started_at and now is not None:
            try:
                return max(0.0, (now - datetime.fromisoformat(self.started_at)).total_seconds())
            except ValueError:
                pass
        if self._run_seen_at is not None:
            return self._clock() - self._run_seen_at
        return None

    def rows(self) -> list[SiteRow]:
        now = self._clock()
        rows = []
        for s in self._sitios():
            estado = s.get("estado", "")
            seen = self._seen_running.get(s["nombre"])
            rows.append(SiteRow(
                nombre=s["nombre"],
                estado=estado,
                count=int(s.get("count") or 0) if estado == "done" else None,
                dur_ms=int(s.get("durMs") or 0),
                error=s.get("error"),
                running_for=now - seen if seen is not None else None,
            ))
        return rows


def _duration(seconds: float) -> str:
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes, secs = divmod(int(seconds), 60)
    return f"{minutes}m{secs:02d}s"


def _clock_text(seconds: float) -> str:
    minutes, secs = divmod(int(seconds), 60)
    return f"{minutes}m{secs:02d}s"


def summary_line(view: ScrapeView, now: Optional[datetime] = None) -> str:
    filled = view.percent * BAR_WIDTH // 100
    bar = "#" * filled + "-" * (BAR_WIDTH - filled)
    parts = [f"[{bar}] {view.percent:3d}%", f"{view.terminal}/{view.total} sitios",
             f"{view.productos} productos"]
    elapsed = view.elapsed(now)
    if elapsed is not None:
        parts.append(_clock_text(elapsed))
    parts.append(view.status)
    return " · ".join(parts)


def site_line(row: SiteRow, live: bool = True) -> str:
    """One row. `live=False` omits the since-first-seen label, which differs on
    every render and would make a log-style printer repeat the row."""
    if row.dur_ms > 0:
        dur = _duration(row.dur_ms / 1000)
    elif live and row.estado == "en_curso" and row.running_for is not None:
        dur = f"hace {int(row.running_for)}s"
    else:
        dur = ""
    count = "" if row.count is None else str(row.count)
    line = f"  {row.nombre:<24} {row.estado:<9} {count:>7}  {dur}".rstrip()
    if row.error:
        err = row.error if len(row.error) <= ERROR_WIDTH else row.error[: ERROR_WIDTH - 3] + "..."
        line += f"  {err}"
    return line


def render_lines(view: ScrapeView, now: Optional[datetime] = None) -> list[str]:
    if view.progreso is None:
        return [f"{view.status} · {view.mensaje}".rstrip(" ·")]
    return [summary_line(view, now), *(site_line(r) for r in view.rows())]


def cloudflare_line(payload: Any) -> str:
    """One console line for a `scrape.cloudflare` event; missing fields are skipped."""
    data = payload if isinstance(payload, dict) else {}
    sitio = data.get("sitio")
    if not sitio:
        return "[Cloudflare] sitio desconocido"
    line = f"[Cloudflare] {sitio}"
    if data.get("estado"):
        line += f": {data['estado']}"
    if data.get("detalle"):
        line += f" — {data['detalle']}"
    return line


class ChangeLog:
    """Log-style output: each call returns only the lines that changed since
    the previous one, so a piped `watch` is a readable transcript instead of a
    repeated table."""

    def __init__(self) -> None:
        self._summary: Optional[tuple] = None
        self._rows: dict[str, tuple] = {}

    def lines(self, view: ScrapeView, now: Optional[datetime] = None) -> list[str]:
        if view.progreso is None:
            return []
        out = []
        key = (view.status, view.terminal, view.total, view.productos)
        if key != self._summary:
            self._summary = key
            out.append(summary_line(view, now))
        for row in view.rows():
            row_key = (row.estado, row.count, row.dur_ms, row.error)
            if self._rows.get(row.nombre) != row_key:
                self._rows[row.nombre] = row_key
                out.append(site_line(row, live=False))
        return out
