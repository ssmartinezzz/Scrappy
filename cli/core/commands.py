"""The CLI's command vocabulary — one table, three renderings.

Both presenters read this registry instead of hardcoding their own verb
lists, so the console's autocomplete, its `help` output and the plain
runner's menu can never drift apart from what dispatch actually accepts.

Headless: no `textual`/`rich` imports (see the `cli.core` package
docstring). This module knows the *names* of the operations, never how to
run them — dispatch stays in each presenter, because the console runs
commands on a worker thread while the plain runner runs them inline.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass(frozen=True)
class Command:
    """One command. `args` is the usage suffix shown after the name; empty
    for the argument-less commands. `aliases` exist for muscle memory and
    are accepted by `find()` but deliberately never offered as completions
    — the suggester should teach the canonical name."""

    name: str
    help: str
    args: str = ""
    aliases: tuple[str, ...] = field(default=())

    @property
    def usage(self) -> str:
        return f"{self.name} {self.args}".strip()


COMMANDS: tuple[Command, ...] = (
    Command("build", "compila frontend (npm) + backend (mvn) y copia el jar"),
    Command("start", "levanta backend (:3000) + frontend (:5173); buildea si falta", aliases=("up",)),
    Command("stop", "baja backend + frontend sin salir de la consola", aliases=("down",)),
    Command("status", "GET /api/status", aliases=("st",)),
    Command("scrape", "POST /api/scrape — dispara un run de scraping"),
    Command("retrain", "POST /api/ml/entrenar — reentrena texto + backfill de embeddings"),
    Command("sites", "GET /api/sitios — lista los sitios configurados", aliases=("ls",)),
    Command("add-site", "POST /api/sitios", args="<nombre> <url> [plataforma]"),
    Command("del-site", "DELETE /api/sitios/{nombre}", args="<nombre>"),
    Command("logs", "muestra el tail del log de un servicio", args="[backend|frontend] [n]"),
    Command("open", "abre el dashboard en el navegador"),
    Command("clear", "limpia la consola", aliases=("cls",)),
    Command("help", "esta ayuda", aliases=("?", "h")),
    Command("quit", "sale (baja backend + frontend antes)", aliases=("q", "exit")),
)

_BY_NAME: dict[str, Command] = {}
for _cmd in COMMANDS:
    _BY_NAME[_cmd.name] = _cmd
    for _alias in _cmd.aliases:
        _BY_NAME[_alias] = _cmd


def find(name: str) -> Optional[Command]:
    """The `Command` for a canonical name or an alias; `None` if unknown."""
    return _BY_NAME.get(name.strip().lower())


def complete(prefix: str) -> list[str]:
    """Canonical names starting with `prefix`, sorted. Prefix-only and
    case-insensitive: mid-word matching would make the ghost text rewrite
    what the user already typed."""
    lowered = prefix.strip().lower()
    return sorted(c.name for c in COMMANDS if c.name.startswith(lowered))


def help_lines() -> list[str]:
    """One `usage  ·  help` row per command, usage column padded to align."""
    width = max(len(c.usage) for c in COMMANDS)
    return [f"{c.usage.ljust(width)}   {c.help}" for c in COMMANDS]


def menu_text() -> str:
    """The registry as a plain-text block, for the non-interactive runner."""
    return "\n".join(f"  {line}" for line in help_lines())
