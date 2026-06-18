#!/usr/bin/env python3
"""
Fashion Scraper ML Trainer
--------------------------
Entrena modelos de clasificación usando los productos del DB como dataset.

Fase 1 — Text classifier (TF-IDF + LogisticRegression): ~30 seg
Fase 2 — Image classifier (MobileNetV3-Small, PyTorch): ~1h CPU   [--images]

Uso:
  python ml_train.py scraper.db [--images] [--epochs N]

Output JSON:
  { "status": "ok", "text": {...metrics}, "image": {...metrics} }
"""

import sys, os, json, pickle, sqlite3, re, time
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

# ─── Fase 2: Image Classifier (PyTorch MobileNetV3-Small) ────────────────────

def _download_one(args):
    """Module-level for ThreadPoolExecutor pickling on Windows. Returns fpath_str or None."""
    prod_url, img_url, img_dir_str = args
    import urllib.request, hashlib
    from pathlib import Path
    from PIL import Image
    img_dir = Path(img_dir_str)
    if img_url.startswith("//"): img_url = "https:" + img_url
    if not img_url.startswith("http"): return None
    fname = hashlib.md5(prod_url.encode()).hexdigest() + ".jpg"
    fpath = img_dir / fname
    # Validate cached file
    if fpath.exists():
        try:
            with Image.open(fpath) as im: im.verify()
            return str(fpath)
        except Exception:
            fpath.unlink(missing_ok=True)
    # Download
    try:
        req = urllib.request.Request(img_url, headers={'User-Agent': 'Mozilla/5.0'})
        data = urllib.request.urlopen(req, timeout=5).read()
        with open(fpath, 'wb') as f: f.write(data)
        with Image.open(fpath) as im: im.verify()
        return str(fpath)
    except Exception:
        if fpath.exists(): fpath.unlink(missing_ok=True)
        return None


def load_image_dataset(db_path, max_images=3000):
    """Fetches image rows from DB with cleaned labels. Returns [(prod_url, img_url, cleaned_cat)]."""
    from pathlib import Path
    if not Path(db_path).exists():
        log(f"DB no encontrada: {db_path}"); return []
    conn = sqlite3.connect(db_path)
    cur = conn.execute("""
        SELECT url, imagen_url, nombre, categoria
        FROM productos
        WHERE activo=1 AND imagen_url IS NOT NULL AND imagen_url != ''
          AND categoria IS NOT NULL AND categoria != ''
        ORDER BY RANDOM() LIMIT ?
    """, (max_images,))
    rows = cur.fetchall(); conn.close()

    # Apply label corrections
    cleaned = []
    for prod_url, img_url, nombre, cat in rows:
        cat_corregida = corregir_etiqueta(nombre or '', cat.strip())
        cleaned.append((prod_url, img_url, cat_corregida.strip()))

    # Count and apply CAT_PARENTS merging
    from collections import Counter
    cat_counts = Counter(cat for _, _, cat in cleaned)
    result = []
    for prod_url, img_url, cat in cleaned:
        cat_norm = cat.lower().translate(ACCENT)
        if cat_counts[cat] < MIN_SAMPLES:
            parent = CAT_PARENTS.get(cat_norm)
            if parent:
                cat = parent
        result.append((prod_url, img_url, cat))

    log(f"load_image_dataset: {len(result)} filas, {len(set(c for _,_,c in result))} categorías limpias")
    return result


def download_images(img_dir, rows, max_images=3000):
    """Downloads images in parallel. rows = [(prod_url, img_url, cat)]. Returns [(fpath, cat)]."""
    from concurrent.futures import ThreadPoolExecutor, as_completed

    DOWNLOAD_WORKERS = 8
    GLOBAL_DEADLINE_S = 180

    img_dir.mkdir(exist_ok=True)
    rows = rows[:max_images]
    total = len(rows)
    if total == 0: return []

    args_list = [(prod_url, img_url, str(img_dir)) for prod_url, img_url, cat in rows]
    cat_map   = {prod_url: cat for prod_url, img_url, cat in rows}

    downloaded = []
    n_ok = n_fail = n_processed = 0
    t0 = time.monotonic()

    progress("image_download", 0, f"Descargando {total} imágenes con {DOWNLOAD_WORKERS} workers...")

    with ThreadPoolExecutor(max_workers=DOWNLOAD_WORKERS) as ex:
        future_to_url = {ex.submit(_download_one, args): args[0] for args in args_list}
        for fut in as_completed(future_to_url, timeout=GLOBAL_DEADLINE_S + 10):
            elapsed = time.monotonic() - t0
            if elapsed > GLOBAL_DEADLINE_S:
                ex.shutdown(wait=False, cancel_futures=True)
                log(f"Deadline global {GLOBAL_DEADLINE_S}s alcanzado — usando {len(downloaded)} imágenes")
                break
            prod_url = future_to_url[fut]
            try:
                fpath = fut.result()
                if fpath:
                    downloaded.append((fpath, cat_map[prod_url]))
                    n_ok += 1
                else:
                    n_fail += 1
            except Exception:
                n_fail += 1
            n_processed += 1
            if n_processed % 50 == 0:
                pct = int(n_processed / total * 100)
                eta = int((total - n_processed) / max(n_processed, 1) * elapsed / 60)
                progress("image_download", pct,
                         f"{n_processed}/{total} | {n_ok} OK | {n_fail} fail | ~{eta}min")

    elapsed = time.monotonic() - t0
    log(f"download_images: {n_ok} OK, {n_fail} fallidas, {total} intentadas, {elapsed:.0f}s")
    return downloaded


