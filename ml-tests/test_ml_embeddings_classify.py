# -*- coding: utf-8 -*-
"""
Tests for ml_embeddings.py (PR3b1 slice): classify() + zero-shot prompts +
dominant_color(). The full-catalog backfill() CLI that consumes these is
a separate slice (PR3b2, ml-tests/test_ml_embeddings_backfill.py).

Same no-network, no-real-model-weights discipline as PR3a's
test_ml_embeddings.py: every heavy dependency (open_clip/torch/PIL) stays
behind this module's own lazy-import boundary, so these tests run green
even when open_clip/torch/PIL are NOT installed. `classify()` and
`dominant_color()` are tested via monkeypatched/fake collaborators
(`_get_prompt_embeddings`, a duck-typed fake PIL image) rather than the
real model, exactly like PR3a's `_load_model`/`_download_image` fakes.
"""
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


def _all_spanish_labels():
    """Closed Spanish label set per signal, derived from PROMPTS plus the
    abstain values `classify()` may emit (empty for everything, "unisex"
    additionally for genero)."""
    labels = {}
    for signal, entries in ml_embeddings.PROMPTS.items():
        allowed = {label for _english, label in entries}
        allowed.add("")
        labels[signal] = allowed
    labels["genero"].add("unisex")
    return labels


def _one_hot_prompt_embeddings():
    """Deterministic fake prompt embeddings: candidate `i` for a signal is
    a one-hot vector at index `i`, padded to a shared dimension across
    every signal (mirrors how a real model reuses one embedding space for
    every prompt). Lets tests craft an image embedding that aligns
    exactly with (or is orthogonal to) every signal's first candidate
    simultaneously, with zero dependency on the real model."""
    dim = max(len(entries) for entries in ml_embeddings.PROMPTS.values())
    fake = {}
    for signal, entries in ml_embeddings.PROMPTS.items():
        vectors = []
        for i, (_english, label) in enumerate(entries):
            v = np.zeros(dim, dtype="float32")
            v[i] = 1.0
            vectors.append((label, v))
        fake[signal] = vectors
    return fake, dim


# ─── classify(): closed Spanish label set / no English leakage ──────────────


def test_classify_above_threshold_uses_only_closed_spanish_labels(monkeypatch):
    fake_prompts, dim = _one_hot_prompt_embeddings()
    monkeypatch.setattr(ml_embeddings, "_get_prompt_embeddings", lambda db_path="scraper.db": fake_prompts)

    aligned = np.zeros(dim, dtype="float32")
    aligned[0] = 1.0

    result = ml_embeddings.classify(aligned)

    allowed = _all_spanish_labels()
    english_texts = {e for entries in ml_embeddings.PROMPTS.values() for e, _label in entries}
    for signal in ("categoria", "fit", "estampado", "escote", "genero"):
        assert result[signal] == ml_embeddings.PROMPTS[signal][0][1]
        assert result[signal] in allowed[signal]
        assert result[signal] not in english_texts
    assert result["catMLConf"] == pytest.approx(1.0)
    assert result["genImgConf"] == pytest.approx(1.0)


def test_classify_below_threshold_abstains_to_empty_and_genero_to_unisex(monkeypatch):
    fake_prompts, dim = _one_hot_prompt_embeddings()
    monkeypatch.setattr(ml_embeddings, "_get_prompt_embeddings", lambda db_path="scraper.db": fake_prompts)

    flat = np.zeros(dim, dtype="float32")  # orthogonal to every one-hot candidate

    result = ml_embeddings.classify(flat)

    allowed = _all_spanish_labels()
    assert result["categoria"] == ""
    assert result["fit"] == ""
    assert result["estampado"] == ""
    assert result["escote"] == ""
    assert result["genero"] == "unisex"
    assert result["catMLConf"] == 0.0
    for signal in ("categoria", "fit", "estampado", "escote", "genero"):
        assert result[signal] in allowed[signal]


