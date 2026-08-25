"""Tests for the authentication keys in `.env` generation (slice 2 of
user-accounts-and-roles).

The reconcile mechanism itself is generic and already covered by
`test_env_file.py`. What is decided here is *what value each new key gets*,
and the three keys get three different answers for three different reasons:

- `AUTH_JWT_SECRET` and `CLI_SERVICE_ACCOUNT_PASSWORD` are **generated** per
  installation. Nobody ever types either of them — the backend signs with one
  and the CLI reads the other out of `.env` — so a placeholder would simply
  survive forever, and a placeholder signing secret in a public repository is
  the same as no signature at all.
- `ADMIN_BOOTSTRAP_PASSWORD` stays a **placeholder**, because a human has to
  choose it and then use it to log in. Generating one would leave the operator
  with an account whose password lives only in a file they were never told to
  read. The backend refuses to seed while that placeholder is still in place.

The stickiness test is the one that matters most in practice: regenerating
`.env` must NOT rotate an already-issued secret. The seeder never overwrites an
existing password hash, so a rotated `CLI_SERVICE_ACCOUNT_PASSWORD` would leave
the file and the database disagreeing, and every cronjob would start failing to
authenticate with a correct-looking configuration.
"""
from __future__ import annotations

import logging
from pathlib import Path

from cli.core.env_file import (
    GENERATED_KEYS,
    SECRET_KEYS,
    generate_env,
    generate_secret,
    parse_env,
)

EXAMPLE = """\
DATABASE_URL=jdbc:postgresql://localhost:5432/scraper
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres

# ── Autenticación ──
AUTH_JWT_SECRET=generado-por-el-instalador
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=cambiame-por-una-password-real
CLI_SERVICE_ACCOUNT_USERNAME=cli-service
CLI_SERVICE_ACCOUNT_PASSWORD=generado-por-el-instalador
"""

COMPUTED = {
    "DATABASE_URL": "jdbc:postgresql://localhost:5432/scraper",
    "DATABASE_USERNAME": "postgres",
    "DATABASE_PASSWORD": "",
}

AUTH_KEYS = [
    "AUTH_JWT_SECRET",
    "ADMIN_BOOTSTRAP_USERNAME",
    "ADMIN_BOOTSTRAP_PASSWORD",
    "CLI_SERVICE_ACCOUNT_USERNAME",
    "CLI_SERVICE_ACCOUNT_PASSWORD",
]


def _write_example(tmp_path: Path) -> Path:
    example = tmp_path / ".env.example"
    example.write_text(EXAMPLE, encoding="utf-8")
    return example


def test_pre_existing_env_gains_the_auth_keys_without_disturbing_anything(tmp_path: Path):
    """A `.env` written before this change gains all five keys, untouched otherwise."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    env_path.write_text(
        "DATABASE_URL=jdbc:postgresql://localhost:6543/scraper\n"
        "DATABASE_USERNAME=otro\n"
        "DATABASE_PASSWORD=una-password-que-el-usuario-eligio\n",
        encoding="utf-8",
    )

    generate_env(example, env_path, COMPUTED)

    values = parse_env(env_path)
    for key in AUTH_KEYS:
        assert key in values, f"{key} should have been appended"
    assert values["DATABASE_PASSWORD"] == "una-password-que-el-usuario-eligio"
    assert values["DATABASE_URL"] == "jdbc:postgresql://localhost:6543/scraper"
    assert values["DATABASE_USERNAME"] == "otro"


def test_generated_secrets_are_not_the_example_placeholder(tmp_path: Path):
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"

    generate_env(example, env_path, COMPUTED)

    values = parse_env(env_path)
    for key in GENERATED_KEYS:
        assert values[key] != "generado-por-el-instalador"
        assert len(values[key]) >= 32, f"{key} is too short to be a real secret"


def test_the_admin_password_stays_a_placeholder_for_a_human_to_replace(tmp_path: Path):
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"

    generate_env(example, env_path, COMPUTED)

    assert parse_env(env_path)["ADMIN_BOOTSTRAP_PASSWORD"] == "cambiame-por-una-password-real", (
        "generating it would leave the operator unable to log in, and the backend "
        "refuses to seed while the placeholder is still there"
    )


def test_two_installations_do_not_share_a_secret(tmp_path: Path):
    example = _write_example(tmp_path)
    uno = tmp_path / "uno.env"
    otro = tmp_path / "otro.env"

    generate_env(example, uno, COMPUTED)
    generate_env(example, otro, COMPUTED)

    assert parse_env(uno)["AUTH_JWT_SECRET"] != parse_env(otro)["AUTH_JWT_SECRET"]


def test_regenerating_does_not_rotate_an_already_issued_secret(tmp_path: Path):
    """The trap: a rotated CLI password no longer matches the seeded hash."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    generate_env(example, env_path, COMPUTED)
    antes = parse_env(env_path)

    generate_env(example, env_path, COMPUTED, force=True)

    despues = parse_env(env_path)
    assert despues["AUTH_JWT_SECRET"] == antes["AUTH_JWT_SECRET"], (
        "rotating the signing secret logs every session out with no explanation"
    )
    assert despues["CLI_SERVICE_ACCOUNT_PASSWORD"] == antes["CLI_SERVICE_ACCOUNT_PASSWORD"], (
        "the seeder never overwrites an existing hash, so rotating this in the file "
        "silently breaks every cronjob's login"
    )


def test_regenerating_still_rewrites_ordinary_keys(tmp_path: Path):
    """Stickiness is scoped to the generated secrets — force still forces the rest."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    env_path.write_text("DATABASE_USERNAME=el-viejo\n", encoding="utf-8")

    generate_env(example, env_path, COMPUTED, force=True)

    assert parse_env(env_path)["DATABASE_USERNAME"] == "postgres"


def test_the_new_secrets_are_never_logged(tmp_path: Path, caplog):
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"

    with caplog.at_level(logging.DEBUG):
        generate_env(example, env_path, COMPUTED)

    values = parse_env(env_path)
    registro = caplog.text
    for key in ("AUTH_JWT_SECRET", "CLI_SERVICE_ACCOUNT_PASSWORD"):
        assert values[key] not in registro, f"{key}'s value leaked into the log"


def test_the_new_secrets_are_declared_as_secrets(tmp_path: Path):
    assert "AUTH_JWT_SECRET" in SECRET_KEYS
    assert "ADMIN_BOOTSTRAP_PASSWORD" in SECRET_KEYS
    assert "CLI_SERVICE_ACCOUNT_PASSWORD" in SECRET_KEYS


def test_generate_secret_is_long_and_unpredictable():
    uno = generate_secret()
    otro = generate_secret()

    assert uno != otro
    assert len(uno.encode("utf-8")) >= 32, "HS256 needs at least 32 bytes of key"
