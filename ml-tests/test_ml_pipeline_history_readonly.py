# -*- coding: utf-8 -*-
"""
`ml_historial.json` is an INPUT derived by Java from the precio_historico
table, rebuilt on every run. ml_pipeline.py reads it and appends today's price
in memory, but must never write it back: a second owner of price history is
what let the ML and the database drift apart.
"""
import json
import sys

import ml_pipeline


def _run(tmp_path, monkeypatch, historial):
    trabajo = tmp_path
    productos = trabajo / 'ml_productos.json'
    productos.write_text(json.dumps([{
        'url': 'https://site.test/remera', 'nombre': 'Remera Nike Dry',
        'precio': 8000, 'categoria': 'Remera', 'genero': 'hombre', 'sitio': 'Test',
    }]), encoding='utf-8')
    hist = trabajo / 'ml_historial.json'
    hist.write_text(json.dumps(historial), encoding='utf-8')
    salida = trabajo / 'ml_output.json'
    monkeypatch.chdir(trabajo)
    monkeypatch.setattr(sys, 'argv', ['ml_pipeline.py', str(productos), str(salida), str(hist)])
    ml_pipeline.main()
    return hist, salida


def test_main_never_writes_the_history_file_back(tmp_path, monkeypatch):
    historial = {'https://site.test/remera': [
        {'fecha': '2026-10-30', 'precio': 9000}, {'fecha': '2026-10-31', 'precio': 9000}]}
    original = json.dumps(historial)

    hist, salida = _run(tmp_path, monkeypatch, historial)

    assert salida.exists()
    assert hist.read_text(encoding='utf-8') == original