def test_classify_catMLConf_reflects_score_even_when_categoria_abstains(monkeypatch):
    """Regression test: catMLConf must carry the real cosine score for
    `categoria` even when the label itself abstains to "" below threshold.
    Uses a NON-orthogonal, below-threshold embedding (unlike the
    all-zero `flat` vector in the abstain test above) so a bug that only
    sets `catMLConf` inside the `if score >= threshold` branch can't hide
    behind a coincidental score of exactly 0.0."""
    fake_prompts, dim = _one_hot_prompt_embeddings()
    monkeypatch.setattr(ml_embeddings, "_get_prompt_embeddings", lambda db_path="scraper.db": fake_prompts)

    weak = np.zeros(dim, dtype="float32")
    weak[0] = 0.1  # below categoria's min_prob (0.22), but not orthogonal/zero

    result = ml_embeddings.classify(weak)

    assert result["categoria"] == ""  # abstained: below threshold
    assert result["catMLConf"] == pytest.approx(0.1)  # but score must still be reported
    assert result["catMLConf"] != 0.0


# ─── classify(): degradation paths ───────────────────────────────────────────


def test_classify_degrades_to_all_empty_when_embedding_is_none():
    def _boom(*_a, **_kw):
        raise AssertionError("prompt embeddings should never be computed without an image embedding")

    import ml_embeddings as m

    orig = m._get_prompt_embeddings
    m._get_prompt_embeddings = _boom
    try:
        result = ml_embeddings.classify(None)
    finally:
        m._get_prompt_embeddings = orig

    assert result == {
        "categoria": "",
        "fit": "",
        "estampado": "",
        "escote": "",
        "genero": "",
        "genImgConf": 0.0,
        "catMLConf": 0.0,
    }


def test_classify_degrades_to_all_empty_when_prompt_embeddings_unavailable(monkeypatch):
    monkeypatch.setattr(ml_embeddings, "_get_prompt_embeddings", lambda db_path="scraper.db": None)

    result = ml_embeddings.classify(np.array([1.0, 0.0, 0.0], dtype="float32"))

    assert result == {
        "categoria": "",
        "fit": "",
        "estampado": "",
        "escote": "",
        "genero": "",
        "genImgConf": 0.0,
        "catMLConf": 0.0,
    }


def test_classify_catches_unexpected_failure_and_degrades_to_empty(monkeypatch):
    def _boom_prompts(db_path="scraper.db"):
        return {"categoria": "not-a-list-of-tuples"}  # will raise when iterated as candidates

    monkeypatch.setattr(ml_embeddings, "_get_prompt_embeddings", _boom_prompts)

    result = ml_embeddings.classify(np.array([1.0, 0.0], dtype="float32"))

    assert result["categoria"] == ""
    assert result["genero"] == ""


# ─── dominant_color(): Pillow histogram, independent of the model ───────────


class _FakeImage:
    def __init__(self, colors):
        self._colors = colors  # list of (count, (r, g, b))

    def convert(self, _mode):
        return self

    def resize(self, _size):
        return self

    def getcolors(self, _maxcolors):
        return self._colors


def test_dominant_color_returns_value_from_fixed_spanish_palette():
    image = _FakeImage([(5, (250, 250, 250)), (50, (10, 10, 10))])  # near-black dominates

    color = ml_embeddings.dominant_color(image)

    assert color in ml_embeddings.COLOR_PALETTE
    assert color == "negro"


def test_dominant_color_never_calls_the_vision_language_model(monkeypatch):
    def _boom_load(*_a, **_kw):
        raise AssertionError("dominant_color must never touch the SigLIP model")

    monkeypatch.setattr(ml_embeddings, "_load_model", _boom_load)
    monkeypatch.setattr(ml_embeddings, "_get_prompt_embeddings", _boom_load)

    image = _FakeImage([(10, (30, 80, 180))])  # azul

    assert ml_embeddings.dominant_color(image) == "azul"


def test_dominant_color_degrades_to_empty_on_broken_image():
    class _BrokenImage:
        def convert(self, _mode):
            raise IOError("simulated: corrupted image data")

    assert ml_embeddings.dominant_color(_BrokenImage()) == ""


def test_dominant_color_degrades_to_empty_when_no_pixel_data():
    assert ml_embeddings.dominant_color(_FakeImage([])) == ""
