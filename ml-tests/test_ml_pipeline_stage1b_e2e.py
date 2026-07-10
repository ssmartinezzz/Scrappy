# -*- coding: utf-8 -*-
"""
End-to-end test (T4.7) for ml_pipeline.py's stage 1b, with `ml_embeddings`
fully mocked (no torch/open_clip/PIL required — same no-network discipline
as every other file in this suite).

Runs the REAL `ml_pipeline.main()` CLI entrypoint against a small sample
`ml_productos.json` batch covering every gate branch (low text confidence,
generic category, blank gender, and the text-wins bypass case), asserts the
produced `ml_output.json`'s `scores` have the new visual-attribute keys
correctly gated (populated only where the gate fired) and that zero raw
English prompt text ever leaks into the output.
"""
import json
import sys
import types

import numpy as np
import pytest

import ml_pipeline


class _FakeImage:
    """Duck-typed stand-in for a PIL Image — never touched by anything
    outside the fake `ml_embeddings` module below."""
    pass


def _install_fake_ml_embeddings(monkeypatch):
    """Installs a fake `ml_embeddings` module into `sys.modules` so
    `ml_pipeline.main()`'s lazy `import ml_embeddings` picks it up instead
    of the real module — same technique used to isolate this module from
    torch/open_clip/PIL, which are not installed in this sandbox."""
    fake = types.ModuleType("ml_embeddings")
    fake.MODEL_VERSION = "fake-v1-e2e"

    fake.calls = {"download": [], "embed_images": [], "classify": [], "dominant_color": []}

    def _download_image(url):
        fake.calls["download"].append(url)
        if "brokenimg" in url:
            raise IOError("simulated broken image download")
        return _FakeImage()

    def embed_images(urls, db_path="scraper.db", model_version=None, preloaded_images=None):
        fake.calls["embed_images"].append(list(urls))
        results = {}
        for url in urls:
            preloaded = (preloaded_images or {}).get(url)
            results[url] = None if preloaded is None else np.ones(4, dtype="float32")
        return results

    def classify(embedding, db_path="scraper.db"):
        fake.calls["classify"].append(embedding is not None)
        if embedding is None:
            return {
                "categoria": "", "fit": "", "estampado": "", "escote": "",
                "genero": "", "genImgConf": 0.0, "catMLConf": 0.0,
            }
        return {
            "categoria": "Buzo",
            "fit": "oversize",
            "estampado": "liso",
            "escote": "cuello redondo",
            "genero": "hombre",
            "genImgConf": 0.91,
            "catMLConf": 0.88,
        }

    def dominant_color(image):
        fake.calls["dominant_color"].append(image is not None)
        return "azul"

    fake._download_image = _download_image
    fake.embed_images = embed_images
    fake.classify = classify
    fake.dominant_color = dominant_color

    monkeypatch.setitem(sys.modules, "ml_embeddings", fake)
    return fake


def _fake_predict_category(nombre_to_result):
    """Builds a fake `predict_category(text_model, le, nombre, ...)` that
    looks up a deterministic (categoria, confianza) per product name
    substring — stands in for a real trained text model/LabelEncoder pkl,
    which this sandbox does not have (no scikit-learn installed)."""

    def _fake(text_model, le, nombre, confianza_min=0.70):
        for needle, result in nombre_to_result.items():
            if needle in nombre:
                return result
        return None, 0.0

    return _fake


@pytest.fixture
def sample_productos():
    return [
        {
            "url": "https://site.test/remera-nike",
            "nombre": "Remera Nike Negra",
            "precio": 20000,
            "categoria": "Remera",
            "genero": "hombre",
            "img": "https://cdn.test/remera.jpg",
        },
        {
            "url": "https://site.test/buzo-adidas",
            "nombre": "Buzo Adidas Azul",
            "precio": 35000,
            "categoria": "Buzo",
            "genero": "hombre",
            "img": "https://cdn.test/buzo.jpg",
        },
        {
            "url": "https://site.test/gorra-puma",
            "nombre": "Gorra Puma Deportiva",
            "precio": 8000,
            "categoria": "Indumentaria",
            "genero": "mujer",
            "img": "https://cdn.test/gorra.jpg",
        },
        {
            "url": "https://site.test/pantalon-cargo",
            "nombre": "Pantalon Cargo Beige",
            "precio": 27000,
            "categoria": "Pantalón",
            "genero": "",
            "img": "https://cdn.test/pantalon.jpg",
        },
    ]


