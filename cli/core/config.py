"""Repo-root discovery and vendored toolchain path resolution.

Headless: no interactive stdout, no `textual`/`rich` imports (see
`cli.core` package docstring).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from cli.core.errors import ConfigError

# Markers that uniquely identify the repo root: the two service dirs plus
# the Windows installer entry point, which only ever exists once, at root.
_ROOT_MARKER_FILE = "INSTALAR_Y_CORRER.bat"
_ROOT_MARKER_DIRS = ("scraper", "frontend")


@dataclass(frozen=True)
class ToolchainPaths:
    """Vendored toolchain paths under `<repo_root>/_tools/` — the installer
    provisions these; the CLI only ever reads from them (never downloads
    anything itself — see design.md invariant)."""

    jdk21: Path
    maven: Path
    node: Path
    cli_venv: Path
    pgsql: Path


@dataclass(frozen=True)
class Ports:
    """Fixed port assignments (design.md §5)."""

    backend: int = 3000
    frontend: int = 5173
    postgres: int = 5432


@dataclass(frozen=True)
class Config:
    repo_root: Path
    tools: ToolchainPaths
    ports: Ports


def find_repo_root(start: Path | None = None) -> Path:
    """Walk upward from `start` (default: cwd) until the repo root markers
    are found. Works from any nested cwd (e.g. `scraper/src/main/java`)."""
    current = (start or Path.cwd()).resolve()
    candidates = [current, *current.parents]
    for candidate in candidates:
        if (candidate / _ROOT_MARKER_FILE).is_file() and all(
            (candidate / marker_dir).is_dir() for marker_dir in _ROOT_MARKER_DIRS
        ):
            return candidate
    raise ConfigError(
        f"Could not locate the repo root from {current}.",
        action=(
            f"Run the CLI from inside the fashion-scraper-new repo "
            f"(looked for {_ROOT_MARKER_FILE} + {'/'.join(_ROOT_MARKER_DIRS)}/)."
        ),
    )


def resolve_toolchain_paths(repo_root: Path) -> ToolchainPaths:
    tools = repo_root / "_tools"
    return ToolchainPaths(
        jdk21=tools / "jdk21",
        maven=tools / "maven",
        node=tools / "node",
        cli_venv=tools / "cli-venv",
        pgsql=tools / "pgsql",
    )


def load_config(start: Path | None = None) -> Config:
    """Resolve the full `Config` (repo root, toolchain paths, ports) from
    an arbitrary (possibly nested) starting cwd."""
    repo_root = find_repo_root(start)
    return Config(
        repo_root=repo_root,
        tools=resolve_toolchain_paths(repo_root),
        ports=Ports(),
    )
