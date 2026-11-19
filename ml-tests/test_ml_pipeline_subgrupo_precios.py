# -*- coding: utf-8 -*-
"""Los precios se puntúan contra su sub_categoria (sólo subs de tipo, con >= MIN_GROUP filas); todo producto
sigue aportando a su categoría, así que quien no tiene sub conserva su puntaje.

Los precios son los reales de Samsung Argentina (scrape 2026-11-19): 39 teléfonos de mediana $1,2M contra
187 accesorios de mediana $85k. Con una sola distribución, hasta el teléfono más barato queda en el p62.
"""
import json
import statistics
from collections import defaultdict

import ml_pipeline
import pytest

PARENTS = {'sneaker': 'zapatilla'}


def _contar(filas):
    cat_counts, sub_counts = defaultdict(int), defaultdict(int)
    for cat, sub, _ in filas:
        cat_counts[cat] += 1
        s = ml_pipeline.sub_que_parte(cat, sub)
        if s:
            sub_counts[(cat, s)] += 1
    return cat_counts, sub_counts


def _clave(fila, filas, rubro='tecnologia'):
    cat_counts, sub_counts = _contar(filas)
    cat, sub, _ = fila
    base, clave_sub = ml_pipeline.clave_de_precio(cat, rubro, sub, cat_counts, sub_counts, PARENTS)
    return clave_sub or base


def _celulares():
    telefonos = [(('celular', 'smartphone', 400000 + i * 70000)) for i in range(12)]
    fundas = [(('celular', 'funda', 30000 + i * 2500)) for i in range(25)]
    return telefonos, fundas


def test_sin_sub_la_clave_es_la_de_siempre():
    filas = [('remera', '', 1000)] * 25 + [('notebook', '', 900000)] * 3
    assert _clave(('remera', '', 1000), filas, rubro='indumentaria') == 'remera'
    assert _clave(('notebook', '', 900000), filas) == 'tech|notebook'
    assert _clave(('sneaker', '', 5), [('sneaker', '', 5)] * 3 + [('zapatilla', '', 9)] * 12,
                  rubro='indumentaria') == 'zapatilla'


def test_cualquier_categoria_se_parte_por_su_sub_de_tipo():
    """Cooler líquida (mediana $222k) y pasta ($16,8k) no comparten distribución."""
    filas = [('cooler', 'liquida', 222000)] * 12 + [('cooler', 'pasta', 16800)] * 12
    assert _clave(('cooler', 'liquida', 1), filas) == 'tech|cooler|liquida'
    assert _clave(('cooler', 'pasta', 1), filas) == 'tech|cooler|pasta'
    assert _clave(('cpu', 'am5', 1), [('cpu', 'am5', 1)] * 10) == 'tech|cpu|am5'


def test_una_etiqueta_de_deporte_no_parte_la_distribucion():
    """`running` sobre una remera o `hockey` sobre Alimentos (1 fila a $666k) son etiquetas por nombre, no tipos."""
    filas = [('remera', 'running', 50000)] * 30
    assert _clave(('remera', 'running', 1), filas, rubro='indumentaria') == 'remera'
    assert _clave(('alimentos', 'hockey', 1), [('alimentos', 'hockey', 1)] * 12, rubro='indumentaria') == 'alimentos'


def test_donde_el_deporte_define_el_producto_si_parte():
    filas = [('pelota', 'futbol', 12500)] * 12 + [('pelota', 'voley', 54000)] * 12
    assert _clave(('pelota', 'futbol', 1), filas, rubro='indumentaria') == 'pelota|futbol'
    assert _clave(('pelota', 'voley', 1), filas, rubro='indumentaria') == 'pelota|voley'


def test_celular_con_sub_suficiente_se_parte_por_sub():
    tel, fun = _celulares()
    filas = tel + fun
    assert _clave(tel[0], filas) == 'tech|celular|smartphone'
    assert _clave(fun[0], filas) == 'tech|celular|funda'


def test_celular_con_sub_chica_cae_a_la_categoria():
    tel, fun = _celulares()
    raro = ('celular', 'soporte', 15000)
    filas = tel + fun + [raro] * (ml_pipeline.MIN_GROUP - 1)
    assert _clave(raro, filas) == 'tech|celular'  # sub con < MIN_GROUP filas: la categoría
    assert _clave(('celular', '', 1), filas) == 'tech|celular'