def test_stage1b_gates_visual_attrs_and_leaks_zero_english(
    tmp_path, monkeypatch, sample_productos
):
    fake_ml_embeddings = _install_fake_ml_embeddings(monkeypatch)

    # Deterministic fake text predictions — mirrors the exact scenarios the
    # gate must discriminate between:
    #   remera  -> confident + specific + gender present -> BYPASS image
    #   buzo    -> low confidence (0.30 < 0.75)           -> image fires
    #   gorra   -> confident BUT generic categoria         -> image fires
    #   pantalon-> confident + specific BUT blank genero   -> image fires
    fake_predict = _fake_predict_category({
        "remera":   ("Remera", 0.95),
        "buzo":     ("Buzo", 0.30),
        "gorra":    ("Indumentaria", 0.95),
        "pantalon": ("Pantalón", 0.95),
    })
    monkeypatch.setattr(ml_pipeline, "predict_category", fake_predict)

    prod_path = tmp_path / "ml_productos.json"
    out_path = tmp_path / "ml_output.json"
    prod_path.write_text(json.dumps(sample_productos), encoding="utf-8")

    monkeypatch.setattr(sys, "argv", ["ml_pipeline.py", str(prod_path), str(out_path)])

    ml_pipeline.main()

    assert out_path.exists()
    output = json.loads(out_path.read_text(encoding="utf-8"))
    scores = output["scores"]

    remera_score = scores["https://site.test/remera-nike"]
    buzo_score = scores["https://site.test/buzo-adidas"]
    gorra_score = scores["https://site.test/gorra-puma"]
    pantalon_score = scores["https://site.test/pantalon-cargo"]

    # ── Bypass case: confident + specific + gender present → never touched ──
    assert remera_score["fit"] == ""
    assert remera_score["print"] == ""
    assert remera_score["neckline"] == ""
    assert remera_score["color"] == ""
    assert remera_score["generoML"] == ""
    assert remera_score["genImgConf"] == 0.0

    # ── Gate-fired cases: all three trigger reasons populate identically,
    # since the fake classify() returns the same fixed attrs regardless of
    # WHY the gate fired ──────────────────────────────────────────────────
    for gated_score in (buzo_score, gorra_score, pantalon_score):
        assert gated_score["fit"] == "oversize"
        assert gated_score["print"] == "liso"
        assert gated_score["neckline"] == "cuello redondo"
        assert gated_score["color"] == "azul"
        assert gated_score["generoML"] == "hombre"
        assert gated_score["genImgConf"] == pytest.approx(0.91)

    # ── Every product still has the full new key set present (no KeyError
    # risk downstream in MlEnricher/PR5) ─────────────────────────────────
    for pid, s in scores.items():
        for key in ("fit", "print", "neckline", "color", "generoML", "genImgConf"):
            assert key in s, f"scores[{pid!r}] missing new visual-attr key {key!r}"

    # ── Zero English leakage anywhere in the output ("a photo of ..." must
    # never appear in Spanish-facing output — spec: Spanish-only guarantee)
    dumped = json.dumps(output, ensure_ascii=False).lower()
    assert "photo of" not in dumped
    assert "a photo" not in dumped

    # ── Sanity: the fake ml_embeddings collaborators were actually invoked
    # (not silently skipped) for the 3 gated products, and NOT for the
    # bypassed one — distinct URLs are downloaded/embedded once each ─────
    assert fake_ml_embeddings.calls["download"], "expected at least one image download"
    downloaded_urls = set(fake_ml_embeddings.calls["download"])
    assert "https://cdn.test/remera.jpg" not in downloaded_urls
    assert "https://cdn.test/buzo.jpg" in downloaded_urls
    assert "https://cdn.test/gorra.jpg" in downloaded_urls
    assert "https://cdn.test/pantalon.jpg" in downloaded_urls


