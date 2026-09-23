"""Etiqueta la muestra A CIEGAS con el modelo local, restringido al canon.

D3 del documento de feature: el modelo NUNCA ve `productos.categoria`. Si la
viera, anclaría en ella y el acuerdo medido no significaría nada. La etiqueta
actual se lee acá sólo para escribirla en la salida y compararla después.
"""
import json, os, sys, time, urllib.request

S = os.environ["S"]
MODEL = "qwen3:14b"
canon = [l.strip() for l in open(f"{S}/canon-db.txt") if l.strip()]
canon_set = set(canon)
canon_lower = {c.lower(): c for c in canon}

CRITERIO = ('La categoría es la CLASIFICADA del producto, y el nombre puede no contener esa '
            'palabra: una "Remera sin mangas Dry Fit" es Musculosa.')

def ask(nombre, estricto=False):
    p = ("Categorías válidas (usá EXACTAMENTE una de estas, nada más): " + ", ".join(canon)
         + "\n\n" + CRITERIO + '\n\nProducto: "' + nombre + '"\n\n'
         + ("REPETÍ SOLO UNA de las categorías de la lista, copiada carácter por carácter. "
            "Sin explicación, sin comillas." if estricto
            else "Respondé SOLO con el nombre exacto de la categoría, sin explicación."))
    body = {"model": MODEL, "prompt": p, "stream": False, "think": False,
            "options": {"temperature": 0, "num_predict": 24}}
    req = urllib.request.Request("http://localhost:11434/api/generate",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=300).read()).get("response", "").strip()

def normalizar(raw):
    """Acepta sólo el canon. Devuelve (label|None, raw)."""
    t = raw.strip().strip('"').strip("'").strip(".")
    if t in canon_set: return t, raw
    if t.lower() in canon_lower: return canon_lower[t.lower()], raw
    return None, raw

filas = [l.rstrip("\n").split("\t") for l in open(f"{S}/muestra.tsv") if l.strip()]
out = open(f"{S}/eval-set.jsonl", "w")
t0, invalidos, acuerdos = time.time(), 0, 0

for i, (url, nombre, rubro, cat_actual) in enumerate(filas, 1):
    label, raw = normalizar(ask(nombre))
    reintentado = False
    if label is None:
        label, raw2 = normalizar(ask(nombre, estricto=True))
        reintentado = True
        if label is None:
            invalidos += 1
            raw = f"{raw} || {raw2}"
    if label == cat_actual: acuerdos += 1
    out.write(json.dumps({
        "url": url, "nombre": nombre, "rubro": rubro,
        "categoria_actual": cat_actual,      # la del clasificador por keywords
        "categoria_modelo": label,           # None = el modelo no produjo nada del canon
        "acuerdo": label == cat_actual,
        "reintentado": reintentado,
        "crudo": None if label and not reintentado else raw,
    }, ensure_ascii=False) + "\n")
    if i % 50 == 0:
        print(f"  {i}/{len(filas)}  {round(time.time()-t0)}s  acuerdo={acuerdos}/{i}", flush=True)

out.close()
print(f"LISTO {len(filas)} en {round(time.time()-t0)}s | fuera del canon tras reintento: {invalidos} "
      f"| acuerdo crudo: {acuerdos}/{len(filas)} = {100*acuerdos/len(filas):.1f}%")
