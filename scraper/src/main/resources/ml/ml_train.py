#!/usr/bin/env python3
"""
Fashion Scraper ML Trainer
--------------------------
Entrena modelos de clasificación usando los productos del DB como dataset.

Fase 1 — Text classifier (TF-IDF + LogisticRegression): ~30 seg

Fase 2 — Image classifier: REMOVIDA (PR4, spec "ml-training-removal").
`--images` es ahora un no-op que avisa y sigue sin entrenar nada — la
clasificación de imagen es zero-shot vía ml_embeddings.py
(Marqo-FashionSigLIP), no requiere entrenamiento propio. Ver
ml_pipeline.py's stage 1b.

Uso:
  python ml_train.py scraper.db [--images] [--epochs N]

Output JSON:
  { "status": "ok", "text": {...metrics}, "image": {"status": "no-op", ...} }
"""

import sys, os, json, pickle, sqlite3, re
# UTF-8 se fuerza via PYTHONIOENCODING=utf-8 en el ProcessBuilder de Java
from pathlib import Path
from collections import Counter
from datetime import datetime
import numpy as np

# ─── Helpers ──────────────────────────────────────────────────────────────────

def log(msg):
    print(f"[TRAIN] {msg}", file=sys.stderr, flush=True)

def progress(phase, pct, msg=""):
    print(json.dumps({"phase": phase, "pct": pct, "msg": msg}), flush=True)

# ─── Normalización de texto ───────────────────────────────────────────────────

ACCENT = str.maketrans("áàäéèëíìïóòöúùüñÁÉÍÓÚÑ","aaaeeeiiiooouuunAEIOUN")

def normalize_text(s):
    if not s: return ""
    s = str(s).lower().translate(ACCENT).strip()
    # Quitar talle/color/género para que el modelo aprenda el producto base
    s = re.sub(r'\b(talle|talla|size|t\.?)\s*[\w.,/]+', '', s)
    s = re.sub(r'\b(xs|xxs|s\b|m\b|l\b|xl|xxl|xxxl)\b', '', s)
    s = re.sub(r'\b(negro|blanco|azul|rojo|verde|gris|beige|naranja|rosa|dorado)\b', '', s)
    s = re.sub(r'\b(hombre|mujer|masculino|femenino|unisex)\b', '', s)
    s = re.sub(r'\s+', ' ', s).strip()
    return s

# ─── Cargar datos del DB ──────────────────────────────────────────────────────

def load_dataset(db_path):
    if not Path(db_path).exists():
        log(f"DB no encontrada: {db_path}"); return []
    conn = sqlite3.connect(db_path)
    cur  = conn.execute("""
        SELECT nombre, categoria, rubro, marca
        FROM productos
        WHERE activo=1 AND nombre IS NOT NULL AND nombre != ''
          AND categoria IS NOT NULL AND categoria != ''
          AND categoria != 'Indumentaria'
          AND categoria != 'Ropa'
          AND categoria != 'PC & Tech'
    """)
    rows = cur.fetchall()
    conn.close()
    log(f"Registros cargados del DB: {len(rows)}")
    return rows

# ─── Merge de categorías pequeñas ────────────────────────────────────────────