def test_stage1b_survives_broken_image_download_without_crashing(
    tmp_path, monkeypatch, sample_productos
):
    """A single failed image download must degrade that one product to
    text-only (empty visual attrs), never crash the whole pipeline run —
    same never-raises discipline as the rest of ml_embeddings.py."""
    _install_fake_ml_embeddings(monkeypatch)

    broken = dict(sample_productos[1])
    broken["img"] = "https://cdn.test/brokenimg.jpg"
    productos = [sample_productos[0], broken]

    fake_predict = _fake_predict_category({
        "remera": ("Remera", 0.95),
        "buzo":   ("Buzo", 0.30),
    })
    monkeypatch.setattr(ml_pipeline, "predict_category", fake_predict)

    prod_path = tmp_path / "ml_productos.json"
    out_path = tmp_path / "ml_output.json"
    prod_path.write_text(json.dumps(productos), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["ml_pipeline.py", str(prod_path), str(out_path)])

    ml_pipeline.main()  # must not raise

    output = json.loads(out_path.read_text(encoding="utf-8"))
    buzo_score = output["scores"]["https://site.test/buzo-adidas"]
    # embed_images returns None for a failed-preload URL (per ml_embeddings'
    # own contract) -> classify(None) -> all-empty degrade, color stays ''
    # because no image was available for dominant_color() either.
    assert buzo_score["fit"] == ""
    assert buzo_score["color"] == ""
    assert buzo_score["generoML"] == ""


def test_stage1b_blank_gender_only_trigger_never_overwrites_confident_specific_categoria(
    tmp_path, monkeypatch
):
    """Judgment-day round-1 BLOCKER (A-001/B-001): when the gate fires
    SOLELY because gender is blank — text confidence is high AND the
    category is specific/non-generic — the image classifier must still be
    allowed to fill `generoML`/`genImgConf`, but it must NEVER overwrite
    `categoria`/`categoria_original`/`ml_cat_conf`, even when the image
    category disagrees and clears the 0.82/0.92 confidence gates. Only a
    questionable text category (txt_conf < 0.75 OR generic) may be
    overridden by image."""
    fake_ml_embeddings = _install_fake_ml_embeddings(monkeypatch)

    # Override classify() to return a DIFFERENT categoria than the text
    # prediction, at a confidence that would otherwise clear both the
    # `confianza >= 0.82` and `>= 0.92` override gates.
    def _classify_different_category(embedding, db_path="scraper.db"):
        fake_ml_embeddings.calls["classify"].append(embedding is not None)
        if embedding is None:
            return {
                "categoria": "", "fit": "", "estampado": "", "escote": "",
                "genero": "", "genImgConf": 0.0, "catMLConf": 0.0,
            }
        return {
            "categoria": "Campera",
            "fit": "oversize",
            "estampado": "liso",
            "escote": "cuello redondo",
            "genero": "hombre",
            "genImgConf": 0.91,
            "catMLConf": 0.95,
        }

    fake_ml_embeddings.classify = _classify_different_category

    productos = [
        {
            "url": "https://site.test/remera-confident",
            "nombre": "Remera Nike Negra",
            "precio": 20000,
            "categoria": "Remera",
            "genero": "",
            "img": "https://cdn.test/remera.jpg",
        },
    ]

    fake_predict = _fake_predict_category({
        "remera": ("Remera", 0.90),
    })
    monkeypatch.setattr(ml_pipeline, "predict_category", fake_predict)

    prod_path = tmp_path / "ml_productos.json"
    out_path = tmp_path / "ml_output.json"
    prod_path.write_text(json.dumps(productos), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["ml_pipeline.py", str(prod_path), str(out_path)])

    ml_pipeline.main()

    output = json.loads(out_path.read_text(encoding="utf-8"))
    score = output["scores"]["https://site.test/remera-confident"]

    # categoria must NOT be overwritten — the gate fired solely for blank
    # gender, not because the text category itself was questionable.
    # `main()` mutates its own internal `productos` copy (parsed from
    # `prod_path`), never written back to disk — the only externally
    # observable view of that mutation is `tendencias.topProductos`
    # (`make_prod_dict()` reads `p.get('categoria', '')` post-mutation).
    # With a single product it is deterministically selected as a
    # `budget_picks` entry.
    top_prods = output["tendencias"]["topProductos"]
    remera_top = next(
        t for t in top_prods if t["url"] == "https://site.test/remera-confident"
    )
    assert remera_top["categoria"] == "Remera"

    # generoML/genImgConf fill-in must still happen (additive, no gate).
    assert score["generoML"] == "hombre"
    assert score["genImgConf"] == pytest.approx(0.91)


