import csv
import json
import sys
from pathlib import Path

import pytest

RAIZ = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(RAIZ / "scripts" / "benchmarks"))

import blender_cpu  # noqa: E402

CLAVES = RAIZ / "scraper/src/test/resources/pcs/benchmarks/claves.csv"
FIXTURE = Path(__file__).parent / "fixtures" / "blender_cpu_4.5.0.json"


def filas_cpu_del_contrato():
    with CLAVES.open(encoding="utf-8") as f:
        return [(r["texto"], r["clave"]) for r in csv.DictReader(f) if r["categoria"] == "CPU"]


@pytest.mark.parametrize("texto,clave", filas_cpu_del_contrato())
def test_canonicaliza_igual_que_java(texto, clave):
    assert blender_cpu.clave_cpu(texto) == clave


def test_tabla_desde_la_respuesta_de_blender():
    filas = blender_cpu.tabla(json.loads(FIXTURE.read_text(encoding="utf-8"))["rows"])

    assert filas == [
        ("core-i5-13600k", 3023, 35),
        ("core-i5-14400f", 2024, 45),
        ("core-i5-14600k", 3015, 102),
        ("core-ultra-7-265k", 4780, 279),
        ("core-ultra-7-270k", 6111, 20),
        ("ryzen-5-5600g", 1457, 164),
        ("ryzen-7-9800x3d", 3339, 1084),
        ("ryzen-9-9950x3d", 6635, 1289),
        ("ryzen-9-9950x3d2", 6870, 3),
    ]


@pytest.mark.parametrize("nombre", [
    "AMD Ryzen 7 7840HS w/ Radeon 780M Graphics",
    "AMD Ryzen 5 3500U with Radeon Vega Mobile Gfx",
    "Intel Core i7-1165G7 @ 2.80GHz",
    "Intel Core Ultra 7 255HX",
    "Intel Core Ultra 7 258V",
    "13th Gen Intel Core i5-13450HX",
    "Intel Core i7-12700H Laptop",
    "Intel Core i5-1038NG7 CPU @ 2.00GHz",
    "Intel Core i7-7700HQ CPU @ 2.80GHz",
    "Intel Core i7-3720QM CPU @ 2.60GHz",
    "Intel Core i7-4810MQ CPU @ 2.80GHz",
    "AMD Ryzen 9 7945HX3D with Radeon Graphics",
])
def test_descarta_moviles(nombre):
    assert blender_cpu.tabla([[nombre, 100.0, 50]]) == []


def test_descarta_varios_sockets_y_pocas_muestras():
    assert blender_cpu.tabla([
        ["8X Intel Core Ultra 7 265K", 237.8, 30],
        ["13th Gen Intel Core i5-13600", 228.6, 2],
    ]) == []


def test_ante_claves_repetidas_gana_la_de_mas_muestras():
    filas = blender_cpu.tabla([
        ["Intel Core i5-14600K", 301.46, 102],
        ["14th Gen Intel Core i5-14600K", 290.0, 400],
        ["Intel(R) Core(TM) i5-14600K", 350.0, 3],
    ])

    assert filas == [("core-i5-14600k", 2900, 400)]


def test_escribe_el_csv_que_lee_java(tmp_path):
    destino = tmp_path / "cpu.csv"

    blender_cpu.escribir_csv(destino, [("core-i5-14400f", 2024, 45), ("ryzen-5-5600g", 1457, 164)])

    assert destino.read_text(encoding="utf-8") == (
        "clave,puntaje,muestras\n"
        "core-i5-14400f,2024,45\n"
        "ryzen-5-5600g,1457,164\n"
    )


def test_actualiza_solo_la_procedencia_de_cpu(tmp_path):
    fuentes = tmp_path / "fuentes.properties"
    fuentes.write_text("cpu.blender_version=4.4.0\ngpu.fuente=tabla curada\n", encoding="utf-8")

    blender_cpu.actualizar_fuentes(fuentes, "4.5.0", "2026-10-13")

    lineas = fuentes.read_text(encoding="utf-8").splitlines()
    assert "gpu.fuente=tabla curada" in lineas
    assert "cpu.blender_version=4.5.0" in lineas
    assert "cpu.fecha=2026-10-13" in lineas
    assert "cpu.blender_version=4.4.0" not in lineas
    assert any(l.startswith("cpu.fuente=https://opendata.blender.org/") for l in lineas)


def test_elige_la_version_con_mas_muestras():
    assert blender_cpu.version_con_mas_muestras([
        ["4.4.0", 249.5, 12929],
        ["4.5.0", 261.7, 21830],
        ["5.1.1", 291.2, 5441],
    ]) == "4.5.0"