# ─── Module-level dataset class (must be pickleable for DataLoader multiprocessing) ─
class ProductImageDataset:
    """Dataset de imagenes de productos — definida a nivel de modulo para pickling."""
    def __init__(self, paths, labels, transform, img_size=224):
        self.paths     = paths
        self.labels    = labels
        self.transform = transform
        self.img_size  = img_size

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, idx):
        try:
            from PIL import Image
            img = Image.open(self.paths[idx]).convert("RGB")
        except Exception:
            from PIL import Image
            img = Image.new("RGB", (self.img_size, self.img_size), (128, 128, 128))
        return self.transform(img), int(self.labels[idx])


def get_device_info():
    """Detecta GPU y retorna info del dispositivo."""
    try:
        import torch
        if torch.cuda.is_available():
            gpu = torch.cuda.get_device_properties(0)
            vram_gb = gpu.total_memory / 1e9
            name    = gpu.name
            # RTX 3080 / 3090 / 4080 / 4090 → usar EfficientNet-B3
            # RTX 2060 / 2070 / 2080 / 3060 → usar EfficientNet-B1
            # CPU → MobileNetV3-Small
            if vram_gb >= 8:
                arch = "efficientnet_b3"
                batch_size = 96
            elif vram_gb >= 5:
                arch = "efficientnet_b1"
                batch_size = 64
            else:
                arch = "mobilenet_v3_small"
                batch_size = 32
            return {"device": "cuda", "name": name,
                    "vram_gb": round(vram_gb,1), "arch": arch, "batch": batch_size}
        else:
            return {"device": "cpu", "arch": "mobilenet_v3_small", "batch": 24, "vram_gb": 0}
    except ImportError:
        return {"device": "cpu", "arch": "mobilenet_v3_small", "batch": 24, "vram_gb": 0}

def build_model(arch, num_classes, device):
    """Construye el modelo según la arquitectura y hardware disponible."""
    import torch.nn as nn
    import torchvision.models as tv_models

    log(f"Arquitectura: {arch} para {num_classes} clases")

    if arch == "efficientnet_b3":
        m = tv_models.efficientnet_b3(weights="DEFAULT")
        in_f = m.classifier[-1].in_features
        m.classifier[-1] = nn.Sequential(
            nn.Dropout(0.4),
            nn.Linear(in_f, num_classes)
        )
    elif arch == "efficientnet_b1":
        m = tv_models.efficientnet_b1(weights="DEFAULT")
        in_f = m.classifier[-1].in_features
        m.classifier[-1] = nn.Sequential(
            nn.Dropout(0.3),
            nn.Linear(in_f, num_classes)
        )
    else:  # mobilenet_v3_small — CPU friendly
        m = tv_models.mobilenet_v3_small(weights="DEFAULT")
        in_f = m.classifier[-1].in_features
        m.classifier[-1] = nn.Linear(in_f, num_classes)

    return m.to(device)

