"""Presentation-only widgets for the Textual TUI.

Zero business logic: every widget here renders data it is handed by
`tui/app.py`. None of them call `core/` themselves -- the App owns all
`core/` wiring (design.md §2, ADR-001). Styling lives in the App's `CSS`
block; these classes only hold the tiny bit of state the App updates.
"""
from __future__ import annotations

from datetime import datetime
from typing import Iterable

from textual.widgets import Log, Static

from cli.core.health import Check


class StatusPanel(Static):
    """Renders the last-known status/result as plain text (no Rich markup,
    so an arbitrary payload string can never inject markup). The visual
    treatment -- border, title, colour -- is pure CSS in `tui/app.py`."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.status_text = "sin consultar todavía — apretá  t  (status)"
        self.update(self.status_text)

    def update_status(self, payload: object) -> None:
        self.status_text = f"status: {payload}"
        self.update(self.status_text)


class HealthPanel(Static):
    """Renders the local build + service health report as a column of
    coloured indicator rows. `Check` values are entirely CLI-derived (path
    labels, `:port`), never user input, so Rich markup here is safe.

    `rows()` is a pure string builder (no widget state) so it is testable
    without mounting; `last_rows` records what was last rendered for the
    same reason."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.last_rows = "consultando…"
        self.update(self.last_rows)

    @staticmethod
    def rows(checks: Iterable[Check]) -> str:
        out = []
        for c in checks:
            mark = "[b green]●[/]" if c.ok else "[b red]○[/]"
            out.append(f"{mark} {c.name:<15}[dim]{c.detail}[/]")
        return "\n".join(out) if out else "sin datos"

    def update_health(self, checks: Iterable[Check]) -> None:
        self.last_rows = self.rows(checks)
        self.update(self.last_rows)


class LogTail(Log):
    """Appends one timestamped line at a time for REST results and process
    output. `Log` renders as plain text (no markup interpretation), so
    hostile payloads are inert here too. Presentation only."""

    def append_line(self, text: str) -> "LogTail":
        stamp = datetime.now().strftime("%H:%M:%S")
        return self.write_line(f"{stamp}  {text}")
