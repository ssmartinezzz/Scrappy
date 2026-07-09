# -*- coding: utf-8 -*-
"""pytest configuration for the standalone Python ML test suite.

Tests live at the repo root (`ml-tests/`), outside `src/main/resources/ml/`,
so they are never bundled into the packaged JAR (Maven copies everything
under `src/main/resources` verbatim, and PythonRunner extracts scripts from
the JAR by exact filename — a stray test directory there would only add
dead weight to the JAR without ever being extracted or run in production).

This conftest adds the real `scraper/src/main/resources/ml/` directory to
`sys.path` so tests can `import ml_embeddings` directly, the same module
that ships to production.
"""
import sys
from pathlib import Path

_ML_DIR = Path(__file__).resolve().parent.parent / "scraper" / "src" / "main" / "resources" / "ml"
if str(_ML_DIR) not in sys.path:
    sys.path.insert(0, str(_ML_DIR))
