# -*- coding: utf-8 -*-
"""
Tests for ml_embeddings.py's `backfill()` CLI entrypoint.

Migrated (task 4.9, `decouple-services-postgres`) from the pre-Postgres
`ml-tests/py-batch4-pending/test_ml_embeddings_backfill.py`, which built a
real SQLite fixture (`image_embeddings` + a hand-built `productos` table)
and issued direct `conn.execute(...)` sqlite3 SQL. Since design D4,
`backfill()` no longer takes a `db_path` argument — it connects via
`_get_connection()` (psycopg2 over `DATABASE_URL`). Every test here
monkeypatches `_get_connection` to return the shared in-memory
`FakeConnection` (`ml-tests/_pg_fakes.py`), which also owns an in-memory
`productos` table (url -> {imagen_url, activo, fit, estampado, escote,
color_dominante}) mirroring the real Flyway-managed columns.

Same no-network, no-real-model-weights discipline as the original: every
heavy dependency stays behind this module's own lazy-import boundary, so
these tests run green even when open_clip/torch/PIL are NOT installed.
"""
import json

import numpy as np
import pytest

import ml_embeddings
from _pg_fakes import FakeConnection, seed_embedding


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
def fake_conn():
    """A `FakeConnection` pre-seeded with two active products, mirroring
    the old SQLite fixture's `productos` seed."""
    return FakeConnection(
        productos={
            "http://x/p1": {"imagen_url": "http://x/p1.jpg", "activo": 1,
                             "fit": "", "estampado": "", "escote": "", "color_dominante": ""},
            "http://x/p2": {"imagen_url": "http://x/p2.jpg", "activo": 1,
                             "fit": "", "estampado": "", "escote": "", "color_dominante": ""},
        }
    )


def _use_fake_conn(monkeypatch, conn):
    monkeypatch.setattr(ml_embeddings, "_get_connection", lambda: conn)


def _row(conn, url):
    r = conn._productos[url]
    return (r.get("fit", ""), r.get("estampado", ""), r.get("escote", ""), r.get("color_dominante", ""))


_FAKE_ATTRS = {
    "categoria": "Remera",
    "fit": "oversize",
    "estampado": "estampado",
    "escote": "cuello redondo",
    "genero": "hombre",
    "genImgConf": 0.9,
    "catMLConf": 0.9,
}

_DEGRADED_ATTRS = {
    "categoria": "", "fit": "", "estampado": "", "escote": "", "genero": "unisex",
    "genImgConf": 0.0, "catMLConf": 0.0,
}


# ─── backfill(): pending selection (cache-first / force) ────────────────────


def test_backfill_skips_urls_with_a_valid_cached_embedding_unless_forced(monkeypatch, fake_conn):
    seed_embedding(
        fake_conn, "http://x/p1.jpg",
        np.array([1.0, 0.0], dtype="<f4").tobytes(), 2, ml_embeddings.MODEL_VERSION,
    )
    _use_fake_conn(monkeypatch, fake_conn)

    seen_urls = []

    def _fake_embed_images(urls, model_version=None, preloaded_images=None):
        seen_urls.extend(urls)
        return {u: None for u in urls}

    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(force=False, use_gpu=True)

    assert seen_urls == ["http://x/p2.jpg"]  # p1 skipped (already cached), p2 processed


def test_backfill_force_reprocesses_every_url_even_if_cached(monkeypatch, fake_conn):
    seed_embedding(
        fake_conn, "http://x/p1.jpg",
        np.array([1.0, 0.0], dtype="<f4").tobytes(), 2, ml_embeddings.MODEL_VERSION,
    )
    _use_fake_conn(monkeypatch, fake_conn)

    seen_urls = []

    def _fake_embed_images(urls, model_version=None, preloaded_images=None):
        seen_urls.extend(urls)
        return {u: None for u in urls}

    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(force=True, use_gpu=True)

    assert sorted(seen_urls) == ["http://x/p1.jpg", "http://x/p2.jpg"]


# ─── backfill(): degradation + persistence + progress reporting ─────────────


