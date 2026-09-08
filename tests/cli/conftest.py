"""Shared pytest fixtures for the native CLI test suite (`tests/cli/`).

Mirrors `ml-tests/conftest.py`'s sys.path pattern: tests run in-place
against the same source the CLI actually ships (`cli/`), no packaging step
required — matches how the CLI is invoked directly
(`python cli/__main__.py`, slice 2).
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))


@pytest.fixture(autouse=True)
def _no_real_docker(monkeypatch):
    """Every LAN-proxy test passes a fake `runner=`. If a call site ever
    forgets it, this makes the real `_run_docker` raise instead of shelling
    out for real — a container is cheap to start and expensive to leave
    running on a developer's machine, replacing whatever `start lan` had up.

    Guards `_run_docker` itself, not just `subprocess.run`, so the raise
    happens before any argv even reaches a shell."""

    def _blocked(argv, **kwargs):
        raise RuntimeError(
            "real Docker reached from a test — pass a fake runner"
        )

    monkeypatch.setattr("cli.core.lan_proxy._run_docker", _blocked)


@pytest.fixture
def repo_root() -> Path:
    """The real fashion-scraper-new repo root backing this checkout."""
    return _REPO_ROOT


@pytest.fixture
def fake_repo(tmp_path: Path) -> Path:
    """Minimal fake repo tree satisfying `find_repo_root`'s markers:
    `scraper/`, `frontend/`, `INSTALAR_Y_CORRER.bat`."""
    (tmp_path / "scraper").mkdir()
    (tmp_path / "frontend").mkdir()
    (tmp_path / "INSTALAR_Y_CORRER.bat").write_text("@echo off\n", encoding="utf-8")
    return tmp_path
