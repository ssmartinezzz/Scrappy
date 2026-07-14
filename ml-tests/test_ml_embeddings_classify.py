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


# ─── PROMPTS: pinned against PR1's Product.VisualAttrs vocabulary ───────────


def test_prompts_visual_attr_labels_match_pr1_visualattrs_vocabulary():
    """Regression: pins EVERY `PROMPTS` label for `fit`, `estampado`, and
    `escote` against the closed label sets documented in `Product.
    VisualAttrs`'s javadoc (PR1, `Product.java`; `colorDominante`'s closed
    set is pinned separately via `COLOR_PALETTE`/`dominant_color()`
    tests). `Product.java` is the single source of truth for that
    vocabulary, but `visual_attrs_vocabulary` below is a MANUAL PIN of it
    (this Python module has no access to the Java source at test time) —
    it must be kept in sync BY HAND if PR1's javadoc vocabulary ever
    changes. In particular, this is the regression test for the escote
    v-neck label: no other existing test asserts its literal value, so
    reverting "en v" back to "cuello en v" would otherwise stay green
    (the other classify() tests only ever exercise the FIRST candidate,
    "cuello redondo")."""
    visual_attrs_vocabulary = {
        "fit": {"oversize", "entallado", "regular"},
        "estampado": {"estampado", "liso"},
        "escote": {"cuello redondo", "en v", "capucha", "con cuello"},
    }
    for signal, allowed_labels in visual_attrs_vocabulary.items():
        actual_labels = {label for _english, label in ml_embeddings.PROMPTS[signal]}
        assert actual_labels == allowed_labels, (
            f"PROMPTS[{signal!r}] labels {actual_labels} must exactly match "
            f"Product.VisualAttrs' javadoc vocabulary {allowed_labels}"
        )
    # Explicit pin, spelled out, for the exact bug just fixed:
    escote_labels = dict(ml_embeddings.PROMPTS["escote"])
    v_neck_english = "a photo of a garment with a v-shaped neckline"
    assert escote_labels[v_neck_english] == "en v"


# ─── PROMPTS['categoria']: pinned against CategoryClassifier's fine output ──


def _categoria_vocabulary_from_java_category_classifier():
    """Manual pin (PR4) of every VISUALLY-classifiable garment/footwear/
    accessory string `CategoryClassifier.clasificar()`
    (scraper/src/main/java/ar/scraper/aggregator/normalize/
    CategoryClassifier.java) can return — i.e. the FINE canonical Spanish
    category output space of the Java text classifier, per PR4's task
    scope decision (expanding `PROMPTS['categoria']` from PR3b1's 13 coarse
    labels to this fine set, so image-emitted categories have a real
    chance of surviving the stage-1b `_TEXT_LABEL_SET` filter).

    Deliberately EXCLUDES:
      - every `tech` category (Notebook, PC, Monitor, GPU, CPU, RAM,
        Gabinete, Teclado, Mouse, Auricular, Webcam) — not garment/image
        classifiable by a fashion vision-language model;
      - every nutrition/supplement/food category (Creatina, Proteína,
        Colágeno, Magnesio, Pre-Workout, BCAA, Vitaminas, Quemadores,
        Gainer, Suplemento, Alimentos, and their "Barra/Pancake/Snack
        Proteico" subcategories) — same reason;
      - Perfume — a personal-care/fragrance product, not a wearable
        garment/footwear/accessory;
      - Accesorio Deportivo — too broad/mixed a bucket (includes
        non-visual items like protein shakers and bandages alongside
        knee braces), not a clean single visual concept.

    This is a MANUAL PIN (this Python module has no access to the Java
    source at test time), same discipline as the fit/estampado/escote
    vocabulary pin above — must be kept in sync BY HAND if
    CategoryClassifier.java's canonical output strings ever change.
    """
    return {
        # Calzado (footwear) — más específico primero, igual que el propio
        # clasificador Java.
        "Zapatilla Running", "Zapatilla Entrenamiento", "Zapatilla Skate",
        "Zapatilla Urbana", "Sneaker", "Zapatilla",
        "Botines", "Borcego", "Pantufla", "Zapato", "Mocasin", "Sandalia",
        "Ojotas", "Botas",
        # Ropa interior / baño
        "Calzoncillos", "Corpino", "Malla",
        # Indumentaria superior
        "Puffer", "Piloto", "Traje", "Saco", "Chaleco", "Campera",
        "Sweater", "Buzo", "Casaca", "Chomba", "Musculosa", "Camisa",
        "Remera",
        # Indumentaria inferior
        "Calza", "Baggy", "Jean", "Jogging", "Bermuda", "Short", "Vestido",
        "Enterito", "Pollera", "Pantalón",
        # Accesorios
        "Billetera", "Riñonera", "Mochila", "Bolso", "Cinturón", "Bufanda",
        "Guantes", "Lentes", "Gorro", "Gorra", "Medias",
    }


def test_prompts_categoria_labels_match_category_classifier_fine_vocabulary():
    """Pins `PROMPTS['categoria']`'s Spanish label set against the manual
    Java-derived vocabulary above — the whole point of PR4's expansion is
    that every image-emittable categoria label has a real chance of
    surviving `ml_pipeline.py`'s `_TEXT_LABEL_SET` filter, which only
    contains strings `CategoryClassifier.java` can actually produce."""
    expected = _categoria_vocabulary_from_java_category_classifier()
    actual = {label for _english, label in ml_embeddings.PROMPTS["categoria"]}
    assert actual == expected, (
        f"PROMPTS['categoria'] labels drifted from the CategoryClassifier.java "
        f"pin.\nMissing: {expected - actual}\nUnexpected: {actual - expected}"
    )


def test_prompts_categoria_has_no_duplicate_spanish_labels_or_english_prompts():
    """Each garment maps to exactly one canonical Spanish string, and every
    English zero-shot prompt phrase is distinct (a duplicated prompt would
    make two different labels share one text embedding)."""
    entries = ml_embeddings.PROMPTS["categoria"]
    spanish_labels = [label for _english, label in entries]
    english_prompts = [english for english, _label in entries]
    assert len(spanish_labels) == len(set(spanish_labels)), "duplicate Spanish categoria label"
    assert len(english_prompts) == len(set(english_prompts)), "duplicate English categoria prompt"


def test_prompts_categoria_english_prompts_never_equal_a_spanish_label():
    """Sanity guard against a copy-paste bug where an English prompt string
    accidentally becomes indistinguishable from a Spanish label (defense in
    depth for the Spanish-only output guarantee, on top of the leakage
    checks in the classify() tests below)."""
    entries = ml_embeddings.PROMPTS["categoria"]
    spanish_labels = {label for _english, label in entries}
    for english, _label in entries:
        assert english not in spanish_labels
        assert english.startswith("a photo of")


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
