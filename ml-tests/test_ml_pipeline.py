# -*- coding: utf-8 -*-
"""
Tests for ml_pipeline.py's stage "1b" image-fallback gate (PR4).

Stage 1b decides, per product, whether the zero-shot image classifier
(``ml_embeddings.embed_images`` + ``classify()``) is worth invoking at all.
The text classifier stays authoritative (spec: "visual-classification-rules"
text-wins invariant) — image is a FILL-ONLY signal, so the gate must only
fire when text is genuinely uncertain: low confidence, a generic/placeholder
category, or (new in PR4) a blank gender, even when the text category itself
is confident and specific.

``needs_image_fallback`` is a small pure function so this gate can be unit
tested in isolation, without needing a real/mocked model — the full
integration (stage 1b actually calling ``ml_embeddings``, writing the new
score keys) is covered end-to-end in ``test_ml_pipeline_stage1b_e2e.py``.
"""
import ml_pipeline


# ─── needs_image_fallback(): pure gate logic ─────────────────────────────────


def test_gate_fires_on_low_text_confidence():
    """Text confidence below 0.75 always triggers image fallback, even for
    an already-specific, non-generic category with gender present."""
    assert ml_pipeline.needs_image_fallback(0.50, "Remera", "hombre") is True


def test_gate_fires_on_generic_category_even_with_high_confidence():
    """A generic/placeholder category (per the existing `genericas` set)
    triggers image fallback regardless of how confident the text model is."""
    assert ml_pipeline.needs_image_fallback(0.99, "Indumentaria", "hombre") is True
    assert ml_pipeline.needs_image_fallback(0.99, "Ropa", "mujer") is True
    assert ml_pipeline.needs_image_fallback(0.99, "General", "hombre") is True
    assert ml_pipeline.needs_image_fallback(0.99, "", "hombre") is True


def test_gate_fires_on_blank_gender_even_when_text_is_confident_and_specific():
    """NEW in PR4: a blank gender triggers image fallback even when the text
    category itself is confident AND specific — image can fill a gender gap
    without touching a category the text model already got right."""
    assert ml_pipeline.needs_image_fallback(0.95, "Remera", "") is True
    assert ml_pipeline.needs_image_fallback(0.95, "Remera", "   ") is True


def test_gate_bypasses_when_text_confident_specific_and_gender_present():
    """The text-wins invariant: confident + specific + gender present means
    image classification is never attempted for this product at all."""
    assert ml_pipeline.needs_image_fallback(0.90, "Remera", "hombre") is False
    assert ml_pipeline.needs_image_fallback(0.75, "Zapatilla Running", "mujer") is False


def test_gate_boundary_at_exactly_0_75_bypasses():
    """0.75 itself is NOT below threshold (`< 0.75`, not `<=`) — matches the
    pre-existing bespoke-image-model gate's boundary verbatim."""
    assert ml_pipeline.needs_image_fallback(0.75, "Remera", "hombre") is False


def test_gate_uses_module_level_genericas_by_default():
    """`needs_image_fallback` defaults to the module's own `GENERICAS` set —
    callers don't need to pass it explicitly (mirrors how stage 1b's own
    `img_candidates` comprehension will call it)."""
    assert "indumentaria" in ml_pipeline.GENERICAS
    assert "general" in ml_pipeline.GENERICAS
    assert "ropa" in ml_pipeline.GENERICAS
    assert "pc & tech" in ml_pipeline.GENERICAS
    assert "tecnologia" in ml_pipeline.GENERICAS
    assert "" in ml_pipeline.GENERICAS


def test_gate_accepts_explicit_genericas_override():
    custom = frozenset({"custom-generic"})
    assert ml_pipeline.needs_image_fallback(0.99, "custom-generic", "hombre", genericas=custom) is True
    assert ml_pipeline.needs_image_fallback(0.99, "indumentaria", "hombre", genericas=custom) is False
