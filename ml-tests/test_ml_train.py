# -*- coding: utf-8 -*-
"""
Tests for ml_train.py's image-training decommission (PR4, T4.5/T4.6).

Design decision #7 / spec "ml-training-removal": the bespoke image
classifier (MobileNetV3/EfficientNet, PyTorch) is removed entirely — text
classification (TF-IDF + LogisticRegression) is unaffected and stays the
only thing `ml_train.py` actually trains. `--images` becomes a no-op that
warns and exits cleanly instead of running `train_image_model()`.

No real DB/scikit-learn is needed: `load_dataset` and `train_text_model`
are monkeypatched with deterministic fakes (this sandbox has neither a real
`scraper.db` nor scikit-learn installed — same discipline as every other
file in this suite that avoids torch/open_clip/PIL).
"""
import json
import sys

import pytest

import ml_train


def _fake_text_meta():
    return {
        "accuracy": 0.87,
        "num_classes": 3,
        "num_train": 40,
        "num_test": 10,
        "classes": ["Remera", "Buzo", "Jean"],
        "trained_at": "2026-07-10T00:00:00",
    }


def _install_fakes(monkeypatch, tmp_path):
    db_path = str(tmp_path / "scraper.db")
    monkeypatch.setattr(
        ml_train, "load_dataset",
        lambda dbp: [("remera negra", "Remera", "indumentaria", "Nike")] * 60,
    )
    monkeypatch.setattr(ml_train, "train_text_model", lambda cleaned, models_dir: _fake_text_meta())
    return db_path


# ─── T4.5: `--images` is a no-op that warns and exits cleanly ───────────────


def test_images_flag_is_noop_and_does_not_crash(monkeypatch, tmp_path, capsys):
    db_path = _install_fakes(monkeypatch, tmp_path)
    monkeypatch.setattr(sys, "argv", ["ml_train.py", db_path, "--images"])

    ml_train.main()  # must not raise, even with no torch/PyTorch installed

    captured = capsys.readouterr()
    combined = (captured.out + captured.err).lower()
    assert "no-op" in combined
    assert "image training removed" in combined


def test_images_flag_result_has_noop_status(monkeypatch, tmp_path, capsys):
    db_path = _install_fakes(monkeypatch, tmp_path)
    monkeypatch.setattr(sys, "argv", ["ml_train.py", db_path, "--images"])

    ml_train.main()

    captured = capsys.readouterr()
    # main() prints the final JSON summary as the LAST stdout line.
    last_line = [line for line in captured.out.strip().splitlines() if line.strip()][-1]
    results = json.loads(last_line)
    assert results["image"]["status"] == "no-op"
    assert "image training removed" in results["image"]["reason"].lower()


# ─── Image-training functions are fully removed, not just unreachable ───────


def test_image_training_functions_are_removed():
    removed = (
        "train_image_model", "build_model", "get_device_info",
        "load_image_dataset", "download_images", "_download_one",
        "ProductImageDataset",
    )
    for name in removed:
        assert not hasattr(ml_train, name), (
            f"ml_train.{name} should have been removed — image training is "
            f"decommissioned (spec: ml-training-removal)"
        )


# ─── Text-training branch stays completely unaffected ───────────────────────


def test_text_training_branch_still_runs_and_is_unaffected_by_images_flag(monkeypatch, tmp_path):
    calls = {"text": 0}

    def fake_train_text(cleaned, models_dir):
        calls["text"] += 1
        return _fake_text_meta()

    db_path = str(tmp_path / "scraper.db")
    monkeypatch.setattr(ml_train, "load_dataset", lambda dbp: [("remera negra", "Remera", "indumentaria", "Nike")] * 60)
    monkeypatch.setattr(ml_train, "train_text_model", fake_train_text)
    monkeypatch.setattr(sys, "argv", ["ml_train.py", db_path])  # no --images at all

    ml_train.main()

    assert calls["text"] == 1


def test_text_training_still_runs_when_images_flag_is_also_passed(monkeypatch, tmp_path):
    """--images must not short-circuit or otherwise affect the text branch —
    only its own (now no-op) branch changes."""
    calls = {"text": 0}

    def fake_train_text(cleaned, models_dir):
        calls["text"] += 1
        return _fake_text_meta()

    db_path = str(tmp_path / "scraper.db")
    monkeypatch.setattr(ml_train, "load_dataset", lambda dbp: [("remera negra", "Remera", "indumentaria", "Nike")] * 60)
    monkeypatch.setattr(ml_train, "train_text_model", fake_train_text)
    monkeypatch.setattr(sys, "argv", ["ml_train.py", db_path, "--images"])

    ml_train.main()

    assert calls["text"] == 1


def test_results_have_no_image_key_when_images_flag_not_passed(monkeypatch, tmp_path, capsys):
    db_path = _install_fakes(monkeypatch, tmp_path)
    monkeypatch.setattr(sys, "argv", ["ml_train.py", db_path])

    ml_train.main()

    captured = capsys.readouterr()
    last_line = [line for line in captured.out.strip().splitlines() if line.strip()][-1]
    results = json.loads(last_line)
    assert "image" not in results
