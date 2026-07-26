"""Presentation-only widgets for the Textual TUI.

Zero business logic: every widget here renders data it is handed by
`tui/app.py`. None of them call `core/` themselves — the App owns all
`core/` wiring (design.md §2, ADR-001).
"""
from __future__ import annotations

from textual.widgets import Log, Static


class StatusPanel(Static):
    """Renders the last-known status/result text as plain text."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__("status: unknown", *args, **kwargs)
        self.status_text = "status: unknown"

    def update_status(self, payload: object) -> None:
        self.status_text = f"status: {payload}"
        self.update(self.status_text)


class MenuPanel(Static):
    """Static reference of available key bindings — presentation only,
    the actual dispatch lives in `FashionScraperApp.BINDINGS`."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(
            "Actions -- b build | u start | s scrape | r retrain | t status\n"
            "l list sites | a add site | x delete site | o open dashboard\n"
            "q / ctrl+c  quit (tears down backend + frontend)",
            *args,
            **kwargs,
        )


class LogTail(Log):
    """Appends one line at a time for REST call results and process
    output. Presentation only; callers decide what text to append."""

    def append_line(self, text: str) -> "LogTail":
        return self.write_line(text)
