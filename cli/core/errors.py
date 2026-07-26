"""Typed exception hierarchy for the CLI's headless core.

Every `core/` module raises one of these instead of a bare `Exception`, so
presenters (`tui/`, `plain/`, slice 2) can render an actionable message
without inspecting a traceback. Each error carries a short `message` and an
optional `action` describing what the user should do next.
"""
from __future__ import annotations


class CliError(Exception):
    """Base class for all headless-core failures."""

    def __init__(self, message: str, action: str | None = None) -> None:
        self.message = message
        self.action = action
        full = message if action is None else f"{message} — {action}"
        super().__init__(full)


class ConfigError(CliError):
    """Repo-root discovery or vendored toolchain path resolution failed."""


class EnvGenError(CliError):
    """`.env` generation or reconciliation against `.env.example` failed."""


class BuildError(CliError):
    """A build step (`npm install`/`npm run build`/`mvn clean package`/jar
    copy) failed."""


class RestError(CliError):
    """A REST call to the existing backend API failed."""


class ProcessError(CliError):
    """Backend/frontend subprocess launch or teardown failed."""
