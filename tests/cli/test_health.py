"""Tests for cli.core.health — build/service signals with an injected
socket connect (no real ports, deterministic)."""
from __future__ import annotations

from pathlib import Path

from cli.core.config import Config, Ports, ToolchainPaths
from cli.core.health import (
    Check,
    build_checks,
    health_report,
    port_open,
    service_checks,
)


def _cfg(root: Path) -> Config:
    tools = ToolchainPaths(
        jdk21=root / "_tools" / "jdk21",
        maven=root / "_tools" / "maven",
        node=root / "_tools" / "node",
        cli_venv=root / "_tools" / "cli-venv",
        pgsql=root / "_tools" / "pgsql",
    )
    return Config(repo_root=root, tools=tools, ports=Ports())


def test_port_open_uses_injected_connect_true():
    calls: list = []

    def fake(host, port, timeout):
        calls.append((host, port, timeout))
        return True

    assert port_open("localhost", 3000, connect=fake) is True
    assert calls == [("localhost", 3000, 0.35)]


def test_port_open_uses_injected_connect_false():
    assert port_open("localhost", 5432, connect=lambda *a: False) is False


def test_build_checks_reflect_missing_artifacts(tmp_path: Path):
    checks = {c.name: c for c in build_checks(_cfg(tmp_path))}
    # Nothing created yet -> every build signal is not-ok.
    assert checks[".env"].ok is False
    assert checks["backend jar"].ok is False
    assert checks["frontend build"].ok is False


def test_build_checks_reflect_present_artifacts(tmp_path: Path):
    (tmp_path / ".env").write_text("X=1", encoding="utf-8")
    (tmp_path / "frontend").mkdir()
    (tmp_path / "frontend" / ".env").write_text("Y=1", encoding="utf-8")
    (tmp_path / "scraper").mkdir()
    (tmp_path / "scraper" / "scraper.jar").write_text("jar", encoding="utf-8")
    (tmp_path / "frontend" / "dist").mkdir()

    checks = {c.name: c for c in build_checks(_cfg(tmp_path))}
    assert checks[".env"].ok is True
    assert checks["frontend/.env"].ok is True
    assert checks["backend jar"].ok is True
    assert checks["frontend build"].ok is True


def test_service_checks_probe_each_port(tmp_path: Path):
    probed: list[int] = []

    def fake(host, port, timeout):
        probed.append(port)
        return port == 3000  # only backend "up"

    checks = {c.name: c for c in service_checks(_cfg(tmp_path), connect=fake)}
    assert probed == [5432, 3000, 5173]
    assert checks["Backend"].ok is True
    assert checks["Postgres"].ok is False
    assert checks["Frontend"].ok is False
    assert checks["Backend"].detail == ":3000"


def test_health_report_is_build_then_services(tmp_path: Path):
    report = health_report(_cfg(tmp_path), connect=lambda *a: False)
    names = [c.name for c in report]
    assert names == [
        ".env",
        "frontend/.env",
        "backend jar",
        "frontend build",
        "Postgres",
        "Backend",
        "Frontend",
    ]
    assert all(isinstance(c, Check) for c in report)
