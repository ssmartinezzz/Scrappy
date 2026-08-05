"""Presentation-only widgets for the command console.

Zero business logic: every widget here renders data it is handed by
`tui/app.py`, or (for `CommandSuggester`) reads the shared command registry
in `core/commands.py`. None of them call the rest of `core/` themselves —
the App owns all `core/` wiring (design.md §2, ADR-001). Styling lives in
the App's `CSS` block; these classes hold only the tiny bit of state the
App updates.

Two rendering rules that are load-bearing, not stylistic:

* **Payloads are never markup.** REST results, site names and error strings
  are data. They are appended to a `rich.text.Text` with an explicit style
  instead of being interpolated into a markup string, so a `[red]` inside a
  product name renders literally and can never restyle the console.
* **Chrome is one row each.** The status bar and the hint bar are single
  lines by construction. That is what lets the console run in a small
  terminal instead of demanding a maximized window.
"""
from __future__ import annotations

import json
from datetime import datetime
from typing import Iterable, Optional

from rich.text import Text
from textual.suggester import Suggester
from textual.widgets import RichLog, Static

from cli.core.commands import complete
from cli.core.health import Check

# kind -> (gutter mark, style for the text). `cmd` echoes what the user
# typed; `out` is a result; `err` a failure; `info` a note; `raw` a line
# read back from a service log file.
_KINDS = {
    "cmd": ("❯", "bold #7dd3fc"),
    "out": ("·", "#d7e3ea"),
    "err": ("✗", "bold #ff6b6b"),
    "info": ("›", "#9aa7b0"),
    "raw": (" ", "dim #9aa7b0"),
}


def format_payload(payload: object) -> str:
    """Render a REST payload for the console.

    Dicts and lists come back as JSON — indented, accents intact — instead
    of a Python `repr`, because `{'estado': 'ocioso'}` is an implementation
    detail leaking into the UI and quoted keys read worse in a narrow
    terminal. Anything not JSON-serialisable falls back to `str()`: a
    formatter must never be the thing that fails.
    """
    if isinstance(payload, str):
        return payload
    try:
        if isinstance(payload, (list, tuple)):
            # One compact entry per line. Fully indenting a 20-site list
            # costs 80 lines of scroll to say very little; this keeps a
            # list scannable while staying valid JSON.
            if not payload:
                return "[]"
            rows = [json.dumps(item, ensure_ascii=False) for item in payload]
            body = ",\n".join(f"  {row}" for row in rows)
            return f"[\n{body}\n]"
        if isinstance(payload, dict):
            return json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=False)
    except (TypeError, ValueError):
        return str(payload)
    return str(payload)


class StatusBar(Static):
    """One-line health strip: `● pg  ● api  ○ web  ● jar …`.

    `Check` values are entirely CLI-derived (short labels, `:port`), never
    user input, so Rich markup is safe here — unlike the console, which
    renders arbitrary payloads.

    `line()` is a pure string builder (no widget state) so it is testable
    without mounting; `last_line` records what was last rendered."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.last_line = "consultando…"
        self.update(self.last_line)

    @staticmethod
    def line(checks: Iterable[Check]) -> str:
        cells = []
        for c in checks:
            mark = "[b green]●[/]" if c.ok else "[b red]○[/]"
            cells.append(f"{mark} {c.short or c.name}")
        return "  ".join(cells) if cells else "sin datos"

    def update_health(self, checks: Iterable[Check]) -> None:
        self.last_line = self.line(checks)
        self.update(self.last_line)


class HintBar(Static):
    """One-line contextual hint under the prompt: the usage of the verb
    currently being typed, or the default nudge toward `help`."""

    DEFAULT = "tab completa · ↑↓ historial · help lista todo · ctrl+c sale"

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.text = self.DEFAULT
        self.update(self.text)

    def show(self, text: Optional[str] = None) -> None:
        self.text = text or self.DEFAULT
        self.update(self.text)


class Console(RichLog):
    """The scrollback. Everything the CLI has to say lands here.

    Constructed with `markup=False` and a small `min_width` so it wraps to
    a narrow terminal instead of forcing horizontal scroll. `history` keeps
    the plain text of every line for tests and for `clear`."""

    def __init__(self, *args, **kwargs) -> None:
        kwargs.setdefault("markup", False)
        kwargs.setdefault("highlight", False)
        kwargs.setdefault("wrap", True)
        kwargs.setdefault("min_width", 20)
        super().__init__(*args, **kwargs)
        self.history: list[str] = []

    @staticmethod
    def build_line(
        kind: str,
        text: str,
        when: Optional[datetime] = None,
        stamped: bool = True,
    ) -> Text:
        """`HH:MM:SS mark text` as a styled `Text`. The payload is appended
        as a separate span with its own style — never formatted into a
        markup string — so it cannot inject styling.

        `stamped=False` blanks the clock column (keeping its width) for the
        continuation lines of a multi-line result: repeating the same
        timestamp down the left edge of a JSON block is pure noise."""
        mark, style = _KINDS.get(kind, _KINDS["out"])
        stamp = (when or datetime.now()).strftime("%H:%M:%S")
        line = Text(no_wrap=False)
        line.append(stamp if stamped else " " * len(stamp), style="dim #5b6b76")
        line.append(f" {mark} " if stamped else "   ", style=style)
        line.append(text, style=style)
        return line

    def emit(self, kind: str, text: str) -> None:
        """Write one payload, which may span several lines. Only the first
        line carries the clock and the gutter mark; the rest align under it
        so a JSON block reads as one block."""
        chunks = str(text).splitlines() or [""]
        for index, chunk in enumerate(chunks):
            line = self.build_line(kind, chunk, stamped=index == 0)
            self.history.append(line.plain)
            self.write(line)

    def wipe(self) -> None:
        self.history.clear()
        self.clear()


def verb_completion(value: str) -> Optional[str]:
    """The single completion for a partially typed line, or `None`.

    Completes the verb and only the verb: once the value contains a space
    the user is writing arguments, and completing those against command
    names would rewrite what they typed.

    Shared by `CommandSuggester` (ghost text) and the prompt's Tab action,
    so pressing Tab always accepts exactly what the ghost text offered —
    with no dependency on the async suggestion having landed yet.
    """
    if not value or value != value.lstrip() or " " in value:
        return None
    matches = complete(value)
    return matches[0] if matches else None


class CommandSuggester(Suggester):
    """Ghost-text completion for the verb (see `verb_completion`)."""

    def __init__(self) -> None:
        super().__init__(use_cache=True, case_sensitive=False)

    async def get_suggestion(self, value: str) -> Optional[str]:
        return verb_completion(value)
