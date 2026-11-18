"""`--init-env` / `--set-admin`: the non-interactive first-run setup."""
from __future__ import annotations

import io
import stat

import pytest

from cli.__main__ import main
from cli.core.bootstrap import is_placeholder, set_admin
from cli.core.config import Config, Ports, ToolchainPaths
from cli.core.env_file import parse_env
from cli.core.errors import EnvGenError

EXAMPLE = (
    "# auth\n"
    "AUTH_JWT_SECRET=generado-por-el-instalador\n"
    "ADMIN_BOOTSTRAP_USERNAME=admin\n"
    "ADMIN_BOOTSTRAP_PASSWORD=cambiame-por-una-password-real\n"
    "CLI_SERVICE_ACCOUNT_USERNAME=cli-service\n"
    "CLI_SERVICE_ACCOUNT_PASSWORD=replace-me-with-random-too\n"
)


@pytest.fixture
def cfg(tmp_path) -> Config:
    (tmp_path / ".env.example").write_text(EXAMPLE, encoding="utf-8")
    tools = tmp_path / "_tools"
    return Config(
        repo_root=tmp_path,
        tools=ToolchainPaths(jdk21=tools / "jdk21", maven=tools / "maven", node=tools / "node",
                             cli_venv=tools / "cli-venv", pgsql=tools / "pgsql"),
        ports=Ports(),
    )


@pytest.mark.parametrize("value", [
    "cambiame-por-una-password-real", "CAMBIAME", "replace-me-with-random-too", "x-replace-me-x",
])
def test_example_values_are_placeholders(value):
    assert is_placeholder(value)


@pytest.mark.parametrize("value", ["", "   ", "una-password-de-verdad"])
def test_real_or_blank_values_are_not_placeholders(value):
    assert not is_placeholder(value)


def test_set_admin_creates_env_and_generates_a_real_password(cfg):
    assert set_admin(cfg, "jefa", None) is True

    env = parse_env(cfg.repo_root / ".env")
    assert env["ADMIN_BOOTSTRAP_USERNAME"] == "jefa"
    assert len(env["ADMIN_BOOTSTRAP_PASSWORD"]) >= 12
    assert not is_placeholder(env["ADMIN_BOOTSTRAP_PASSWORD"])
    # The installer still mints the secrets nobody types.
    assert not is_placeholder(env["CLI_SERVICE_ACCOUNT_PASSWORD"])


def test_set_admin_keeps_a_chosen_password_and_owner_only_mode(cfg):
    assert set_admin(cfg, "admin", "una-password-larga") is False

    env_path = cfg.repo_root / ".env"
    assert parse_env(env_path)["ADMIN_BOOTSTRAP_PASSWORD"] == "una-password-larga"
    assert stat.S_IMODE(env_path.stat().st_mode) & 0o077 == 0


def test_set_admin_does_not_rotate_the_service_secret(cfg):
    set_admin(cfg, "admin", None)
    before = parse_env(cfg.repo_root / ".env")["CLI_SERVICE_ACCOUNT_PASSWORD"]

    set_admin(cfg, "admin", None)

    assert parse_env(cfg.repo_root / ".env")["CLI_SERVICE_ACCOUNT_PASSWORD"] == before


@pytest.mark.parametrize("username,password", [
    ("", None), ("dos palabras", None),
    ("admin", "cambiame-por-una-password-real"), ("admin", "corta"),
])
def test_set_admin_refuses_bad_input_and_writes_nothing(cfg, username, password):
    with pytest.raises(EnvGenError):
        set_admin(cfg, username, password)
    assert not (cfg.repo_root / ".env").exists()


def test_main_set_admin_reads_stdin_and_never_prints_the_password(cfg, monkeypatch, capsys):
    monkeypatch.setattr("sys.stdin", io.StringIO("una-password-larga\n"))

    assert main(["--set-admin", "admin"], cfg=cfg) == 0

    out = capsys.readouterr().out
    assert "una-password-larga" not in out
    assert parse_env(cfg.repo_root / ".env")["ADMIN_BOOTSTRAP_PASSWORD"] == "una-password-larga"


def test_main_set_admin_reports_a_refusal_with_exit_2(cfg, monkeypatch, capsys):
    monkeypatch.setattr("sys.stdin", io.StringIO("corta\n"))

    assert main(["--set-admin", "admin"], cfg=cfg) == 2
    assert "12" in capsys.readouterr().err


def test_main_init_env_creates_env_and_exits(cfg, capsys):
    assert main(["--init-env"], cfg=cfg) == 0
    assert (cfg.repo_root / ".env").is_file()
