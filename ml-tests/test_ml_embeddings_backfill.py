# -*- coding: utf-8 -*-
"""
Tests for ml_embeddings.py (PR3b2 slice): the backfill() CLI entrypoint.

Builds on PR3b1's classify()/dominant_color() (tested separately in
test_ml_embeddings_classify.py) and PR3a's embed_images() (tested in
test_ml_embeddings.py). Same no-network, no-real-model-weights
discipline: every heavy dependency stays behind this module's own
lazy-import boundary, so these tests run green even when
open_clip/torch/PIL are NOT installed.
"""
import json
import sqlite3

import numpy as np
import pytest

import ml_embeddings


# ─── Fixtures ────────────────────────────────────────────────────────────────


@pytest.fixture(autouse=True)
def _reset_singletons():
    """Model + prompt-embedding singleton state is module-global; reset it
    around every test so one test's mocks don't leak into the next."""
    ml_embeddings._model = None
    ml_embeddings._preprocess = None
    ml_embeddings._model_load_attempted = False
    ml_embeddings._prompt_embeddings = None
    ml_embeddings._prompt_embeddings_attempted = False
    yield
    ml_embeddings._model = None
    ml_embeddings._preprocess = None
    ml_embeddings._model_load_attempted = False
    ml_embeddings._prompt_embeddings = None
    ml_embeddings._prompt_embeddings_attempted = False


