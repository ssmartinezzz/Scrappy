"""Regenera scraper/src/main/resources/pcs/benchmarks/cpu_gaming.csv desde la jerarquía de CPUs de Tom's Hardware.

    python3 -I scripts/benchmarks/gaming_cpu.py

Puntaje 1080p gaming con la tabla actual como escala (Ryzen 7 9850X3D = 1000). Las tablas viejas se
encadenan por la mediana del cociente sobre los chips que comparten con lo ya armado, y la primera
que mide un chip gana. Lo que ninguna mide sale de las reglas de abajo o se abstiene. Solo se
publica el índice derivado, nunca las tablas. Ver docs/PC_BUILDER.md → Fase 12.
"""
import datetime
import re
import statistics
import sys
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from blender_cpu import clave_cpu, escribir_csv  # noqa: E402

PAGINAS = [
    "https://www.tomshardware.com/reviews/cpu-hierarchy,4312.html",
    "https://web.archive.org/web/20250602043810id_/https://www.tomshardware.com/reviews/cpu-hierarchy,4312.html",
    "https://www.tomshardware.com/reviews/cpu-hierarchy,4312-2.html",
]
DESTINO = Path(__file__).resolve().parents[2] / "scraper/src/main/resources/pcs/benchmarks"

TITULO_GAMING = re.compile(r"^(Legacy: [\d -]+ )?Gaming CPU Benchmarks Ranking")
PORCENTAJE = re.compile(r"^([\d.]+)%")

# Tom's titula "Ryzen 7 7900X3D" a un Ryzen 9.
CORRECCIONES = {"ryzen-7-7900x3d": "ryzen-9-7900x3d"}

# Mismo silicio, o la misma generación sin iGPU. Los GE/T toman el G aunque estén limitados por TDP:
# es una sobreestimación conocida.
GEMELOS = {
    "ryzen-9-5900xt": "ryzen-9-5950x",
    "ryzen-7-5800xt": "ryzen-7-5800x",
    "ryzen-5-5600xt": "ryzen-5-5600x",
    "ryzen-7-5700": "ryzen-7-5700g",
    "ryzen-5-5500": "ryzen-5-5600g",
    "ryzen-5-5600gt": "ryzen-5-5600g",
    "ryzen-5-5600ge": "ryzen-5-5600g",
    "ryzen-5-5600t": "ryzen-5-5600g",
    "ryzen-5-pro-5650g": "ryzen-5-5600g",
    "ryzen-7-5700ge": "ryzen-7-5700g",
    "ryzen-7-5755ge": "ryzen-7-5700g",
    "ryzen-9-9950x3d2": "ryzen-9-9950x3d",
    "core-i3-10105": "core-i3-10100",
}

# Phoenix: el Zen 4 de escritorio de igual cantidad de núcleos, por lo que la caché de APU le cuesta a
# Cezanne frente a Vermeer.
FENIX = {
    "ryzen-7-8700g": "ryzen-7-7700x",
    "ryzen-7-8700f": "ryzen-7-7700x",
    "ryzen-5-8600g": "ryzen-5-7600x",
    "ryzen-5-8500g": "ryzen-5-7600x",
    "ryzen-5-8400f": "ryzen-5-7600x",
}
PARES_APU = [("ryzen-7-5700g", "ryzen-7-5800x"), ("ryzen-5-5600g", "ryzen-5-5600x")]

RYZEN_X = re.compile(r"^ryzen-\d-\d{4}x$")
CORE_K = re.compile(r"^core-.*\d+k$")