CAT_PARENTS = {
    "zapatilla running":       "Zapatilla",
    "zapatilla urbana":        "Zapatilla",
    "zapatilla entrenamiento": "Zapatilla",
    "zapatilla skate":         "Zapatilla",
    "sneaker":                 "Zapatilla",
    "botas":                   "Calzado",
    "botines":                 "Calzado",
    "ojotas":                  "Calzado",
    "jogging":                 "Pantalón",
    "jean":                    "Pantalón",
    "baggy":                   "Pantalón",
    "calza":                   "Pantalón",
    "short":                   "Pantalón",
    "pollera":                 "Pantalón",
    "buzo":                    "Indumentaria Superior",
    "sweater":                 "Indumentaria Superior",
    "remera":                  "Indumentaria Superior",
    "campera":                 "Indumentaria Superior",
    "puffer":                  "Indumentaria Superior",
    "musculosa":               "Indumentaria Superior",
    "camisa":                  "Indumentaria Superior",
    "ropa training":           "Indumentaria Superior",
    "mochila":                 "Accesorios",
    "gorra":                   "Accesorios",
    "medias":                  "Accesorios",
    # Nuevas categorías
    "borcego":                 "Calzado",
    "sandalia":                "Ojotas",
    "mocasin":                 "Calzado",
    "zapato":                  "Calzado",
    "pantufla":                "Calzado",
    "chomba":                  "Remera",
    "casaca":                  "Remera",
    "chaleco":                 "Campera",
    "saco":                    "Campera",
    "traje":                   "Indumentaria Superior",
    "piloto":                  "Campera",
    "vestido":                 "Pollera",
    "enterito":                "Indumentaria Superior",
    "bermuda":                 "Short",
    "malla":                   "Indumentaria Superior",
    "calzoncillos":            "Indumentaria Superior",
    "corpino":                 "Musculosa",
    "rinonera":                "Mochila",
    "riñonera":                "Mochila",
    "billetera":               "Accesorios",
    "cinturon":                "Accesorios",
    "cinturón":                "Accesorios",
    "gorro":                   "Gorra",
    "bufanda":                 "Accesorios",
    "guantes":                 "Accesorios",
    "lentes":                  "Accesorios",
    "bolso":                   "Mochila",
    "tarjetero":               "Billetera",
    "notebook":                "PC",
    "monitor":                 "PC",
    "teclado":                 "PC",
    "mouse":                   "PC",
    "gpu":                     "PC",
    "ram":                     "PC",
    "cpu":                     "PC",
    "gabinete":                "PC",
    "auricular":               "Accesorios",
    "perfume":                 "Accesorios",
}

MIN_SAMPLES = 12  # menos de esto → merge al padre

# Reglas de corrección de etiquetas erróneas en datos de entrenamiento
LABEL_CORRECTIONS = [
    # Ojotas mal etiquetadas como zapatilla
    (["ojota","sandalia","sandal","chancleta","birkenstock","crocs","havaianas",
      "reef","ipanema","kenner","chinelo","rasteira","flip flop","slide","zueco","clog"],
     ["zapatilla","zapatilla urbana","zapatilla running","zapatilla entrenamiento",
      "remera","indumentaria","indumentaria superior"], "Ojotas"),
    # Botines mal etiquetados como zapatilla o remera
    (["botin","botín","chimpun","cleats","futbol","football","taco "],
     ["zapatilla","zapatilla urbana","remera","indumentaria"], "Botines"),
    # Remeras mal etiquetadas como zapatilla (el caso más común)
    (["remera","camiseta","tee ","t-shirt","musculosa","tank"],
     ["zapatilla","zapatilla urbana","zapatilla running","botines","ojotas"], "Remera"),
    # Camperas mal etiquetadas como zapatilla
    (["campera","jacket","puffer","anorak","parca","corta viento"],
     ["zapatilla","zapatilla urbana","remera"], "Campera"),
    # Baggy confundido con jean/jogging
    (["baggy","wide leg","pierna ancha","cargo"],
     ["jean","jogging","pantalón","pantalon"], "Baggy"),
    # Borcego / ankle boot (no es zapatilla)
    (["borcego","borcegos","dr martens","martens","timberland","lug sole","chunky boot"],
     ["zapatilla","zapatilla urbana","zapatilla running","remera","indumentaria"], "Borcego"),
    # Sandalia (no es ojota ni zapatilla)
    (["sandalia de tiras","sandalia con tiras","sandalia plana","sandalia taco","tiras cruzadas"],
     ["ojotas","zapatilla","zapatilla urbana"], "Sandalia"),
    # Chomba / polo shirt
    (["chomba","polo shirt","pique polo","rugby shirt","lacoste polo","fred perry polo"],
     ["remera","indumentaria","camisa"], "Chomba"),
    # Casaca de fútbol
    (["casaca","camiseta de futbol","camiseta futbol","jersey futbol","camiseta seleccion",
      "camiseta nba","jersey nba","kit futbol"],
     ["remera","indumentaria","campera"], "Casaca"),
    # Chaleco
    (["chaleco","gilet","chaleco de abrigo","chaleco polar","chaleco inflable"],
     ["campera","remera","indumentaria"], "Chaleco"),
    # Saco / Blazer
    (["blazer","saco de vestir","americana","sport coat","saco formal"],
     ["campera","remera","indumentaria"], "Saco"),
    # Vestido
    (["vestido","dress ","vestido largo","vestido corto","vestido de noche"],
     ["remera","pollera","indumentaria"], "Vestido"),
    # Bermuda (no es short genérico)
    (["bermuda","bermudas","short largo","short 3/4","walk short"],
     ["pantalon","short","jogging","indumentaria"], "Bermuda"),
    # Malla / bikini
    (["bikini","malla","traje de bano","swimsuit","one piece","tankini"],
     ["remera","musculosa","indumentaria"], "Malla"),
    # Riñonera (no es mochila)
    (["rinonera","riñonera","fanny pack","waist bag","hip bag","belt bag"],
     ["mochila","accesorio"], "Riñonera"),
    # Billetera
    (["billetera","wallet","tarjetero","card holder","portamonedas"],
     ["mochila","accesorio"], "Billetera"),
    # Cinturón
    (["cinturon","cinturón","belt ","leather belt","cinto "],
     ["accesorio","indumentaria"], "Cinturón"),
    # Gorro (beanie) vs Gorra (cap)
    (["gorro lana","gorro tejido","beanie","knit hat","pompom hat"],
     ["gorra","accesorio"], "Gorro"),
]

