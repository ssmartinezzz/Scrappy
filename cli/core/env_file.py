"""Template-driven `.env` generation (LD-2 / ADR-003, design.md §3).

`.env.example` is the schema. The known ("computed") keys get real values
derived from the resolved `Config`; every other key passes through with its
example default. Generation is create-if-absent + additive-reconcile +
never-overwrite-existing-keys, with an explicit `--regenerate`/`--force`
escape hatch. Secret values (`DATABASE_PASSWORD`) are written to disk but
NEVER logged — only key *names* are ever logged, never values, which is a
strictly stronger guarantee than the spec's "no log line contains the
DATABASE_PASSWORD value" requirement.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Union

from cli.core.config import Config
from cli.core.errors import EnvGenError

logger = logging.getLogger(__name__)

# Credential-bearing keys — documented here so any future caller building a
# custom logger knows which keys must never be logged by value.
SECRET_KEYS = frozenset({"DATABASE_PASSWORD"})


@dataclass(frozen=True)
class _CommentLine:
    """A verbatim line from `.env.example` that isn't a `KEY=value` pair
    (comment, blank line, or malformed line) — preserved as-is on write."""

    text: str


@dataclass(frozen=True)
class _KeyLine:
    key: str
    default: str


_SchemaLine = Union[_CommentLine, _KeyLine]


def parse_keys(example_path: Path) -> list[_SchemaLine]:
    """Parse `.env.example` into an ordered, comment-preserving schema."""
    if not example_path.is_file():
        raise EnvGenError(
            f"Template file not found: {example_path}",
            action="Ensure .env.example exists at the repo root before running the env step.",
        )
    lines: list[_SchemaLine] = []
    for raw in example_path.read_text(encoding="utf-8").splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#") or "=" not in raw:
            lines.append(_CommentLine(raw))
            continue
        key, _, value = raw.partition("=")
        lines.append(_KeyLine(key.strip(), value.strip()))
    return lines


def parse_env(env_path: Path) -> dict[str, str]:
    """Parse an existing `.env` into a flat `key -> value` dict (comments
    and blank/malformed lines are ignored). Returns `{}` if absent."""
    values: dict[str, str] = {}
    if not env_path.is_file():
        return values
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#") or "=" not in raw:
            continue
        key, _, value = raw.partition("=")
        values[key.strip()] = value.strip()
    return values


def compute_defaults(cfg: Config) -> dict[str, str]:
    """Build the CLI-known computed key set from a resolved `Config`
    (installer-provisioned Postgres port, resolved repo paths, service
    ports) — the set named in env-file-generation's "Create-If-Absent Env
    Generation" requirement."""
    models_root = cfg.repo_root / "scraper" / "_models"
    return {
        "DATABASE_URL": f"jdbc:postgresql://localhost:{cfg.ports.postgres}/scraper",
        "DATABASE_USERNAME": "postgres",
        "DATABASE_PASSWORD": "",  # portable Postgres is provisioned with `initdb -A trust`
        "SCRAPER_MODELS_ROOT": str(models_root),
        "APP_CORS_ALLOWED_ORIGINS": f"http://localhost:{cfg.ports.frontend}",
        "VITE_API_BASE_URL": f"http://localhost:{cfg.ports.backend}",
        "APP_OPEN_URL": f"http://localhost:{cfg.ports.frontend}",
    }


def _resolve(key: str, default: str, computed: dict[str, str]) -> str:
    return computed[key] if key in computed else default


def generate_env(
    example_path: Path,
    env_path: Path,
    computed: dict[str, str],
    force: bool = False,
) -> None:
    """Create or reconcile `.env` against the `.env.example` schema.

    - No `.env`, or `force=True`: write every schema key fresh — computed
      values win, everything else passes through with its example default.
    - Existing `.env`, `force=False`: only append keys present in the
      schema but absent from `.env`; every existing key/value is left
      byte-identical (never touched, never reordered, never rewritten).
    """
    schema = parse_keys(example_path)

    if force or not env_path.is_file():
        out_lines: list[str] = []
        touched_keys: list[str] = []
        for line in schema:
            if isinstance(line, _CommentLine):
                out_lines.append(line.text)
                continue
            value = _resolve(line.key, line.default, computed)
            out_lines.append(f"{line.key}={value}")
            touched_keys.append(line.key)
        env_path.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
        logger.info(
            "Generated %s from %s (force=%s, %d keys): %s",
            env_path, example_path, force, len(touched_keys), ", ".join(touched_keys),
        )
        return

    existing_text = env_path.read_text(encoding="utf-8")
    existing_keys = parse_env(env_path)
    appended: list[str] = []
    for line in schema:
        if isinstance(line, _KeyLine) and line.key not in existing_keys:
            value = _resolve(line.key, line.default, computed)
            appended.append(f"{line.key}={value}")

    if not appended:
        logger.info("No missing keys to reconcile in %s", env_path)
        return

    if existing_text and not existing_text.endswith("\n"):
        existing_text += "\n"
    env_path.write_text(existing_text + "\n".join(appended) + "\n", encoding="utf-8")
    logger.info(
        "Reconciled %s: appended %d missing key(s): %s",
        env_path, len(appended), ", ".join(entry.split("=", 1)[0] for entry in appended),
    )