@pytest.fixture
def db_path(tmp_path):
    """A temp SQLite file with BOTH `image_embeddings` (PR1 schema) and a
    minimal `productos` table (url, imagen_url, activo + the 4 additive
    visual-attribute columns from PR1), seeded with two active products."""
    path = tmp_path / "scraper.db"
    conn = sqlite3.connect(str(path))
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS image_embeddings (
            url           TEXT PRIMARY KEY,
            embedding     BLOB NOT NULL,
            dim           INTEGER NOT NULL,
            model_version TEXT NOT NULL,
            computed_at   TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS productos (
            url             TEXT PRIMARY KEY,
            imagen_url      TEXT,
            activo          INTEGER DEFAULT 1,
            fit             TEXT DEFAULT '',
            estampado       TEXT DEFAULT '',
            escote          TEXT DEFAULT '',
            color_dominante TEXT DEFAULT ''
        )
        """
    )
    conn.executemany(
        "INSERT INTO productos (url, imagen_url, activo) VALUES (?, ?, 1)",
        [
            ("http://x/p1", "http://x/p1.jpg"),
            ("http://x/p2", "http://x/p2.jpg"),
        ],
    )
    conn.commit()
    conn.close()
    return str(path)


# ─── backfill(): pending selection (cache-first / force) ────────────────────


def test_backfill_skips_urls_with_a_valid_cached_embedding_unless_forced(monkeypatch, db_path):
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at) VALUES (?, ?, ?, ?, ?)",
        (
            "http://x/p1.jpg",
            np.array([1.0, 0.0], dtype="<f4").tobytes(),
            2,
            ml_embeddings.MODEL_VERSION,
            "2026-01-01T00:00:00+00:00",
        ),
    )
    conn.commit()
    conn.close()

    seen_urls = []

    def _fake_embed_images(urls, db_path=None, model_version=None, preloaded_images=None):
        seen_urls.extend(urls)
        return {u: None for u in urls}

    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(db_path=db_path, force=False, use_gpu=True)

    assert seen_urls == ["http://x/p2.jpg"]  # p1 skipped (already cached), p2 processed


def test_backfill_force_reprocesses_every_url_even_if_cached(monkeypatch, db_path):
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at) VALUES (?, ?, ?, ?, ?)",
        (
            "http://x/p1.jpg",
            np.array([1.0, 0.0], dtype="<f4").tobytes(),
            2,
            ml_embeddings.MODEL_VERSION,
            "2026-01-01T00:00:00+00:00",
        ),
    )
    conn.commit()
    conn.close()

    seen_urls = []

    def _fake_embed_images(urls, db_path=None, model_version=None, preloaded_images=None):
        seen_urls.extend(urls)
        return {u: None for u in urls}

    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(db_path=db_path, force=True, use_gpu=True)

    assert sorted(seen_urls) == ["http://x/p1.jpg", "http://x/p2.jpg"]


# ─── backfill(): degradation + persistence + progress reporting ─────────────


def test_backfill_degrades_to_text_only_when_model_unavailable_no_crash(monkeypatch, db_path, capsys):
    """T3b.8: when the model can't load for the whole run, embed_images
    returns None for every URL, classify() abstains entirely, and color
    extraction is skipped too — backfill() must still run to completion
    without raising and without persisting any bogus attribute value."""
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, db_path=None, model_version=None, preloaded_images=None: {u: None for u in urls},
    )
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("no model, no image"))
    )

    ml_embeddings.backfill(db_path=db_path, force=False, use_gpu=True)

    conn = sqlite3.connect(db_path)
    rows = conn.execute(
        "SELECT fit, estampado, escote, color_dominante FROM productos ORDER BY url"
    ).fetchall()
    conn.close()
    assert rows == [("", "", "", ""), ("", "", "", "")]

    out = capsys.readouterr().out
    progress = [json.loads(line) for line in out.strip().splitlines() if line.strip()]
    assert progress[-1] == {"phase": "embedding", "pct": 100, "msg": "backfill completo"}


def test_backfill_persists_additive_columns_only_never_genero_or_categoria(monkeypatch, db_path):
    """Confirms `_persist_visual_attrs` never touches genero/categoria —
    those require the text-wins gate that lives in the Java pipeline
    (PR4/PR5), out of scope for this CLI."""
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, db_path=None, model_version=None, preloaded_images=None: {
            u: np.array([1.0, 0.0], dtype="float32") for u in urls
        },
    )
    fake_attrs = {
        "categoria": "Remera",
        "fit": "oversize",
        "estampado": "estampado",
        "escote": "cuello redondo",
        "genero": "hombre",
        "genImgConf": 0.9,
        "catMLConf": 0.9,
    }
    monkeypatch.setattr(ml_embeddings, "classify", lambda embedding, db_path="scraper.db": fake_attrs)
    monkeypatch.setattr(ml_embeddings, "dominant_color", lambda _image: "azul")
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: "fake-pil-image"
    )

    ml_embeddings.backfill(db_path=db_path, force=True, use_gpu=True)

    conn = sqlite3.connect(db_path)
    row = conn.execute(
        "SELECT fit, estampado, escote, color_dominante FROM productos WHERE url = ?", ("http://x/p1",)
    ).fetchone()
    columns = [d[0] for d in conn.execute("PRAGMA table_info(productos)").fetchall()]
    conn.close()

    assert row == ("oversize", "estampado", "cuello redondo", "azul")
    assert "genero" not in columns  # productos never gained a genero write path via this CLI
    assert "categoria" not in columns


def test_backfill_no_pending_products_emits_single_completion_progress(tmp_path, capsys):
    path = tmp_path / "empty.db"
    conn = sqlite3.connect(str(path))
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS image_embeddings (
            url TEXT PRIMARY KEY, embedding BLOB NOT NULL, dim INTEGER NOT NULL,
            model_version TEXT NOT NULL, computed_at TEXT NOT NULL)
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS productos (
            url TEXT PRIMARY KEY, imagen_url TEXT, activo INTEGER DEFAULT 1,
            fit TEXT DEFAULT '', estampado TEXT DEFAULT '', escote TEXT DEFAULT '',
            color_dominante TEXT DEFAULT '')
        """
    )
    conn.commit()
    conn.close()

    ml_embeddings.backfill(db_path=str(path), force=False, use_gpu=True)

    out = capsys.readouterr().out
    progress = [json.loads(line) for line in out.strip().splitlines() if line.strip()]
    assert progress == [{"phase": "embedding", "pct": 100, "msg": "sin productos pendientes"}]


def test_backfill_db_connect_failure_degrades_without_raising(monkeypatch, tmp_path, capsys):
    def _boom_connect(*_a, **_kw):
        raise sqlite3.OperationalError("simulated: database is locked")

    monkeypatch.setattr(ml_embeddings.sqlite3, "connect", _boom_connect)

    ml_embeddings.backfill(db_path=str(tmp_path / "unreachable.db"), force=False, use_gpu=True)

    out = capsys.readouterr().out
    progress = [json.loads(line) for line in out.strip().splitlines() if line.strip()]
    assert progress == [
        {"phase": "embedding", "pct": 0, "msg": "error: no se pudo conectar a la base de datos"}
    ]