def corregir_etiqueta(nombre, cat):
    """Corrige etiquetas de entrenamiento incorrectas según reglas léxicas."""
    nombre_l = nombre.lower()
    for palabras, cats_incorrectas, cat_correcta in LABEL_CORRECTIONS:
        if cat in cats_incorrectas or cat.lower() in cats_incorrectas:
            if any(p in nombre_l for p in palabras):
                return cat_correcta
    return cat

def clean_labels(rows):
    """Normaliza labels, corrige errores y hace merge de categorías pequeñas."""
    data = []
    correcciones = 0
    for nombre, cat, rubro, marca in rows:
        cat_corregida = corregir_etiqueta(nombre, cat.strip())
        if cat_corregida != cat.strip():
            correcciones += 1
        cat_norm = cat_corregida.strip().lower().translate(ACCENT)
        data.append((nombre, cat_corregida.strip(), cat_norm, rubro or "indumentaria"))
    if correcciones:
        log(f"Correcciones de etiquetas en datos de entrenamiento: {correcciones}")

    cat_counts = Counter(cn for _, _, cn, _ in data)

    cleaned = []
    for nombre, cat_orig, cat_norm, rubro in data:
        n = normalize_text(nombre)
        if not n: continue
        # Decidir label final
        if cat_counts[cat_norm] >= MIN_SAMPLES:
            label = cat_orig
        else:
            parent_key = CAT_PARENTS.get(cat_norm)
            if parent_key:
                label = parent_key
            else:
                label = cat_orig  # usar igualmente aunque sea pequeño
        cleaned.append((n, label, rubro))

    log(f"Dataset limpio: {len(cleaned)} ejemplos, "
        f"{len(set(l for _,l,_ in cleaned))} categorías")
    return cleaned

# ─── Fase 1: Text Classifier (TF-IDF + LogReg) ───────────────────────────────