def test_stage1b_embed_images_call_site_failure_degrades_to_text_only(
    tmp_path, monkeypatch, sample_productos
):
    """WARNING fix (judgment-day PR4 round 1, A-003/B-004): an unexpected
    failure of the `embed_images()` call itself (as opposed to a per-URL
    failure it already catches internally) must degrade this batch to
    text-only, never crash `main()`. Color extraction is independent of
    the embedding model (uses the already-downloaded image directly), so
    it is unaffected."""
    fake_ml_embeddings = _install_fake_ml_embeddings(monkeypatch)

    def _raising_embed_images(*args, **kwargs):
        raise RuntimeError("simulated embed_images() failure")

    fake_ml_embeddings.embed_images = _raising_embed_images

    fake_predict = _fake_predict_category({
        "gorra": ("Indumentaria", 0.95),
    })
    monkeypatch.setattr(ml_pipeline, "predict_category", fake_predict)

    prod_path = tmp_path / "ml_productos.json"
    out_path = tmp_path / "ml_output.json"
    prod_path.write_text(json.dumps([sample_productos[2]]), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["ml_pipeline.py", str(prod_path), str(out_path)])

    ml_pipeline.main()  # must not raise

    output = json.loads(out_path.read_text(encoding="utf-8"))
    score = output["scores"]["https://site.test/gorra-puma"]
    assert score["fit"] == ""
    assert score["generoML"] == ""
    assert score["color"] == "azul"  # download + dominant_color unaffected


def test_stage1b_import_guard_catches_non_import_error(
    tmp_path, monkeypatch, sample_productos
):
    """WARNING fix (judgment-day PR4 round 1, A-006/B-005): a corrupt or
    truncated extracted `ml_embeddings.py` can fail with e.g. a
    `SyntaxError` at import time, not just `ImportError` — the lazy-import
    guard in `main()` must catch that too and degrade to text-only instead
    of crashing the whole pipeline run."""
    import builtins

    real_import = builtins.__import__

    def _fake_import(name, *args, **kwargs):
        if name == "ml_embeddings":
            raise SyntaxError("simulated corrupt ml_embeddings.py")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", _fake_import)

    fake_predict = _fake_predict_category({
        "gorra": ("Indumentaria", 0.95),
    })
    monkeypatch.setattr(ml_pipeline, "predict_category", fake_predict)

    prod_path = tmp_path / "ml_productos.json"
    out_path = tmp_path / "ml_output.json"
    prod_path.write_text(json.dumps([sample_productos[2]]), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["ml_pipeline.py", str(prod_path), str(out_path)])

    ml_pipeline.main()  # must not raise (pre-fix: uncaught SyntaxError)

    output = json.loads(out_path.read_text(encoding="utf-8"))
    score = output["scores"]["https://site.test/gorra-puma"]
    assert score["fit"] == ""
    assert score["color"] == ""