def test_backfill_use_gpu_false_forces_cpu_only_via_env_var(monkeypatch, db_path):
    monkeypatch.delenv("CUDA_VISIBLE_DEVICES", raising=False)
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, db_path=None, model_version=None, preloaded_images=None: {u: None for u in urls},
    )
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(db_path=db_path, force=True, use_gpu=False)

    assert ml_embeddings.os.environ.get("CUDA_VISIBLE_DEVICES") == "-1"


def test_backfill_use_gpu_true_does_not_touch_cuda_env_var(monkeypatch, db_path):
    monkeypatch.delenv("CUDA_VISIBLE_DEVICES", raising=False)
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, db_path=None, model_version=None, preloaded_images=None: {u: None for u in urls},
    )
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(db_path=db_path, force=True, use_gpu=True)

    assert "CUDA_VISIBLE_DEVICES" not in ml_embeddings.os.environ


# ─── backfill(): batching + single-download-per-image (PR3b2 judgment-day) ──


def test_backfill_batches_embed_images_calls_and_downloads_each_image_once(tmp_path, monkeypatch):
    """Regression: `embed_images` must be called once per chunk of
    `_BACKFILL_CHUNK_SIZE` products, never once per product, and every
    image must be downloaded exactly once — reused for both the embedding
    and `dominant_color()` — instead of downloaded a second time later in
    the loop (deferred from PR3b1 judgment-day review: "backfill loop
    double-downloads each image AND calls embed_images once per URL,
    causing thousands of SQLite connection open/close cycles")."""
    path = tmp_path / "many.db"
    conn = sqlite3.connect(str(path))
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS image_embeddings (
            url TEXT PRIMARY KEY, embedding BLOB NOT NULL, dim INTEGER NOT NULL,
            model_version TEXT NOT NULL, computed_at TEXT NOT NULL)
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS productos (
            url TEXT PRIMARY KEY, imagen_url TEXT, activo INTEGER DEFAULT 1,
            fit TEXT DEFAULT '', estampado TEXT DEFAULT '', escote TEXT DEFAULT '',
            color_dominante TEXT DEFAULT '')
        """
    )
    chunk_size = ml_embeddings._BACKFILL_CHUNK_SIZE
    n = chunk_size * 2 + 5  # spans three chunks: full, full, partial
    conn.executemany(
        "INSERT INTO productos (url, imagen_url, activo) VALUES (?, ?, 1)",
        [(f"http://x/p{i}", f"http://x/p{i}.jpg") for i in range(n)],
    )
    conn.commit()
    conn.close()

    embed_images_call_sizes = []
    download_counts = {}

    def _fake_embed_images(urls, db_path=None, model_version=None, preloaded_images=None):
        embed_images_call_sizes.append(len(urls))
        return {u: np.array([1.0, 0.0], dtype="float32") for u in urls}

    def _fake_download(url):
        download_counts[url] = download_counts.get(url, 0) + 1
        return "fake-pil-image"

    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(ml_embeddings, "_download_image", _fake_download)
    monkeypatch.setattr(
        ml_embeddings,
        "classify",
        lambda embedding, db_path="scraper.db": {
            "categoria": "", "fit": "", "estampado": "", "escote": "", "genero": "unisex",
            "genImgConf": 0.0, "catMLConf": 0.0,
        },
    )
    monkeypatch.setattr(ml_embeddings, "dominant_color", lambda _image: "azul")

    ml_embeddings.backfill(db_path=str(path), force=False, use_gpu=True)

    # embed_images called once per chunk (3 calls), not once per product (n calls)
    assert embed_images_call_sizes == [chunk_size, chunk_size, 5]
    # every product's image downloaded exactly once, never twice
    assert len(download_counts) == n
    assert all(count == 1 for count in download_counts.values())


def test_emit_progress_degrades_silently_on_broken_pipe(monkeypatch):
    """Regression: `_emit_progress`'s `print` must never raise
    `BrokenPipeError`/`OSError` out of `backfill()` when the reading side
    of the pipe (Java's `PythonRunner`) already closed — deferred from
    PR3b2 judgment-day review: unguarded print violated the module's
    never-raises contract."""

    def _boom_print(*_a, **_kw):
        raise BrokenPipeError("reader closed")

    monkeypatch.setattr("builtins.print", _boom_print)

    ml_embeddings._emit_progress(50, "should not raise")  # must not raise
