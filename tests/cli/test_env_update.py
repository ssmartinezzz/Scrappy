"""cli.core.env_file.update_env — rewrite chosen keys in a live `.env` without
disturbing anything else."""
from __future__ import annotations

import os
import stat
from pathlib import Path

import pytest

from cli.core import env_file
from cli.core.env_file import SECRET_KEYS, parse_env, update_env

ORIGINAL = """\
# header
DATABASE_URL=jdbc:postgresql://localhost:5432/scraper

SMTP_HOST=
SMTP_PORT=587
# keep me
OTRA=valor
"""


def _env(tmp_path: Path, text: str = ORIGINAL) -> Path:
    path = tmp_path / ".env"
    path.write_text(text, encoding="utf-8")
    return path


def test_sets_a_present_key_in_place_and_keeps_everything_else(tmp_path: Path):
    path = _env(tmp_path)

    update_env(path, {"SMTP_HOST": "smtp.gmail.com"})

    assert path.read_text(encoding="utf-8") == ORIGINAL.replace("SMTP_HOST=\n", "SMTP_HOST=smtp.gmail.com\n")


def test_appends_an_absent_key(tmp_path: Path):
    path = _env(tmp_path)

    update_env(path, {"SMTP_USERNAME": "cuenta@example.com"})

    assert path.read_text(encoding="utf-8") == ORIGINAL + "SMTP_USERNAME=cuenta@example.com\n"


def test_appends_after_a_file_with_no_trailing_newline(tmp_path: Path):
    path = _env(tmp_path, "A=1")

    update_env(path, {"B": "2"})

    assert path.read_text(encoding="utf-8") == "A=1\nB=2\n"


def test_rewrites_every_occurrence_of_a_duplicated_key(tmp_path: Path):
    path = _env(tmp_path, "SMTP_PORT=25\nX=1\nSMTP_PORT=26\n")

    update_env(path, {"SMTP_PORT": "587"})

    assert parse_env(path)["SMTP_PORT"] == "587"
    assert path.read_text(encoding="utf-8").count("587") == 2


def test_keeps_the_file_permissions(tmp_path: Path):
    path = _env(tmp_path)
    os.chmod(path, 0o640)

    update_env(path, {"SMTP_HOST": "h"})

    assert stat.S_IMODE(path.stat().st_mode) == 0o640


@pytest.mark.parametrize("modo", [0o644, 0o640, 0o666, 0o400])
def test_writing_a_secret_clamps_the_file_to_owner_only(tmp_path: Path, modo: int):
    path = _env(tmp_path)
    os.chmod(path, modo)

    update_env(path, {"SMTP_PASSWORD": "clave"})

    assert stat.S_IMODE(path.stat().st_mode) == 0o600


def test_is_atomic_a_failed_write_leaves_the_original_untouched(tmp_path: Path, monkeypatch):
    path = _env(tmp_path)
    monkeypatch.setattr(env_file.os, "replace", lambda *a: (_ for _ in ()).throw(OSError("disco lleno")))

    with pytest.raises(OSError):
        update_env(path, {"SMTP_HOST": "h"})

    assert path.read_text(encoding="utf-8") == ORIGINAL
    assert [p.name for p in tmp_path.iterdir()] == [".env"]


@pytest.mark.parametrize("valor", ["a\nb", "a\rb", "a\x00b"])
def test_refuses_a_value_that_would_inject_a_line(tmp_path: Path, valor: str):
    path = _env(tmp_path)

    with pytest.raises(ValueError):
        update_env(path, {"SMTP_PASSWORD": valor})

    assert path.read_text(encoding="utf-8") == ORIGINAL


def test_smtp_password_is_a_secret_key():
    assert "SMTP_PASSWORD" in SECRET_KEYS
