# -*- coding: utf-8 -*-
"""add-inpro-office-store: los thresholds de badges no tienen escala.

Este archivo existe por una pregunta concreta: al subir `precio.maximo` de
300.000 a 5.000.000 entran al catálogo productos mucho más caros, las
distribuciones por categoría se corren, ¿hay que recalibrar los thresholds?

**No, y no por criterio sino por construcción.** Cada condición de
`assign_badges` está expresada en una unidad que se mueve CON la distribución:

| Condición                        | Unidad                          |
|----------------------------------|---------------------------------|
| `comp <= 35/20/65/40`, `>= 80`   | score compuesto 0-100, relativo |
| `mz <= -1.5` / `mz >= 1.5`       | z-score modificado (MAD)        |
| `cheap` / `exp`                  | cercos de Tukey sobre el IQR    |
| `desc_pct < 12`                  | porcentaje                      |
| `ratio > 1.0`                    | ratio                           |

No hay una sola constante denominada en pesos en el camino de scoring. La
única del archivo es el piso de `bin_size` en `_calc_mode`, y `mode` se
reporta en `to_dict()` sin alimentar ningún score ni badge.

Estos tests fijan esa propiedad para que no se pierda en silencio: el día que
alguien meta un umbral en pesos —"below_market si está $50.000 bajo la
mediana"— el pipeline deja de ser portable entre categorías de escalas
distintas (una remera de $30.000 y un standing desk de $2.400.000 conviven en
la misma base) y ESTE archivo se pone rojo antes de que se note en los badges.
"""
import ml_pipeline
import pytest

# Precios REALES de INPRO, medidos contra el sitio en vivo el 2026-08-20.
# Se usan estos y no números redondos a propósito: son la distribución que
# motivó la pregunta, con su asimetría real (una cola cara larga).
SILLAS = [260990, 399900, 489990, 589990, 599000, 799000, 799000,
          999990, 999990, 1259990, 2999000]
ESCRITORIOS = [399000, 449100, 499000, 599000, 799000, 999000,
               1099990, 1199990, 1999990, 2099990, 2399000]

FACTORES = [0.01, 10, 100, 1000]


def _evaluar(precios, x):
    """comp, mz y badges de `x` dentro de `precios`, con el código real."""
    st = ml_pipeline.PriceStats(precios)
    comp = st.composite_score(x)
    mz = st.z_score_modified(x)
    badges = ml_pipeline.assign_badges(
        hist=False, comp=comp, mz=mz,
        cheap=st.is_cheap_outlier(x), alta=False, trend=None,
        exp=st.is_expensive_outlier(x), ratio=0.0, desc_pct=0.0,
        es_oferta_real=False)
    return comp, badges, st.price_segment(x)


@pytest.mark.parametrize("factor", FACTORES)
@pytest.mark.parametrize("precios", [SILLAS, ESCRITORIOS], ids=["sillas", "escritorios"])
def test_badges_no_cambian_al_escalar_la_distribucion(precios, factor):
    """Escalar toda la distribución no puede mover un badge.

    Es la prueba que decide si hay algo que recalibrar: si algún threshold
    estuviera en pesos, multiplicar por 10 lo cruzaría.
    """
    escalada = [v * factor for v in precios]
    for p in precios:
        assert _evaluar(precios, p) == _evaluar(escalada, p * factor), (
            "el producto de $%s cambia de badge al escalar la distribucion x%s "
            "-> hay un threshold denominado en pesos" % (p, factor))


def test_el_segmento_de_precio_tampoco_tiene_escala():
    """`price_segment` es cuartiles + cerco de Tukey, sin cortes absolutos."""
    for factor in FACTORES:
        st1 = ml_pipeline.PriceStats(SILLAS)
        st2 = ml_pipeline.PriceStats([v * factor for v in SILLAS])
        for p in SILLAS:
            assert st1.price_segment(p) == st2.price_segment(p * factor)


def test_la_moda_es_la_unica_excepcion_y_esta_fuera_del_scoring():
    """`_calc_mode` tiene un piso de $5000: es el ÚNICO valor con escala.

    Se fija acá como excepción documentada, no como defecto. `mode` se reporta
    en `to_dict()` y no lo lee ningún score ni ningún badge, así que su falta
    de invariancia no llega a ninguna decisión. Si algún día entra al scoring,
    este es el puntero a revisar.
    """
    st = ml_pipeline.PriceStats(SILLAS)
    achicada = ml_pipeline.PriceStats([v * 0.001 for v in SILLAS])

    # Achicada x1000, el piso de bin_size domina y la moda deja de ser
    # proporcional. Que esto sea CIERTO es lo que prueba que el piso existe.
    assert achicada.mode * 1000 != pytest.approx(st.mode, rel=1e-9), (
        "si la moda escalara, el piso de bin_size en _calc_mode ya no estaria")

    # Y sin embargo el badge del mismo producto no se mueve — que es la razón
    # por la que la excepción es tolerable.
    for p in SILLAS:
        assert _evaluar(SILLAS, p) == _evaluar([v * 0.001 for v in SILLAS], p * 0.001)


def test_los_extremos_reales_reciben_el_badge_correcto():
    """Sanity de dominio sobre los datos reales, no sólo invariancia.

    El stool más barato es outlier inferior y el LiberNovo es outlier superior
    DENTRO de su categoría. Que el catálogo entero tenga precios altos no hace
    que todo sea 'caro': el scoring es siempre relativo a la categoría.
    """
    _, badges_barato, seg_barato = _evaluar(SILLAS, 260990)
    _, badges_caro, seg_caro = _evaluar(SILLAS, 2999000)

    assert "below_market" in badges_barato
    assert "above_market" not in badges_barato
    assert seg_barato == "budget"

    assert "above_market" in badges_caro
    assert "below_market" not in badges_caro
    assert seg_caro == "luxury"


def test_una_categoria_por_debajo_del_minimo_de_muestra_se_abstiene():
    """Con menos de MIN_SAMPLE productos no se inventan outliers.

    Es lo que pasaba con `Silla` y `Escritorio` bajo la banda de 300.000: 1 y 0
    productos sobrevivían, así que la categoría no existía. Un catálogo chico
    tiene que abstenerse, no clasificar con ruido (`CODE-5`).
    """
    minusculo = [p for p in SILLAS if p <= 300_000]
    assert len(minusculo) < ml_pipeline.PriceStats.MIN_SAMPLE

    st = ml_pipeline.PriceStats(minusculo)
    assert st.is_cheap_outlier(minusculo[0]) is False
    assert st.is_expensive_outlier(minusculo[0]) is False
    assert st.price_segment(minusculo[0]) == "standard"
