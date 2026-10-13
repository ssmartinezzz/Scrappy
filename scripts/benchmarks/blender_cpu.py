"""Regenera scraper/src/main/resources/pcs/benchmarks/cpu.csv desde Blender Open Data.

    python3 -I scripts/benchmarks/blender_cpu.py [--version 4.5.0]

Sin --version usa la versión de Blender con más corridas: las medianas de versiones
distintas no son comparables entre sí. La clave sigue el contrato de
pcs/benchmarks/claves.csv, que también lee ClaveDeModelo del lado Java.
"""
import argparse
import datetime
import json
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://opendata.blender.org/benchmarks/query/"
LICENCIA = "license-free (Open Data, https://opendata.blender.org/about/)"
DESTINO = Path(__file__).resolve().parents[2] / "scraper/src/main/resources/pcs/benchmarks"
MUESTRAS_MINIMAS = 3

RYZEN = re.compile(r" ryzen ([3579]) ((?:pro )?)(\d{4}[a-z0-9]*) ")
CORE_I = re.compile(r" i([3579]) (\d{4,5}[a-z0-9]*) ")
CORE_ULTRA = re.compile(r" ultra ([3579]) (\d{3}[a-z]*) ")

VARIOS_SOCKETS = re.compile(r"^\d+x ")
SUFIJO_MOVIL = re.compile(r"\d(u|h|hs|hx|hk|hq|hx3d|p|v|y|m|qm|mq|n?g\d)$")


def _tokens(texto):
    sin_acentos = unicodedata.normalize("NFKD", texto.lower()).encode("ascii", "ignore").decode()
    return " " + " ".join(re.sub(r"[^a-z0-9]+", " ", sin_acentos).split()) + " "


def clave_cpu(texto):
    t = _tokens(texto)
    m = RYZEN.search(t)
    if m:
        return f"ryzen-{m[1]}-{m[2].replace(' ', '-')}{m[3]}"
    m = CORE_ULTRA.search(t)
    if m:
        return f"core-ultra-{m[1]}-{m[2]}"
    m = CORE_I.search(t)
    if m:
        return f"core-i{m[1]}-{m[2]}"
    return ""


def _es_de_escritorio(nombre, clave):
    t = _tokens(nombre)
    if VARIOS_SOCKETS.match(t.lstrip()) or " laptop " in t or " mobile " in t:
        return False
    return not SUFIJO_MOVIL.search(clave)


def tabla(filas):
    mejores = {}
    for nombre, mediana, muestras in filas:
        clave = clave_cpu(nombre)
        if not clave or muestras < MUESTRAS_MINIMAS or not _es_de_escritorio(nombre, clave):
            continue
        if clave not in mejores or muestras > mejores[clave][1]:
            mejores[clave] = (round(mediana * 10), muestras)
    return [(clave, puntaje, muestras) for clave, (puntaje, muestras) in sorted(mejores.items())]


def escribir_csv(destino, filas):
    lineas = ["clave,puntaje,muestras"] + [f"{c},{p},{m}" for c, p, m in filas]
    destino.write_text("\n".join(lineas) + "\n", encoding="utf-8")


def _url(**params):
    return API + "?" + urllib.parse.urlencode({"compute_type": "CPU", "response_type": "datatables", **params})


def actualizar_fuentes(destino, version, fecha):
    cpu = {
        "cpu.fuente": _url(group_by="device_name", blender_version=version),
        "cpu.blender_version": version,
        "cpu.fecha": fecha,
        "cpu.licencia": LICENCIA,
        "cpu.escala": "mediana de Blender CPU x10",
    }
    previas = destino.read_text(encoding="utf-8").splitlines() if destino.exists() else []
    otras = [l for l in previas if not l.startswith("cpu.")]
    destino.write_text("\n".join(otras + [f"{k}={v}" for k, v in cpu.items()]) + "\n", encoding="utf-8")


def version_con_mas_muestras(filas):
    return max(filas, key=lambda f: f[2])[0]


def _consultar(url):
    # El User-Agent por defecto de urllib recibe 403.
    pedido = urllib.request.Request(url, headers={"User-Agent": "scrappy-benchmarks/1.0"})
    with urllib.request.urlopen(pedido, timeout=60) as r:
        return json.load(r)["rows"]


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version")
    args = parser.parse_args(argv)

    version = args.version or version_con_mas_muestras(_consultar(_url(group_by="blender_version")))
    filas = tabla(_consultar(_url(group_by="device_name", blender_version=version)))
    escribir_csv(DESTINO / "cpu.csv", filas)
    actualizar_fuentes(DESTINO / "fuentes.properties", version, datetime.date.today().isoformat())
    print(f"cpu.csv: {len(filas)} modelos, Blender {version}")


if __name__ == "__main__":
    main(sys.argv[1:])
