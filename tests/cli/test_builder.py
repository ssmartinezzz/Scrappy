"""Tests for cli.core.builder — build orchestration & the VITE_API_BASE_URL
build-time export ordering hazard (tasks.md 1.4.1-1.4.2)."""
from __future__ import annotations

from pathlib import Path

import pytest

from cli.core import builder
from cli.core.config import Config, Ports, ToolchainPaths


def _cfg(repo_root: Path) -> Config:
    tools = ToolchainPaths(
        jdk21=repo_root / "_tools" / "jdk21",
        maven=repo_root / "_tools" / "maven",
        node=repo_root / "_tools" / "node",
        cli_venv=repo_root / "_tools" / "cli-venv",
        pgsql=repo_root / "_tools" / "pgsql",
    )
    return Config(repo_root=repo_root, tools=tools, ports=Ports())


def _fake_jar(repo_root: Path) -> None:
    target = repo_root / "scraper" / "target"
    target.mkdir(parents=True, exist_ok=True)
    (target / builder.JAR_NAME).write_text("fake-jar", encoding="utf-8")


def _bare_project(tmp_path: Path) -> None:
    """Mirrors the REAL repo's root/frontend `.env.example` split (verify.md
    CRITICAL-1): the root template does NOT declare `VITE_API_BASE_URL` as
    an active key (it lives only in `frontend/.env.example`, per D6) — only
    a commented-out reference plus an unrelated active key. Using a
    synthetic single-file schema here would silently mask the real bug this
    correction closes."""
    (tmp_path / "scraper").mkdir()
    frontend_dir = tmp_path / "frontend"
    frontend_dir.mkdir()
    (tmp_path / ".env.example").write_text(
        "OTHER_KEY=x\n# VITE_API_BASE_URL=http://localhost:3000\n", encoding="utf-8"
    )
    (frontend_dir / ".env.example").write_text(
        "VITE_API_BASE_URL=http://localhost:3000\n", encoding="utf-8"
    )


def test_vite_api_base_url_present_in_env_before_npm_run_build(tmp_path: Path):
    """1.4.1 — VITE_API_BASE_URL is present in the env dict passed to the
    `npm run build` subprocess, proving the export happens before build."""
    _bare_project(tmp_path)
    cfg = _cfg(tmp_path)
    _fake_jar(tmp_path)

    calls: list = []

    def fake_runner(cmd, *, cwd, env):
        calls.append((list(cmd), env))

    builder.build_project(cfg, runner=fake_runner)

    npm_build_call = next(cmd_env for cmd_env in calls if cmd_env[0][-2:] == ["run", "build"])
    assert npm_build_call[1]["VITE_API_BASE_URL"] == "http://localhost:3000"


def test_build_sequence_runs_in_exact_order(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """1.4.2 — generate_env(root) -> generate_env(frontend) -> parse .env
    (root) -> parse .env (frontend) -> npm install -> npm run build ->
    mvn clean package -> jar copy, in that exact order. Two templates are
    generated/parsed (root + frontend, see CRITICAL-1 correction) before any
    subprocess runs."""
    _bare_project(tmp_path)
    cfg = _cfg(tmp_path)
    _fake_jar(tmp_path)

    order: list = []
    monkeypatch.setattr(builder, "generate_env", lambda *a, **k: order.append("generate_env"))

    def fake_parse_env(*_a, **_k):
        order.append("parse_env")
        return {"VITE_API_BASE_URL": "http://localhost:3000"}

    monkeypatch.setattr(builder, "parse_env", fake_parse_env)

    def fake_runner(cmd, *, cwd, env):
        order.append(tuple(cmd))

    builder.build_project(cfg, runner=fake_runner)
    order.append("jar_copy" if (tmp_path / "scraper" / "scraper.jar").is_file() else "jar_copy_missing")

    assert order[0] == "generate_env"
    assert order[1] == "generate_env"
    assert order[2] == "parse_env"
    assert order[3] == "parse_env"
    assert order[4][-1] == "install"
    assert order[5][-2:] == ("run", "build")
    assert order[6][-3:] == ("clean", "package", "-DskipTests")
    assert order[7] == "jar_copy"


def test_build_raises_typed_error_when_jar_artifact_missing(tmp_path: Path):
    _bare_project(tmp_path)
    cfg = _cfg(tmp_path)
    # no fake jar created — mvn "succeeded" per the fake runner, but never
    # actually produced the artifact

    def fake_runner(cmd, *, cwd, env):
        return None

    from cli.core.errors import BuildError

    with pytest.raises(BuildError):
        builder.build_project(cfg, runner=fake_runner)


# -- vendored toolchain resolution with PATH fallback -------------------
# (regression for "build fails: No such file or directory: .../_tools/node/
#  bin/npm" on POSIX, where the installer assumes a system Node/Maven)


def test_npm_cmd_prefers_vendored_when_present(tmp_path: Path):
    cfg = _cfg(tmp_path)
    npm = tmp_path / "_tools" / "node" / "bin" / "npm"  # POSIX layout (CI is Linux)
    npm.parent.mkdir(parents=True)
    npm.write_text("#!/bin/sh\n", encoding="utf-8")

    assert builder._npm_cmd(cfg) == str(npm)


def test_npm_cmd_falls_back_to_path_when_vendored_absent(tmp_path: Path):
    cfg = _cfg(tmp_path)  # no _tools/node provisioned (the POSIX system-tools case)

    assert builder._npm_cmd(cfg) == "npm"


def test_mvn_cmd_prefers_vendored_when_present(tmp_path: Path):
    cfg = _cfg(tmp_path)
    mvn = tmp_path / "_tools" / "maven" / "bin" / "mvn"
    mvn.parent.mkdir(parents=True)
    mvn.write_text("#!/bin/sh\n", encoding="utf-8")

    assert builder._mvn_cmd(cfg) == str(mvn)


def test_mvn_cmd_falls_back_to_path_when_vendored_absent(tmp_path: Path):
    cfg = _cfg(tmp_path)

    assert builder._mvn_cmd(cfg) == "mvn"


def test_mvn_package_skips_tests(tmp_path: Path):
    """The install flow must not run the backend suite: it needs Docker /
    a local Postgres and a Mockito-compatible JDK, none of which an end
    user installing the app is required to have. CI runs the tests."""
    _bare_project(tmp_path)
    cfg = _cfg(tmp_path)
    _fake_jar(tmp_path)
    calls: list = []

    def fake_runner(cmd, *, cwd, env):
        calls.append(list(cmd))

    builder.build_project(cfg, runner=fake_runner)

    mvn_call = next(c for c in calls if "package" in c)
    assert "-DskipTests" in mvn_call


def test_is_built_true_when_jar_and_dist_present(tmp_path: Path):
    (tmp_path / "scraper").mkdir()
    (tmp_path / "scraper" / "scraper.jar").write_text("jar", encoding="utf-8")
    (tmp_path / "frontend").mkdir()
    (tmp_path / "frontend" / "dist").mkdir()

    assert builder.is_built(_cfg(tmp_path)) is True


def test_is_built_false_when_jar_missing(tmp_path: Path):
    (tmp_path / "frontend").mkdir()
    (tmp_path / "frontend" / "dist").mkdir()  # dist present, jar absent

    assert builder.is_built(_cfg(tmp_path)) is False