class _Tablas(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tablas, self._tabla, self._fila, self._celda = [], None, None, None
        self._titulo, self._en_titulo = "", False

    def handle_starttag(self, tag, attrs):
        if tag in ("h2", "h3", "h4"):
            self._en_titulo, self._titulo = True, ""
        elif tag == "table":
            self._tabla = {"titulo": " ".join(self._titulo.split()), "filas": []}
        elif tag == "tr" and self._tabla is not None:
            self._fila = []
        elif tag in ("td", "th") and self._fila is not None:
            self._celda = ""

    def handle_endtag(self, tag):
        if tag in ("h2", "h3", "h4"):
            self._en_titulo = False
        elif tag == "table" and self._tabla is not None:
            self.tablas.append(self._tabla)
            self._tabla = None
        elif tag == "tr" and self._fila is not None:
            self._tabla["filas"].append(self._fila)
            self._fila = None
        elif tag in ("td", "th") and self._celda is not None:
            self._fila.append(" ".join(self._celda.split()))
            self._celda = None

    def handle_data(self, data):
        if self._celda is not None:
            self._celda += data
        if self._en_titulo:
            self._titulo += data


def _nombres(celda):
    nombre = re.sub(r"^\$\d+\s*-\s*", "", celda)
    nombre = re.sub(r"\s*\(\$.*$|\s+DDR4 / DDR5$", "", nombre).replace("*", "").strip()
    base, *alternativas = re.split(r"\s*/\s*", nombre)
    nombres = [base]
    for alt in alternativas:
        # "Core i5-9400 / i5-9400F" reemplaza el modelo; "Core i9-9900K / F" le agrega el sufijo.
        nombres.append(base.rsplit(" ", 1)[0] + " " + alt if re.search(r"\d", alt) else base + alt)
    return nombres


def tablas_gaming(html):
    parser = _Tablas()
    parser.feed(html)
    resultado = []
    for tabla in parser.tablas:
        if not TITULO_GAMING.match(tabla["titulo"]) or not tabla["filas"]:
            continue
        encabezado = tabla["filas"][0]
        columna = next(i for i, c in enumerate(encabezado) if "1080p Gaming Score" in c)
        puntajes = {}
        for fila in tabla["filas"][1:]:
            valor = PORCENTAJE.match(fila[columna]) if len(fila) > columna else None
            if not valor:
                continue
            for nombre in _nombres(fila[0]):
                clave = clave_cpu(nombre)
                if clave:
                    puntajes[CORRECCIONES.get(clave, clave)] = float(valor[1])
        resultado.append(puntajes)
    return resultado


def _cociente(medidos, pares):
    cocientes = [medidos[a] / medidos[b] for a, b in pares if a in medidos and b in medidos]
    return statistics.median(cocientes) if cocientes else None


def derivar(medidos):
    puntajes = dict(medidos)
    for clave, origen in GEMELOS.items():
        if origen in medidos:
            puntajes.setdefault(clave, medidos[origen])

    apu = _cociente(medidos, PARES_APU)
    for clave, origen in FENIX.items():
        if apu and origen in medidos:
            puntajes.setdefault(clave, medidos[origen] * apu)

    no_x = _cociente(medidos, [(c[:-1], c) for c in medidos if RYZEN_X.match(c)])
    no_k = _cociente(medidos, [(c[:-1], c) for c in medidos if CORE_K.match(c)])
    for clave in medidos:
        if no_x and RYZEN_X.match(clave):
            puntajes.setdefault(clave[:-1], medidos[clave] * no_x)
        if no_k and CORE_K.match(clave):
            puntajes.setdefault(clave[:-1], medidos[clave] * no_k)

    for clave in [c for c in puntajes if c.startswith("core-") and not c.endswith("f")]:
        puntajes.setdefault(clave + "f", puntajes[clave])
    return puntajes


def tabla(paginas):
    medidos = {}
    for html in paginas:
        for puntajes in tablas_gaming(html):
            if not medidos:
                medidos = {clave: valor * 10 for clave, valor in puntajes.items()}
                continue
            comunes = [medidos[c] / v for c, v in puntajes.items() if c in medidos]
            if not comunes:
                raise ValueError("una tabla sin ningún chip en común no se puede encadenar")
            factor = statistics.median(comunes)
            for clave, valor in puntajes.items():
                medidos.setdefault(clave, valor * factor)
    return [(clave, round(valor), 0) for clave, valor in sorted(derivar(medidos).items())]


def actualizar_fuentes(destino, fecha, revision):
    gaming = {
        "cpu_gaming.fuente": PAGINAS[0],
        "cpu_gaming.fuentes_encadenadas": " ".join(PAGINAS[1:]),
        "cpu_gaming.fecha": fecha,
        "cpu_gaming.revision_fuente": revision,
        "cpu_gaming.licencia": "datos de rendimiento citados de Tom's Hardware; solo el indice derivado, sin reproducir las tablas",
        "cpu_gaming.escala": "1080p gaming score, Ryzen 7 9850X3D = 1000",
        "cpu_gaming.metodo": "tabla actual; snapshot 2025-06 y las dos tablas legado encadenadas por la mediana del cociente "
                             "sobre los chips en comun; gemelos y cocientes medidos en scripts/benchmarks/gaming_cpu.py",
        "cpu_gaming.abstiene": "7700X3D (no existe), Ryzen 5 4500, Ryzen 3 4100, Ryzen 5 3500X (sin dato), "
                               "Core i3-12100/13100/14100, Core Ultra 5 235, i5-10400T",
    }
    previas = destino.read_text(encoding="utf-8").splitlines() if destino.exists() else []
    otras = [l for l in previas if not l.startswith("cpu_gaming.")]
    destino.write_text("\n".join(otras + [f"{k}={v}" for k, v in gaming.items()]) + "\n", encoding="utf-8")


def _descargar(url):
    pedido = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 scrappy-benchmarks/1.0"})
    with urllib.request.urlopen(pedido, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def main():
    paginas = [_descargar(url) for url in PAGINAS]
    revision = re.search(r'"dateModified":\s*"(\d{4}-\d{2}-\d{2})', paginas[0])
    filas = tabla(paginas)
    escribir_csv(DESTINO / "cpu_gaming.csv", filas)
    actualizar_fuentes(DESTINO / "fuentes.properties", datetime.date.today().isoformat(),
                       revision[1] if revision else "desconocida")
    print(f"cpu_gaming.csv: {len(filas)} modelos")


if __name__ == "__main__":
    main()