def test_backfill_degrades_to_text_only_when_model_unavailable_no_crash(monkeypatch, fake_conn, capsys):
    """When the model can't load for the whole run, embed_images returns
    None for every URL, classify() abstains entirely, and color extraction
    is skipped too — backfill() must still run to completion without
    raising and without persisting any bogus attribute value."""
    _use_fake_conn(monkeypatch, fake_conn)
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, model_version=None, preloaded_images=None: {u: None for u in urls},
    )
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("no model, no image"))
    )

    ml_embeddings.backfill(force=False, use_gpu=True)

    assert _row(fake_conn, "http://x/p1") == ("", "", "", "")
    assert _row(fake_conn, "http://x/p2") == ("", "", "", "")

    out = capsys.readouterr().out
    progress = [json.loads(line) for line in out.strip().splitlines() if line.strip()]
    assert progress[-1] == {"phase": "embedding", "pct": 100, "msg": "backfill completo"}


def test_backfill_persists_additive_columns_only_never_genero_or_categoria(monkeypatch, fake_conn):
    """Confirms `_persist_visual_attrs` never touches genero/categoria —
    those require the text-wins gate that lives in the Java pipeline,
    out of scope for this CLI."""
    _use_fake_conn(monkeypatch, fake_conn)
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, model_version=None, preloaded_images=None: {
            u: np.array([1.0, 0.0], dtype="float32") for u in urls
        },
    )
    monkeypatch.setattr(ml_embeddings, "classify", lambda embedding: _FAKE_ATTRS)
    monkeypatch.setattr(ml_embeddings, "dominant_color", lambda _image: "azul")
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda _url: "fake-pil-image")

    ml_embeddings.backfill(force=True, use_gpu=True)

    assert _row(fake_conn, "http://x/p1") == ("oversize", "estampado", "cuello redondo", "azul")
    # productos never gained a genero/categoria write path via this CLI —
    # the fake's `productos` rows only ever hold the four additive columns.
    assert set(fake_conn._productos["http://x/p1"].keys()) == {
        "imagen_url", "activo", "fit", "estampado", "escote", "color_dominante"
    }


def test_backfill_force_with_model_down_does_not_wipe_existing_attrs(monkeypatch, fake_conn):
    """CONFIRMED regression: a forced rebuild (`force=True`) with the model
    unavailable must NOT wipe existing fit/estampado/escote/color_dominante
    values back to "". Even a product whose embedding is already cached
    (so `embed_images` can still return it without needing the model)
    still needs the model's text tower for prompt embeddings inside
    `classify()`, which degrades to its all-empty sentinel when that's
    unavailable. Uses the REAL (unmocked) `classify()` — torch/open_clip
    are absent from this test environment, so `_get_prompt_embeddings`
    genuinely fails, exactly like a real "model unavailable" run."""
    fake_conn._productos["http://x/p1"].update(
        fit="oversize", estampado="estampado", escote="cuello redondo", color_dominante="azul"
    )
    _use_fake_conn(monkeypatch, fake_conn)

    # `embed_images` still returns a real embedding (as if it were already
    # cached from a prior successful run) — but `classify()` is left
    # REAL/unmocked, so it genuinely degrades via `_get_prompt_embeddings`
    # (no torch/open_clip in this test env).
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, model_version=None, preloaded_images=None: {
            u: np.array([1.0, 0.0], dtype="float32") for u in urls
        },
    )
    monkeypatch.setattr(ml_embeddings, "_download_image", lambda _url: "fake-pil-image")
    monkeypatch.setattr(ml_embeddings, "dominant_color", lambda _image: "verde")

    ml_embeddings.backfill(force=True, use_gpu=True)

    # Pre-existing attrs must remain exactly as seeded — NOT wiped to "".
    assert _row(fake_conn, "http://x/p1") == ("oversize", "estampado", "cuello redondo", "azul")