def train_text_model(cleaned, models_dir):
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import LabelEncoder
    from sklearn.model_selection import StratifiedShuffleSplit
    from sklearn.pipeline import Pipeline
    from sklearn.metrics import classification_report, accuracy_score

    log("=== Fase 1: Text Classifier ===")
    progress("text", 5, "Preparando features...")

    texts  = [t for t,_,_ in cleaned]
    labels = [l for _,l,_ in cleaned]

    # Encodear labels
    le = LabelEncoder()
    y  = le.fit_transform(labels)
    # Convertir np.str_ a str puro (numpy 2.0 devuelve np.str_)
    le.classes_ = np.array([str(cls) for cls in le.classes_])
    num_classes = len(le.classes_)
    log(f"Clases ({num_classes}): {list(le.classes_[:15])}...")

    # Filtrar clases con muy pocos ejemplos para el split estratificado
    class_counts = Counter(y)
    valid_mask   = np.array([class_counts[yi] >= 2 for yi in y])
    texts_v  = [t for t, ok in zip(texts, valid_mask) if ok]
    y_v      = y[valid_mask]
    progress("text", 15, f"{len(texts_v)} ejemplos después de filtrar")

    # Split 80/20 estratificado
    try:
        sss = StratifiedShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
        train_idx, test_idx = next(sss.split(texts_v, y_v))
    except ValueError:
        from sklearn.model_selection import train_test_split
        train_idx = range(int(len(texts_v)*0.8))
        test_idx  = range(int(len(texts_v)*0.8), len(texts_v))

    X_train = [texts_v[i] for i in train_idx]
    X_test  = [texts_v[i] for i in test_idx]
    y_train = y_v[list(train_idx)]
    y_test  = y_v[list(test_idx)]
    log(f"Train: {len(X_train)}, Test: {len(X_test)}")
    progress("text", 25, f"Split: {len(X_train)} train / {len(X_test)} test")

    # Pipeline TF-IDF (character n-grams) + LogisticRegression
    # Character n-grams capturan morfología española mejor que word-level
    # Rango (2,5): "zapati", "apatil", "patill", "atilla" → reconoce "zapatilla" incluso con typos
    pipe = Pipeline([
        ('tfidf', TfidfVectorizer(
            analyzer    = 'char_wb',   # character n-grams with word boundaries
            ngram_range = (2, 5),
            max_features= 80_000,
            min_df      = 2,
            sublinear_tf= True,        # log(1+tf) reduce dominancia de palabras muy frecuentes
            strip_accents='unicode',
        )),
        ('clf', LogisticRegression(
            C            = 8.0,
            max_iter     = 2000,
            solver       = 'lbfgs',
            random_state = 42,
        )),
    ])
    progress("text", 40, f"Entrenando TF-IDF (80k features) + LogisticRegression ({num_classes} clases)...")
    pipe.fit(X_train, y_train)
    progress("text", 75, f"Evaluando en {len(X_test)} muestras de prueba...")

    y_pred   = pipe.predict(X_test)
    accuracy = accuracy_score(y_test, y_pred)
    log(f"Accuracy en test: {accuracy*100:.1f}% ({int(accuracy*len(X_test))}/{len(X_test)} correctas)")

    # Reporte por categoría
    # Solo reportar clases que aparecen en test (evita mismatch si alguna tiene n<2)
    from sklearn.utils.multiclass import unique_labels
    present_idx   = sorted(unique_labels(y_test, y_pred))
    present_names = [str(le.classes_[i]) for i in present_idx]
    report_str = classification_report(
        y_test, y_pred,
        labels       = list(present_idx),
        target_names = present_names,
        zero_division= 0
    )
    log(f"\n{report_str}")

    # Guardar modelo
    model_path = models_dir / "text_classifier.pkl"
    le_path    = models_dir / "label_encoder.pkl"
    with open(model_path, 'wb') as f: pickle.dump(pipe, f, protocol=4)
    with open(le_path, 'wb') as f:    pickle.dump(le,   f, protocol=4)
    progress("text", 95, f"Modelo guardado → {model_path}")

    # Guardar también metadatos
    meta = {
        "accuracy": round(accuracy, 4),
        "num_classes": num_classes,
        "num_train": len(X_train),
        "num_test":  len(X_test),
        "classes":   list(le.classes_),
        "trained_at": datetime.now().isoformat(),
    }
    with open(models_dir / "text_meta.json", 'w') as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    progress("text", 100, f"✓ Texto listo — Accuracy: {accuracy*100:.1f}% | {num_classes} categorias | {len(X_train)} ejemplos entrenados")
    return meta


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    db_path   = sys.argv[1] if len(sys.argv) > 1 else "scraper.db"
    do_images = "--images" in sys.argv
    epochs    = int(next((sys.argv[i+1] for i, a in enumerate(sys.argv)
                          if a == "--epochs"), 8))
    models_dir = Path(db_path).parent / "_models"
    models_dir.mkdir(exist_ok=True)

    log(f"DB: {db_path}")
    log(f"Models dir: {models_dir}")
    log(f"Image training: {do_images}, epochs: {epochs}")

    # Cargar y limpiar dataset
    rows = load_dataset(db_path)
    if len(rows) < 50:
        out = {"status": "error", "reason": "pocas_muestras", "n": len(rows)}
        print(json.dumps(out)); return

    cleaned = clean_labels(rows)

    results = {"status": "ok", "n_samples": len(cleaned)}

    # Fase 1: Text
    progress("text", 0, "Iniciando clasificador de texto...")
    try:
        text_meta = train_text_model(cleaned, models_dir)
        results["text"] = text_meta
    except Exception as e:
        log(f"Error en text model: {e}")
        results["text"] = {"status": "error", "reason": str(e)}

    # Fase 2: Image — REMOVIDA (PR4, spec "ml-training-removal", design
    # decision #7). El clasificador de imagen bespoke (MobileNetV3/
    # EfficientNet) fue reemplazado por ml_embeddings.py (Marqo-
    # FashionSigLIP zero-shot, sin entrenamiento propio) — ver
    # ml_pipeline.py stage 1b. `--images` queda como no-op: avisa y sigue,
    # nunca rompe la corrida ni afecta la Fase 1 de texto.
    if do_images:
        log("no-op, image training removed — ver ml_embeddings.py backfill "
            "para clasificacion visual zero-shot")
        progress("image", 100, "no-op: entrenamiento de imagenes removido")
        results["image"] = {
            "status": "no-op",
            "reason": "image training removed (see ml_embeddings.py backfill)",
        }

    results["models_dir"] = str(models_dir)

    # ── Resumen unificado final ───────────────────────────────────────────────
    print("\n" + "="*60, file=sys.stderr)
    print("  ENTRENAMIENTO ML COMPLETADO", file=sys.stderr)
    print("="*60, file=sys.stderr)

    txt = results.get("text", {})
    if isinstance(txt, dict) and txt.get("accuracy"):
        acc = txt["accuracy"] * 100
        n_cls = txt.get("num_classes", 0)
        n_train = txt.get("num_train", 0)
        print(f"  Texto  : {acc:.1f}% accuracy | {n_cls} categorias | {n_train} ejemplos",
              file=sys.stderr)

    img = results.get("image", {})
    if isinstance(img, dict):
        if img.get("status") == "no-op":
            print(f"  Imagen : no-op — {img.get('reason', '')}", file=sys.stderr)
        elif img.get("status") == "ok":
            v_acc = img.get("val_accuracy", 0) * 100
            arch  = img.get("arch", "")
            dev   = img.get("device", "")
            n_img = img.get("num_images", 0)
            print(f"  Imagen : {v_acc:.1f}% val_acc | {arch} | {n_img} imagenes | {dev}",
                  file=sys.stderr)
        else:
            reason = img.get("reason", "desconocido")
            print(f"  Imagen : ERROR — {reason}", file=sys.stderr)

    print(f"  Modelos: {models_dir}", file=sys.stderr)
    print("="*60 + "\n", file=sys.stderr)

    # JSON unificado — SIEMPRE la ultima linea de stdout para facil parseo en Java
    print(json.dumps(results, ensure_ascii=False))

if __name__ == "__main__":
    main()
