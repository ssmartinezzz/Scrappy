"""Tests for cli.core.config — repo-root discovery & toolchain path
resolution (tasks.md 1.1.1)."""
from __future__ import annotations

from pathlib import Path

import pytest

from cli.core.config import Ports, find_repo_root, load_config, resolve_toolchain_paths
from cli.core.errors import ConfigError


def test_find_repo_root_resolves_from_a_nested_cwd(fake_repo: Path):
    nested = fake_repo / "scraper" / "src" / "main" / "java"
    nested.mkdir(parents=True)

    assert find_repo_root(nested) == fake_repo


def test_find_repo_root_resolves_from_root_itself(fake_repo: Path):
    assert find_repo_root(fake_repo) == fake_repo


def test_find_repo_root_raises_config_error_when_no_markers_found(tmp_path: Path):
    lost = tmp_path / "somewhere" / "else"
    lost.mkdir(parents=True)

    with pytest.raises(ConfigError):
        find_repo_root(lost)


def test_toolchain_paths_resolve_expected_subdirs(fake_repo: Path):
    tools = resolve_toolchain_paths(fake_repo)

    assert tools.jdk21 == fake_repo / "_tools" / "jdk21"
    assert tools.maven == fake_repo / "_tools" / "maven"
    assert tools.node == fake_repo / "_tools" / "node"
    assert tools.cli_venv == fake_repo / "_tools" / "cli-venv"
    assert tools.pgsql == fake_repo / "_tools" / "pgsql"


def test_load_config_wires_repo_root_tools_and_ports(fake_repo: Path):
    nested = fake_repo / "scraper" / "src"
    nested.mkdir(parents=True)

    cfg = load_config(nested)

    assert cfg.repo_root == fake_repo
    assert cfg.tools == resolve_toolchain_paths(fake_repo)
    assert cfg.ports == Ports()


def test_ports_expose_backend_and_frontend():
    ports = Ports()
    assert ports.backend == 3000
    assert ports.frontend == 5173