def test_el_telefono_mas_barato_deja_de_estar_en_el_p62():
    """Distribución real de Samsung: mezclada, el más barato es p62; partida, es el piso de los teléfonos."""
    telefonos = [(float(p)) for p in
                 [499999, 599999, 699999, 799999, 899999, 899999, 999999, 1099999, 1199999, 1199999, 1299999,
                  1399999, 1499999, 1699999, 1799999, 1999999, 2199999, 2699999, 2799999]]
    accesorios = [float(p) for p in [15000 + i * 900 for i in range(120)]] + \
                 [float(p) for p in [90000 + i * 5000 for i in range(67)]]
    mezclado = ml_pipeline.PriceStats(telefonos + accesorios)
    partido = ml_pipeline.PriceStats(telefonos)
    barato = min(telefonos)
    assert mezclado.percentile_rank(barato) > 60
    assert partido.percentile_rank(barato) < 10
    assert mezclado.z_score_modified(statistics.median(telefonos)) > 5
    assert abs(partido.z_score_modified(statistics.median(telefonos))) < 0.5


def test_main_puntua_cada_sub_contra_su_propio_grupo(tmp_path, monkeypatch):
    """Corrida real de main(): 12 teléfonos y 25 fundas; el teléfono más barato no queda en lo más alto."""
    monkeypatch.chdir(tmp_path)
    filas = []
    for i in range(12):
        filas.append({'url': f'http://t/{i}', 'nombre': f'Celular X{i}', 'precio': 400000 + i * 70000,
                      'categoria': 'Celular', 'subCategoria': 'Smartphone', 'rubro': 'tecnologia',
                      'sitio': 'Samsung', 'genero': '', 'marca': 'Samsung'})
    for i in range(25):
        filas.append({'url': f'http://f/{i}', 'nombre': f'Funda Y{i}', 'precio': 30000 + i * 2500,
                      'categoria': 'Celular', 'subCategoria': 'Funda', 'rubro': 'tecnologia',
                      'sitio': 'Samsung', 'genero': '', 'marca': 'Samsung'})
    (tmp_path / 'p.json').write_text(json.dumps(filas))
    monkeypatch.setattr('sys.argv', ['ml_pipeline.py', 'p.json', 'o.json'])
    ml_pipeline.main()
    scores = json.loads((tmp_path / 'o.json').read_text())['scores']
    assert scores['http://t/0']['pctil'] < 10
    assert scores['http://t/11']['pctil'] > 90
    assert scores['http://f/0']['pctil'] < 10
    assert scores['http://f/24']['pctil'] > 90
    assert abs(scores['http://t/5']['zScore']) < 2


def test_quien_no_tiene_sub_conserva_el_puntaje_de_siempre(tmp_path, monkeypatch):
    """Los sin sub se puntúan contra la categoría ENTERA (también los que tienen sub aportan a ella)."""
    monkeypatch.chdir(tmp_path)
    def fila(i, sub, precio):
        return {'url': f'http://c/{i}', 'nombre': f'Cooler {i}', 'precio': precio, 'categoria': 'Cooler',
                'subCategoria': sub, 'rubro': 'tecnologia', 'sitio': 'S', 'genero': '', 'marca': ''}
    filas = [fila(i, 'liquida', 200000 + i * 1000) for i in range(12)]
    filas += [fila(100 + i, 'pasta', 15000 + i * 500) for i in range(12)]
    filas += [fila(200 + i, '', 100000 + i * 5000) for i in range(6)]
    (tmp_path / 'p.json').write_text(json.dumps(filas))
    monkeypatch.setattr('sys.argv', ['ml_pipeline.py', 'p.json', 'o.json'])
    ml_pipeline.main()
    scores = json.loads((tmp_path / 'o.json').read_text())['scores']
    todos = ml_pipeline.PriceStats([f['precio'] for f in filas])
    for f in filas[24:]:
        assert scores[f['url']]['pctil'] == todos.percentile_rank(f['precio'])
    assert scores['http://c/0']['pctil'] < 10 and scores['http://c/100']['pctil'] < 10