def test_backfill_force_preserves_color_when_image_download_fails_but_embedding_is_cached(monkeypatch, fake_conn):
    """CONFIRMED regression: in force mode, a product whose embedding is
    already cached (so `embed_images` can still return it via its
    cache-first short-circuit) but whose image download fails THIS run
    must still get fit/estampado/escote updated from a successful
    classify() — but color_dominante, which depends entirely on THIS run's
    (failed) download, must be PRESERVED rather than wiped to ""."""
    fake_conn._productos["http://x/p1"].update(
        fit="regular", estampado="liso", escote="con cuello", color_dominante="rojo"
    )
    _use_fake_conn(monkeypatch, fake_conn)

    # Embedding is still retrievable (cache-first short-circuit)...
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, model_version=None, preloaded_images=None: {
            u: np.array([1.0, 0.0], dtype="float32") for u in urls
        },
    )
    # ...but THIS run's own image download fails for every product.
    monkeypatch.setattr(
        ml_embeddings,
        "_download_image",
        lambda _url: (_ for _ in ()).throw(IOError("simulated: transient network failure")),
    )
    monkeypatch.setattr(ml_embeddings, "classify", lambda embedding: _FAKE_ATTRS)

    ml_embeddings.backfill(force=True, use_gpu=True)

    row = _row(fake_conn, "http://x/p1")
    # fit/estampado/escote updated from the new (successful) classification...
    assert row[:3] == ("oversize", "estampado", "cuello redondo")
    # ...but color_dominante preserved — the download failed this run.
    assert row[3] == "rojo"


def test_backfill_dedups_shared_imagen_url_across_products(monkeypatch):
    """Regression: two products sharing the same imagen_url (e.g. color
    variants of one listing) must trigger exactly ONE download and ONE
    URL entry passed to embed_images per chunk — not one per product —
    while still persisting BOTH rows independently."""
    shared_url = "http://x/shared.jpg"
    conn = FakeConnection(
        productos={
            "http://x/p1": {"imagen_url": shared_url, "activo": 1,
                             "fit": "", "estampado": "", "escote": "", "color_dominante": ""},
            "http://x/p2": {"imagen_url": shared_url, "activo": 1,
                             "fit": "", "estampado": "", "escote": "", "color_dominante": ""},
        }
    )
    _use_fake_conn(monkeypatch, conn)

    download_calls = []
    embed_images_calls = []

    def _tracking_download(url):
        download_calls.append(url)
        return "fake-pil-image"

    def _fake_embed_images(urls, model_version=None, preloaded_images=None):
        embed_images_calls.append(list(urls))
        return {u: np.array([1.0, 0.0], dtype="float32") for u in urls}

    monkeypatch.setattr(ml_embeddings, "_download_image", _tracking_download)
    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(ml_embeddings, "classify", lambda embedding: _FAKE_ATTRS)
    monkeypatch.setattr(ml_embeddings, "dominant_color", lambda _image: "azul")

    ml_embeddings.backfill(force=False, use_gpu=True)

    # Exactly one download and one URL entry passed to embed_images.
    assert download_calls == [shared_url]
    assert embed_images_calls == [[shared_url]]

    assert _row(conn, "http://x/p1") == ("oversize", "estampado", "cuello redondo", "azul")
    assert _row(conn, "http://x/p2") == ("oversize", "estampado", "cuello redondo", "azul")


def test_backfill_no_pending_products_emits_single_completion_progress(monkeypatch, capsys):
    conn = FakeConnection(productos={})
    _use_fake_conn(monkeypatch, conn)

    ml_embeddings.backfill(force=False, use_gpu=True)

    out = capsys.readouterr().out
    progress = [json.loads(line) for line in out.strip().splitlines() if line.strip()]
    assert progress == [{"phase": "embedding", "pct": 100, "msg": "sin productos pendientes"}]


def test_backfill_db_connect_failure_degrades_without_raising(monkeypatch, capsys):
    def _boom_connect():
        raise RuntimeError("simulated: could not connect to server")

    monkeypatch.setattr(ml_embeddings, "_get_connection", _boom_connect)

    ml_embeddings.backfill(force=False, use_gpu=True)

    out = capsys.readouterr().out
    progress = [json.loads(line) for line in out.strip().splitlines() if line.strip()]
    assert progress == [
        {"phase": "embedding", "pct": 0, "msg": "error: no se pudo conectar a la base de datos"}
    ]


