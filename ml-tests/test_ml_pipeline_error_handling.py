# -*- coding: utf-8 -*-
"""
Tests for the two error handlers on ml_pipeline.py's price-history path.

Both handlers exist for a good reason: a malformed date string or a corrupt
history file must degrade to a neutral value, not abort a whole run over one
bad row. The defect is their REACH, not their existence — written as a bare
``except:``, they catch ``BaseException``, which includes ``KeyboardInterrupt``
and ``SystemExit``.

That turns a Ctrl-C into a silent ``0`` (or an empty history) and lets the
pipeline carry on as if nothing happened, producing a run whose ML output is
quietly wrong instead of one that stopped when the operator said stop. The
pipeline runs as a subprocess of the Java backend (``PythonRunner``), so
``SystemExit`` is how a shutdown actually arrives.

The contract asserted here: ordinary failures still degrade, interrupts still
propagate.
"""
import json

import pytest

import ml_pipeline


# ─── HistoricalAnalysis.price_velocity(): degrade on data, not on interrupts ──


def _serie():
    """Two well-formed points — enough for price_velocity to do real work,
    so nothing short-circuits before reaching the handler under test."""
    return [
        {'fecha': '2026-01-01', 'precio': 100.0},
        {'fecha': '2026-01-11', 'precio': 90.0},
    ]


def test_price_velocity_computes_over_a_well_formed_series():
    """Baseline: the happy path must keep working. -10% over 10 days = -1%/day."""
    assert ml_pipeline.HistoricalAnalysis(_serie()).price_velocity() == pytest.approx(-1.0)


def test_price_velocity_degrades_to_zero_on_an_unparseable_date():
    """The handler's real job: one malformed row yields a neutral 0, it does
    not blow up the run."""
    serie = [
        {'fecha': '2026-01-01', 'precio': 100.0},
        {'fecha': 'no-es-una-fecha', 'precio': 90.0},
    ]
    assert ml_pipeline.HistoricalAnalysis(serie).price_velocity() == 0


def test_price_velocity_degrades_to_zero_when_the_first_price_is_zero():
    """A zero opening price divides by zero. Also a data problem, also neutral."""
    serie = [
        {'fecha': '2026-01-01', 'precio': 0.0},
        {'fecha': '2026-01-11', 'precio': 90.0},
    ]
    assert ml_pipeline.HistoricalAnalysis(serie).price_velocity() == 0


def test_price_velocity_lets_a_keyboard_interrupt_through(monkeypatch):
    """Ctrl-C during the run must reach the caller, not be recorded as a
    velocity of 0. Patching the module's `datetime` is how we raise from
    INSIDE the try block without touching the surrounding data."""
    class _InterruptingClock:
        @staticmethod
        def strptime(*_args, **_kwargs):
            raise KeyboardInterrupt("operator pressed ctrl-c mid-analysis")

    monkeypatch.setattr(ml_pipeline, 'datetime', _InterruptingClock)

    with pytest.raises(KeyboardInterrupt):
        ml_pipeline.HistoricalAnalysis(_serie()).price_velocity()


def test_price_velocity_lets_a_system_exit_through(monkeypatch):
    """Same contract for SystemExit — how a shutdown reaches this subprocess."""
    class _ExitingClock:
        @staticmethod
        def strptime(*_args, **_kwargs):
            raise SystemExit(0)

    monkeypatch.setattr(ml_pipeline, 'datetime', _ExitingClock)

    with pytest.raises(SystemExit):
        ml_pipeline.HistoricalAnalysis(_serie()).price_velocity()


# ─── cargar_historial(): same contract, on the file-load handler ─────────────


def test_cargar_historial_returns_empty_when_no_path_is_given():
    assert ml_pipeline.cargar_historial('') == {}
    assert ml_pipeline.cargar_historial(None) == {}


def test_cargar_historial_returns_empty_when_the_file_does_not_exist(tmp_path):
    assert ml_pipeline.cargar_historial(str(tmp_path / 'nope.json')) == {}


def test_cargar_historial_reads_a_well_formed_file(tmp_path):
    destino = tmp_path / 'historial.json'
    destino.write_text(json.dumps({'http://x/p': [{'fecha': '2026-01-01', 'precio': 10.0}]}),
                       encoding='utf-8')
    assert ml_pipeline.cargar_historial(str(destino)) == {
        'http://x/p': [{'fecha': '2026-01-01', 'precio': 10.0}]
    }


def test_cargar_historial_degrades_to_empty_on_a_corrupt_file(tmp_path):
    """A truncated or garbled history file starts the run from scratch rather
    than aborting it — the behaviour the handler was written for."""
    destino = tmp_path / 'historial.json'
    destino.write_text('{"roto": ', encoding='utf-8')
    assert ml_pipeline.cargar_historial(str(destino)) == {}


def test_cargar_historial_lets_a_keyboard_interrupt_through(tmp_path, monkeypatch):
    """Ctrl-C while the history file is being parsed must propagate, not be
    swallowed into an empty history that silently discards every price point."""
    destino = tmp_path / 'historial.json'
    destino.write_text('{}', encoding='utf-8')

    def _interrupting_load(*_args, **_kwargs):
        raise KeyboardInterrupt("operator pressed ctrl-c mid-load")

    monkeypatch.setattr(ml_pipeline.json, 'load', _interrupting_load)

    with pytest.raises(KeyboardInterrupt):
        ml_pipeline.cargar_historial(str(destino))
