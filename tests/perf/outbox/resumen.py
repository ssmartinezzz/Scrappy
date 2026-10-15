"""Resume abba.out: mediana, p95 (nearest-rank) y delta de la mediana contra la primera variante."""
import math
import statistics
import sys

salida, archivos = sys.argv[1], sys.argv[2:]
tiempos, encoladas = {}, {}
for linea in open(salida):
    i, ms, enc = linea.split()
    tiempos.setdefault(int(i), []).append(float(ms))
    encoladas.setdefault(int(i), set()).add(enc)

base = statistics.median(tiempos[0])
print(f"{'variante':40} {'n':>3} {'mediana ms':>11} {'p95 ms':>9} {'delta':>8} {'encoladas':>10}")
for i, archivo in enumerate(archivos):
    x = sorted(tiempos[i])
    mediana = statistics.median(x)
    p95 = x[math.ceil(0.95 * len(x)) - 1]
    delta = "base" if i == 0 else f"{(mediana - base) / base * 100:+.1f}%"
    print(f"{archivo[-40:]:40} {len(x):>3} {mediana:>11.1f} {p95:>9.1f} {delta:>8} {','.join(sorted(encoladas[i])):>10}")
