# -*- coding: utf-8 -*-
"""
normalize-db-schema-fks-1nf, slice A.2 (V5), task 2.4.

Covers spec "db-column-domains" (design D2, scenario "Python raw SQL against
activo succeeds post-migration") — `productos.activo` retypes from INTEGER to
BOOLEAN in V5, so `activo = 1` has no operator against a boolean column
(`operator does not exist: boolean = integer`). Source-level assertion (no
DB/psycopg2 needed, per TEST-3: absent infra must skip, never fail, and a
skipped test proves nothing about the one line that matters) reading the two
shipped scripts directly, the same technique `conftest.py` already uses to
put them on `sys.path`.
"""
import re
from pathlib import Path

_ML_DIR = Path(__file__).resolve().parent.parent / "scraper" / "src" / "main" / "resources" / "ml"

# `activo = 1` / `activo=1` (or `= 0`/`=0`) — the integer-literal idiom that
# breaks once the column is BOOLEAN. `activo` alone (no comparison) or
# `activo = true`/`activo = false` are fine and must not be flagged.
_INTEGER_LITERAL_ACTIVO = re.compile(r"\bactivo\b\s*=\s*[01]\b")


def _read(filename):
    path = _ML_DIR / filename
    assert path.is_file(), f"expected {path} to exist"
    return path.read_text(encoding="utf-8")


def test_ml_train_has_no_integer_literal_activo_comparison():
    content = _read("ml_train.py")
    assert not _INTEGER_LITERAL_ACTIVO.search(content), (
        "ml_train.py still compares activo to an integer literal (0/1) — "
        "productos.activo is BOOLEAN as of V5, use 'activo' or 'activo = true/false'"
    )


def test_ml_embeddings_has_no_integer_literal_activo_comparison():
    content = _read("ml_embeddings.py")
    assert not _INTEGER_LITERAL_ACTIVO.search(content), (
        "ml_embeddings.py still compares activo to an integer literal (0/1) — "
        "productos.activo is BOOLEAN as of V5, use 'activo' or 'activo = true/false'"
    )