def train_image_model(db_path, models_dir, epochs=15):
    """Fine-tune modelo de imágenes con optimización GPU automática."""
    try:
        import torch
        import torch.nn as nn
        import torch.optim as optim
        from torch.utils.data import Dataset, DataLoader
        import torchvision.transforms as T
        from PIL import Image, UnidentifiedImageError
    except ImportError:
        log("PyTorch no disponible.")
        log("Para GPU NVIDIA: pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121")
        log("Para CPU:        pip install torch torchvision")
        return {"status": "skipped", "reason": "pytorch_not_available"}

    hw = get_device_info()
    device     = torch.device(hw["device"])
    arch       = hw["arch"]
    batch_size = hw["batch"]
    use_amp    = hw["device"] == "cuda"  # AMP solo en CUDA

    log(f"=== Fase 2: Image Classifier ({arch}) ===")
    log(f"Dispositivo: {device} | GPU: {hw.get('name','CPU')} | VRAM: {hw['vram_gb']}GB")
    log(f"Batch size: {batch_size} | AMP: {use_amp}")
    progress("image", 0, f"GPU: {hw.get('name','CPU')} · arch: {arch}")

    img_dir = models_dir / "images"
    img_dir.mkdir(exist_ok=True)

    log("Cargando dataset con labels limpios...")
    raw_rows = load_image_dataset(db_path)
    log("Descargando imágenes...")
    samples = download_images(img_dir, raw_rows)
    if len(samples) < 50:
        return {"status": "skipped", "reason": f"pocas_imagenes ({len(samples)})"}

    cat_counts = Counter(cat for _, cat in samples)
    min_per_cat = 8 if len(samples) > 500 else 5
    valid_cats  = {cat for cat, n in cat_counts.items() if n >= min_per_cat}
    samples     = [(p, cat) for p, cat in samples if cat in valid_cats]
    log(f"Dataset: {len(samples)} imágenes · {len(valid_cats)} categorías")
    progress("image", 62, f"{len(samples)} imgs · {len(valid_cats)} cats")

    from sklearn.preprocessing import LabelEncoder as LE
    le         = LE()
    labels_all = le.fit_transform([cat for _, cat in samples])
    num_classes = len(le.classes_)

    # ── Dataset ──────────────────────────────────────────────────────────────
    IMG_SIZE = 300 if arch == "efficientnet_b3" else 240

    # Data augmentation más agresiva para fashion — mejora generalización
    train_tf = T.Compose([
        T.Resize((IMG_SIZE + 32, IMG_SIZE + 32)),
        T.RandomCrop(IMG_SIZE),
        T.RandomHorizontalFlip(p=0.5),
        T.RandomRotation(10),
        T.ColorJitter(brightness=0.3, contrast=0.3, saturation=0.25, hue=0.08),
        T.RandomGrayscale(p=0.05),
        T.ToTensor(),
        T.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    val_tf = T.Compose([
        T.Resize((IMG_SIZE, IMG_SIZE)),
        T.ToTensor(),
        T.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    # ProductImageDataset defined at module level (required for DataLoader pickling on Windows)

    paths = [p for p, _ in samples]
    split = int(len(paths) * 0.85)

    import platform
    # Windows: num_workers=0 para evitar problemas de pickling en multiprocessing
    workers  = 0 if platform.system() == 'Windows' else (4 if hw["device"] == "cuda" else 0)
    pin_mem  = hw["device"] == "cuda" and platform.system() != 'Windows'
    train_dl = DataLoader(ProductImageDataset(paths[:split],  labels_all[:split],  train_tf),
                          batch_size=batch_size, shuffle=True,  num_workers=workers,
                          pin_memory=pin_mem, persistent_workers=workers>0)
    val_dl   = DataLoader(ProductImageDataset(paths[split:],  labels_all[split:],  val_tf),
                          batch_size=batch_size*2, shuffle=False, num_workers=workers,
                          pin_memory=pin_mem, persistent_workers=workers>0)

    # ── Modelo ───────────────────────────────────────────────────────────────
    model = build_model(arch, num_classes, device)

    # torch.compile solo en Linux/Mac (Triton no disponible en Windows)
    if use_amp and hasattr(torch, 'compile') and platform.system() != 'Windows':
        try:
            model = torch.compile(model, mode="reduce-overhead")
            log("torch.compile() activado ✓")
        except Exception as e:
            log(f"torch.compile() no disponible: {e}")
    elif platform.system() == 'Windows':
        log("torch.compile() deshabilitado en Windows (Triton no disponible) — usando eager mode")

    criterion = nn.CrossEntropyLoss(label_smoothing=0.1)  # label smoothing mejora generalización
    scaler    = torch.amp.GradScaler("cuda") if use_amp else None

    # ── Training loop ────────────────────────────────────────────────────────
    # Fase A: solo clasificador, features congeladas (epochs 0-3)
    for param in model.parameters():
        param.requires_grad = False
    for param in (model.classifier if hasattr(model,'classifier') else model.fc).parameters():
        param.requires_grad = True

    optimizer = optim.AdamW(
        filter(lambda p: p.requires_grad, model.parameters()),
        lr=1e-3, weight_decay=1e-4
    )
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=5, T_mult=2)

    def run_epoch(dl, train=True, ep_label=""):
        model.train(train)
        loss_sum = correct = total = 0
        n_batches = len(dl)
        ctx = torch.no_grad() if not train else torch.enable_grad()
        with ctx:
            for batch_idx, (imgs, lbls) in enumerate(dl):
                imgs, lbls = imgs.to(device, non_blocking=True), lbls.to(device, non_blocking=True)
                if train: optimizer.zero_grad(set_to_none=True)
                if use_amp:
                    with torch.amp.autocast("cuda"):
                        out  = model(imgs)
                        loss = criterion(out, lbls)
                    if train:
                        scaler.scale(loss).backward()
                        scaler.unscale_(optimizer)
                        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                        scaler.step(optimizer)
                        scaler.update()
                else:
                    out  = model(imgs)
                    loss = criterion(out, lbls)
                    if train:
                        loss.backward()
                        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                        optimizer.step()
                loss_sum += loss.item()
                correct  += (out.argmax(1) == lbls).sum().item()
                total    += len(lbls)
                if train and n_batches >= 10 and (batch_idx + 1) % max(1, n_batches // 5) == 0:
                    pct = int(100 * (batch_idx + 1) / n_batches)
                    log(f"{ep_label} batch {batch_idx+1}/{n_batches} ({pct}%) — loss={loss.item():.3f} acc={correct/max(total,1):.1%}")
        return loss_sum / max(len(dl), 1), correct / max(total, 1)

    best_acc  = 0.0
    best_path = models_dir / "image_model.pt"
    WARMUP_EPOCHS = 3

    for ep in range(epochs):
        # Descongelar todo después del warmup
        if ep == WARMUP_EPOCHS:
            log("Fase B: descongelando todas las capas — fine-tuning profundo (lr=5e-5)")
            for param in model.parameters():
                param.requires_grad = True
            optimizer = optim.AdamW(model.parameters(), lr=5e-5, weight_decay=1e-4)
            scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs-WARMUP_EPOCHS)
            # Fase B activa gradientes en toda la red — reducir batch para evitar OOM
            bs_b = max(16, batch_size // 3)
            log(f"Fase B: reduciendo batch_size {batch_size} → {bs_b} para caber en VRAM con full fine-tuning")
            if use_amp:
                torch.cuda.empty_cache()
            train_dl = DataLoader(ProductImageDataset(paths[:split], labels_all[:split], train_tf),
                                  batch_size=bs_b, shuffle=True, num_workers=workers,
                                  pin_memory=pin_mem, persistent_workers=workers > 0)
            val_dl   = DataLoader(ProductImageDataset(paths[split:], labels_all[split:], val_tf),
                                  batch_size=bs_b * 2, shuffle=False, num_workers=workers,
                                  pin_memory=pin_mem, persistent_workers=workers > 0)

        fase = "B" if ep >= WARMUP_EPOCHS else "A"
        tr_loss, tr_acc = run_epoch(train_dl, train=True, ep_label=f"[Fase {fase} ep{ep+1}]")
        scheduler.step()
        val_loss, val_acc = run_epoch(val_dl, train=False)

        if use_amp:
            mem_gb = torch.cuda.memory_reserved(0) / 1e9
            log(f"Epoch {ep+1}/{epochs}: train={tr_acc*100:.1f}%  val={val_acc*100:.1f}%  loss={tr_loss:.3f}  GPU={mem_gb:.1f}GB {'(nuevo mejor!)' if val_acc > best_acc else ''}")
        else:
            log(f"Epoch {ep+1:2d}/{epochs}: train={tr_acc*100:.1f}% val={val_acc*100:.1f}%"
                f" loss={tr_loss:.3f}")

        pct = 65 + int((ep+1)/epochs * 30)
        gpu_info = f'RTX 3080 AMP' if use_amp else 'CPU'
        progress("image", pct,
                 f"Epoch {ep+1}/{epochs} · val={val_acc*100:.1f}% · mejor={best_acc*100:.1f}% · {gpu_info}")

        if val_acc > best_acc:
            best_acc = val_acc
            torch.save({
                "model_state_dict": model.state_dict(),
                "arch":       arch,
                "classes":    list(le.classes_),
                "num_classes": num_classes,
                "img_size":   IMG_SIZE,
                "val_acc":    val_acc,
                "trained_at": datetime.now().isoformat(),
                "device_info": hw,
            }, best_path)
            log(f"  ✓ Mejor modelo guardado (val_acc={val_acc*100:.1f}%)")

    with open(models_dir / "image_label_encoder.pkl", "wb") as f:
        pickle.dump(le, f, protocol=4)

    if use_amp:
        torch.cuda.empty_cache()

    progress("image", 100, f"✓ val_acc: {best_acc*100:.1f}% | GPU: {hw.get('name','—')}")
    return {
        "status":       "ok",
        "val_accuracy": round(best_acc, 4),
        "arch":         arch,
        "num_images":   len(samples),
        "num_classes":  num_classes,
        "classes":      list(le.classes_),
        "device":       hw.get("name", str(device)),
        "amp":          use_amp,
    }

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

    # Fase 2: Image (opcional)
    if do_images:
        progress("image", 0, "Iniciando clasificador de imágenes...")
        try:
            img_meta = train_image_model(db_path, models_dir, epochs=epochs)
            results["image"] = img_meta
        except Exception as e:
            log(f"Error en image model: {e}")
            results["image"] = {"status": "error", "reason": str(e)}

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
        if img.get("status") == "ok":
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
