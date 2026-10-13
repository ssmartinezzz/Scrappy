import sys
from pathlib import Path

import pytest

RAIZ = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(RAIZ / "scripts" / "benchmarks"))

import gaming_cpu  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"


def _html(nombre):
    return (FIXTURES / nombre).read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def puntajes():
    paginas = [_html("toms_cpu_actual.html"), _html("toms_cpu_2025.html"), _html("toms_cpu_legado.html")]
    return {clave: puntaje for clave, puntaje, _ in gaming_cpu.tabla(paginas)}


def test_lee_solo_las_tablas_de_gaming_con_dgpu():
    actual = gaming_cpu.tablas_gaming(_html("toms_cpu_actual.html"))

    assert len(actual) == 1
    assert actual[0]["ryzen-7-9850x3d"] == 100.0
    assert "ryzen-7-8700g" not in actual[0]


def test_la_pagina_legado_trae_dos_tablas_en_orden():
    legado = gaming_cpu.tablas_gaming(_html("toms_cpu_legado.html"))

    assert [len(t) for t in legado] == [7, 12]


def test_normaliza_las_filas_del_legado():
    tabla = gaming_cpu.tablas_gaming(_html("toms_cpu_legado.html"))[1]

    assert tabla["core-i9-12900k"] == 100.0  # DDR4 / DDR5: la primera columna
    assert tabla["ryzen-7-5700g"] == 79.2  # sin el asterisco
    assert tabla["core-i5-9400"] == tabla["core-i5-9400f"] == 64.85
    assert "core-i7-10700" not in tabla  # "~" no es un dato


def test_el_7900x3d_es_ryzen_9_aunque_la_fuente_diga_ryzen_7(puntajes):
    assert puntajes["ryzen-9-7900x3d"] == 771
    assert "ryzen-7-7900x3d" not in puntajes


def test_la_tabla_actual_es_la_escala(puntajes):
    assert puntajes["ryzen-7-9850x3d"] == 1000
    assert puntajes["ryzen-7-7700x"] == 706  # la 2025 lo mide distinto; gana la actual


def test_encadena_por_la_mediana_de_los_chips_compartidos(puntajes):
    # 2025: (970/100, 706/70) -> 9.8929
    assert puntajes["ryzen-7-9700x"] == 791
    # legado 2022-23: mediana de (768/100, 706/90, 673/85) = 7.8444
    assert puntajes["ryzen-9-7900x"] == 706
    # legado 2020-22: solo 12700K compartido, 658/97.71
    assert puntajes["ryzen-9-5950x"] == 606


def test_una_fila_sin_clave_no_entra():
    tabla = gaming_cpu.tablas_gaming(_html("toms_cpu_2025.html"))[0]

    assert set(tabla) == {"ryzen-7-9800x3d", "ryzen-7-9700x", "ryzen-7-7700x"}


@pytest.mark.parametrize("clave,esperado", [
    ("core-i7-12700kf", 658),  # F/KF: el mismo chip sin iGPU
    ("core-i5-9400f", 437),
    ("ryzen-9-5900xt", 606),  # XT: el X de igual cantidad de núcleos (16)
    ("ryzen-5-5500", 458),  # Cezanne sin iGPU: su G
    ("ryzen-7-5700", 533),
    ("ryzen-5-5600gt", 458),
    ("ryzen-7-5755ge", 533),
    ("core-i3-10105", 417),
    ("core-i3-10105f", 417),
])
def test_gemelos(puntajes, clave, esperado):
    assert puntajes[clave] == esperado


@pytest.mark.parametrize("clave,esperado", [
    ("ryzen-7-9700", 768),  # X x mediana(7600/7600X, 7700/7700X, 7900/7900X) = 0.97
    ("core-i7-12700", 625),  # K x 8700/8700K = 0.95
    ("core-i7-12700f", 625),
    ("core-ultra-7-270", 736),
    ("ryzen-7-8700g", 618),  # 7700X x mediana(5700G/5800X, 5600G/5600X) = 0.875
    ("ryzen-7-8700f", 618),
    ("ryzen-5-8600g", 589),
    ("ryzen-5-8500g", 589),
    ("ryzen-5-8400f", 589),
])
def test_derivados_por_cociente_medido(puntajes, clave, esperado):
    assert puntajes[clave] == esperado


@pytest.mark.parametrize("clave", [
    "ryzen-7-7700x3d",  # no existe ese SKU
    "ryzen-5-4500",
    "ryzen-3-4100",
    "core-i3-12100",
    "core-i3-14100f",
    "core-i5-10400t",
    "ryzen-9-9950x3d2",  # su gemelo no está en estas páginas
    "core-i7-10700",
])
def test_se_abstiene(puntajes, clave):
    assert clave not in puntajes


def test_salida_ordenada_sin_muestras():
    filas = gaming_cpu.tabla([_html("toms_cpu_actual.html")])

    assert [c for c, _, _ in filas] == sorted(c for c, _, _ in filas)
    assert {m for _, _, m in filas} == {0}


def test_actualizar_fuentes_conserva_las_otras_claves(tmp_path):
    destino = tmp_path / "fuentes.properties"
    destino.write_text("cpu.fuente=x\ncpu_gaming.fecha=vieja\ngpu.escala=y\n", encoding="utf-8")

    gaming_cpu.actualizar_fuentes(destino, "2026-10-13", "2026-10-05")

    lineas = destino.read_text(encoding="utf-8").splitlines()
    assert lineas[:2] == ["cpu.fuente=x", "gpu.escala=y"]
    assert "cpu_gaming.fecha=2026-10-13" in lineas
    assert "cpu_gaming.revision_fuente=2026-10-05" in lineas
    assert "cpu_gaming.fecha=vieja" not in lineas
