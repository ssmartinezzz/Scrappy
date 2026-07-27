"""Presentation-only widgets for the Textual TUI.

Zero business logic: every widget here renders data it is handed by
`tui/app.py`. None of them call `core/` themselves -- the App owns all
`core/` wiring (design.md §2, ADR-001). Styling lives in the App's `CSS`
block; these classes only hold the tiny bit of state the App updates.
"""
from __future__ import annotations

from datetime import datetime

from textual.widgets import Log, Static


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


class LogTail(Log):
    """Appends one timestamped line at a time for REST results and process
    output. `Log` renders as plain text (no markup interpretation), so
    hostile payloads are inert here too. Presentation only."""

    def append_line(self, text: str) -> "LogTail":
        stamp = datetime.now().strftime("%H:%M:%S")
        return self.write_line(f"{stamp}  {text}")
