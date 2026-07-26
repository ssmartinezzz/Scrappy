"""Tests for cli.core.env_file — template-driven .env generation (LD-2).

Covers tasks.md 1.3.1-1.3.5: create-if-absent, existing-values-untouched,
additive reconcile, --regenerate/--force, and secrets-never-echoed.
"""
from __future__ import annotations

import logging
from pathlib import Path

from cli.core.env_file import SECRET_KEYS, generate_env, parse_env

EXAMPLE = """\
# comment header — preserved verbatim on fresh generation
DATABASE_URL=jdbc:postgresql://localhost:5432/scraper
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres
SCRAPER_MODELS_ROOT=./scraper/_models
APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
VITE_API_BASE_URL=http://localhost:3000
APP_OPEN_URL=

# unrelated pass-through key — not in the CLI's computed set
SOME_OTHER_KEY=default-value
"""

SCHEMA_KEYS = [
    "DATABASE_URL",
    "DATABASE_USERNAME",
    "DATABASE_PASSWORD",
    "SCRAPER_MODELS_ROOT",
    "APP_CORS_ALLOWED_ORIGINS",
    "VITE_API_BASE_URL",
    "APP_OPEN_URL",
    "SOME_OTHER_KEY",
]

COMPUTED = {
    "DATABASE_URL": "jdbc:postgresql://localhost:5432/scraper",
    "DATABASE_USERNAME": "postgres",
    "DATABASE_PASSWORD": "",
    "SCRAPER_MODELS_ROOT": "/repo/scraper/_models",
    "APP_CORS_ALLOWED_ORIGINS": "http://localhost:5173",
    "VITE_API_BASE_URL": "http://localhost:3000",
    "APP_OPEN_URL": "http://localhost:5173",
}


def _write_example(tmp_path: Path) -> Path:
    example = tmp_path / ".env.example"
    example.write_text(EXAMPLE, encoding="utf-8")
    return example


def test_create_if_absent_writes_every_schema_key_with_computed_or_default(tmp_path: Path):
    """1.3.1 — First run generates .env."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    assert not env_path.exists()

    generate_env(example, env_path, COMPUTED, force=False)

    values = parse_env(env_path)
    for key in SCHEMA_KEYS:
        assert key in values

    assert values["DATABASE_URL"] == COMPUTED["DATABASE_URL"]
    assert values["DATABASE_PASSWORD"] == ""
    assert values["VITE_API_BASE_URL"] == COMPUTED["VITE_API_BASE_URL"]
    assert values["APP_OPEN_URL"] == COMPUTED["APP_OPEN_URL"]
    # not in COMPUTED -> passes through the .env.example default verbatim
    assert values["SOME_OTHER_KEY"] == "default-value"


def test_existing_values_untouched_on_rerun(tmp_path: Path):
    """1.3.2 — a hand-edited DATABASE_PASSWORD survives a rerun unchanged."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    generate_env(example, env_path, COMPUTED, force=False)

    hand_edited = "totally-custom-password"
    lines = env_path.read_text(encoding="utf-8").splitlines()
    lines = [
        f"DATABASE_PASSWORD={hand_edited}" if ln.startswith("DATABASE_PASSWORD=") else ln
        for ln in lines
    ]
    env_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    generate_env(example, env_path, COMPUTED, force=False)

    assert parse_env(env_path)["DATABASE_PASSWORD"] == hand_edited


def test_additive_reconcile_appends_missing_key_without_touching_existing_bytes(tmp_path: Path):
    """1.3.3 — a new .env.example key is appended; pre-existing bytes stay identical."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    generate_env(example, env_path, COMPUTED, force=False)
    before_text = env_path.read_text(encoding="utf-8")

    # simulate .env.example gaining a new key (e.g. a future LLM_* var)
    example.write_text(EXAMPLE + "\nLLM_BASE_URL=http://localhost:11434\n", encoding="utf-8")

    generate_env(example, env_path, COMPUTED, force=False)

    after_text = env_path.read_text(encoding="utf-8")
    assert after_text.startswith(before_text)
    assert parse_env(env_path)["LLM_BASE_URL"] == "http://localhost:11434"
    # every pre-existing key/value is unchanged too
    before_values = {
        line.split("=", 1)[0]: line.split("=", 1)[1]
        for line in before_text.splitlines()
        if line and not line.startswith("#") and "=" in line
    }
    after_values = parse_env(env_path)
    for key, value in before_values.items():
        assert after_values[key] == value


def test_regenerate_force_overwrites_every_key_from_computed(tmp_path: Path):
    """1.3.4 — --regenerate/--force overwrites from computed defaults."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    generate_env(example, env_path, COMPUTED, force=False)

    drifted = env_path.read_text(encoding="utf-8").replace(
        "DATABASE_USERNAME=postgres", "DATABASE_USERNAME=custom_user"
    )
    env_path.write_text(drifted, encoding="utf-8")

    generate_env(example, env_path, COMPUTED, force=True)

    assert parse_env(env_path)["DATABASE_USERNAME"] == COMPUTED["DATABASE_USERNAME"]


def test_default_run_never_triggers_full_overwrite_regardless_of_drift(tmp_path: Path):
    """1.3.4 — default run (no flag) never overwrites, even with drift."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    generate_env(example, env_path, COMPUTED, force=False)

    drifted = env_path.read_text(encoding="utf-8").replace(
        "DATABASE_USERNAME=postgres", "DATABASE_USERNAME=drifted_user"
    )
    env_path.write_text(drifted, encoding="utf-8")

    generate_env(example, env_path, COMPUTED, force=False)

    assert parse_env(env_path)["DATABASE_USERNAME"] == "drifted_user"


def test_secrets_never_echoed_in_logs(tmp_path: Path, caplog):
    """1.3.5 — no log line ever contains the DATABASE_PASSWORD value, in
    either the create-if-absent or additive-reconcile path."""
    example = _write_example(tmp_path)
    env_path = tmp_path / ".env"
    secret_value = "s3cr3t-p@ss"
    computed = {**COMPUTED, "DATABASE_PASSWORD": secret_value}

    with caplog.at_level(logging.INFO):
        generate_env(example, env_path, computed, force=False)
        example.write_text(EXAMPLE + "\nLLM_BASE_URL=http://localhost:11434\n", encoding="utf-8")
        generate_env(example, env_path, computed, force=False)

    assert caplog.records, "expected the generator to log its progress"
    for record in caplog.records:
        assert secret_value not in record.getMessage()
    assert "DATABASE_PASSWORD" in SECRET_KEYS