def test_backfill_use_gpu_false_forces_cpu_only_via_env_var(monkeypatch, fake_conn):
    _use_fake_conn(monkeypatch, fake_conn)
    monkeypatch.delenv("CUDA_VISIBLE_DEVICES", raising=False)
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, model_version=None, preloaded_images=None: {u: None for u in urls},
    )
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(force=True, use_gpu=False)

    assert ml_embeddings.os.environ.get("CUDA_VISIBLE_DEVICES") == "-1"


def test_backfill_use_gpu_true_does_not_touch_cuda_env_var(monkeypatch, fake_conn):
    _use_fake_conn(monkeypatch, fake_conn)
    monkeypatch.delenv("CUDA_VISIBLE_DEVICES", raising=False)
    monkeypatch.setattr(
        ml_embeddings,
        "embed_images",
        lambda urls, model_version=None, preloaded_images=None: {u: None for u in urls},
    )
    monkeypatch.setattr(
        ml_embeddings, "_download_image", lambda _url: (_ for _ in ()).throw(IOError("skip"))
    )

    ml_embeddings.backfill(force=True, use_gpu=True)

    assert "CUDA_VISIBLE_DEVICES" not in ml_embeddings.os.environ


# ─── backfill(): batching + single-download-per-image ───────────────────────


def test_backfill_batches_embed_images_calls_and_downloads_each_image_once(monkeypatch):
    """Regression: `embed_images` must be called once per chunk of
    `_BACKFILL_CHUNK_SIZE` products, never once per product, and every
    image must be downloaded exactly once — reused for both the embedding
    and `dominant_color()`."""
    chunk_size = ml_embeddings._BACKFILL_CHUNK_SIZE
    n = chunk_size * 2 + 5  # spans three chunks: full, full, partial
    productos = {
        f"http://x/p{i}": {"imagen_url": f"http://x/p{i}.jpg", "activo": 1,
                            "fit": "", "estampado": "", "escote": "", "color_dominante": ""}
        for i in range(n)
    }
    conn = FakeConnection(productos=productos)
    monkeypatch.setattr(ml_embeddings, "_get_connection", lambda: conn)

    embed_images_call_sizes = []
    download_counts = {}

    def _fake_embed_images(urls, model_version=None, preloaded_images=None):
        embed_images_call_sizes.append(len(urls))
        return {u: np.array([1.0, 0.0], dtype="float32") for u in urls}

    def _fake_download(url):
        download_counts[url] = download_counts.get(url, 0) + 1
        return "fake-pil-image"

    monkeypatch.setattr(ml_embeddings, "embed_images", _fake_embed_images)
    monkeypatch.setattr(ml_embeddings, "_download_image", _fake_download)
    monkeypatch.setattr(ml_embeddings, "classify", lambda embedding: _DEGRADED_ATTRS)
    monkeypatch.setattr(ml_embeddings, "dominant_color", lambda _image: "azul")

    ml_embeddings.backfill(force=False, use_gpu=True)

    # embed_images called once per chunk (3 calls), not once per product (n calls)
    assert embed_images_call_sizes == [chunk_size, chunk_size, 5]
    # every product's image downloaded exactly once, never twice
    assert len(download_counts) == n
    assert all(count == 1 for count in download_counts.values())


def test_emit_progress_degrades_silently_on_broken_pipe(monkeypatch):
    """Regression: `_emit_progress`'s `print` must never raise
    `BrokenPipeError`/`OSError` out of `backfill()` when the reading side
    of the pipe (Java's `PythonRunner`) already closed."""

    def _boom_print(*_a, **_kw):
        raise BrokenPipeError("reader closed")

    monkeypatch.setattr("builtins.print", _boom_print)

    ml_embeddings._emit_progress(50, "should not raise")  # must not raise
