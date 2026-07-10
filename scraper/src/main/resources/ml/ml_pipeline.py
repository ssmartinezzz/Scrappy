#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fashion Scraper ML Pipeline v2 — Statistical Price Intelligence
===============================================================
Entrada : ml_productos.json  (arg 1)
Salida  : ml_output.json     (arg 2)
Historial: precio_historico.json (arg 3)

Modelos estadísticos:
  1. PriceStats por categoría+género: media, mediana, moda, desvío estándar,
     varianza, IQR, MAD, coeficiente de variación
  2. Z-score y Z-score modificado (robusto con MAD)
  3. Tukey fences para detección de outliers baratos/caros
  4. Score compuesto (percentil + z-score + distancia a mediana)
  5. Detección de oferta real estadística (vs distribución de descuentos)
  6. Segmentación de precio: budget / standard / premium / luxury
  7. Análisis histórico: media móvil, tendencia, velocidad de cambio
  8. TF-IDF clustering con bigrams para tendencias
"""
import json, sys, os, math, re
# UTF-8 forzado via PYTHONIOENCODING en el ProcessBuilder
from datetime import datetime, timedelta
from collections import defaultdict

_TEXT_LABEL_SET: frozenset = frozenset()

# Categorías genéricas/placeholder de texto — cuando el texto cae en una de
# estas, la clasificación de imagen (stage 1b) se intenta igual aunque la
# confianza de texto sea alta, porque "Indumentaria"/"Ropa"/etc. no aportan
# señal real. Ver `needs_image_fallback()`.
GENERICAS = frozenset({'indumentaria', 'general', 'ropa', 'pc & tech', 'tecnologia', ''})


# ─── Clase estadística por grupo ────────────────────────────────────────────


# ─── Cargar modelo entrenado si existe ───────────────────────────────────────
def load_trained_models(db_path="scraper.db"):
    """Carga el modelo de texto entrenado por ml_train.py si existe.

    PR4: la carga del modelo de imagen bespoke (MobileNetV3/EfficientNet,
    entrenado por ``ml_train.py --images``) fue removida — la clasificación
    de imagen ahora la hace ``ml_embeddings.py`` (Marqo-FashionSigLIP,
    zero-shot, sin entrenamiento propio). Ver ``predict_category_image``
    (removida) y ``ml_train.py``'s ``--images`` no-op.
    """
    from pathlib import Path
    models_dir = Path(db_path).parent / "_models"
    text_model = le = None

    # ── Modelo de texto (TF-IDF + LogReg) ──────────────────────────────────
    try:
        import pickle
        tp = models_dir / "text_classifier.pkl"
        lp = models_dir / "label_encoder.pkl"
        if tp.exists() and lp.exists():
            with open(tp, 'rb') as f: text_model = pickle.load(f)
            with open(lp, 'rb') as f: le          = pickle.load(f)
            global _TEXT_LABEL_SET
            _TEXT_LABEL_SET = frozenset(str(c) for c in le.classes_)
            meta_path = models_dir / "text_meta.json"
            acc = json.load(open(meta_path))['accuracy'] if meta_path.exists() else 0
            print(f"[ML] Modelo texto cargado (acc={acc*100:.1f}%, {len(le.classes_)} clases)",
                  file=sys.stderr)
    except Exception as e:
        print(f"[ML] Sin modelo de texto: {e}", file=sys.stderr)

    return text_model, le

def predict_category(text_model, le, nombre, confianza_min=0.70):
    """Predice categoría con modelo de texto. Retorna (label, confianza) o (None, 0)."""
    if text_model is None or le is None: return None, 0.0
    try:
        probs = text_model.predict_proba([nombre])[0]
        best_idx  = probs.argmax()
        best_prob = probs[best_idx]
        if best_prob >= confianza_min:
            return str(le.classes_[best_idx]), float(best_prob)
    except Exception:
        pass
    return None, 0.0


def needs_image_fallback(txt_conf, categoria, genero, genericas=GENERICAS):
    """Stage 1b gate (spec: "visual-classification-rules", text-wins
    invariant). Zero-shot image classification (``ml_embeddings``) is only
    ever attempted when text is genuinely uncertain — never to second-guess
    a confident, specific text prediction:

      - text confidence < 0.75, OR
      - the text category is generic/placeholder (`genericas`), OR
      - gender is blank (NEW in PR4 — image can fill a gender gap even when
        the text category itself is confident and specific; this is the one
        case where image fires without questioning `categoria` at all).

    Pure function, no I/O — kept separate from the img_candidates
    comprehension in `main()` so the gate itself is unit-testable without a
    real/mocked model.
    """
    cat_norm = (categoria or '').strip().lower()
    return (txt_conf < 0.75) or (cat_norm in genericas) or not (genero or '').strip()


class PriceStats:
    """
    Estadísticas completas de una distribución de precios.
    Incluye medidas robustas (mediana/MAD) además de las clásicas (media/std).
    """
    MIN_SAMPLE = 3  # mínimo para calcular stats significativas

    def __init__(self, values):
        self.raw    = [v for v in values if v > 0]
        self.values = sorted(self.raw)
        self.n      = len(self.values)

        if self.n == 0:
            self._init_empty(); return

        # ── Medidas de tendencia central ──────────────────────────────────
        self.mean   = sum(self.values) / self.n
        self.median = self._percentile_val(50)

        # IQR debe calcularse ANTES que la moda (la moda lo usa para bin_size)
        self.q1     = self._percentile_val(25)
        self.q3     = self._percentile_val(75)
        self.iqr    = self.q3 - self.q1

        self.mode   = self._calc_mode()

        # ── Medidas de dispersión ─────────────────────────────────────────
        variance    = sum((x - self.mean) ** 2 for x in self.values) / self.n
        self.std    = math.sqrt(variance)
        self.var    = variance

        # MAD (desviación absoluta mediana) — más robusto que std ante outliers
        deviations  = sorted(abs(x - self.median) for x in self.values)
        self.mad    = self._median_of(deviations)

        # Coeficiente de variación: qué tan "dispersa" está la categoría
        # CV alto = precios muy variados → comparación menos significativa
        # CV bajo = precios homogéneos → comparación más confiable
        self.cv     = (self.std / self.mean * 100) if self.mean > 0 else 0

        # Tukey fences para outliers
        self.fence_low  = self.q1 - 1.5 * self.iqr
        self.fence_high = self.q3 + 1.5 * self.iqr

        # Fence extremos (outliers severos)
        self.fence_low_ext  = self.q1 - 3.0 * self.iqr
        self.fence_high_ext = self.q3 + 3.0 * self.iqr

    def _init_empty(self):
        self.mean = self.median = self.mode = self.std = self.var = 0
        self.q1 = self.q3 = self.iqr = self.mad = self.cv = 0
        self.fence_low = self.fence_high = 0
        self.fence_low_ext = self.fence_high_ext = 0

    def _percentile_val(self, p):
        if self.n == 0: return 0
        idx = (p / 100) * (self.n - 1)
        lo, hi = int(idx), min(int(idx) + 1, self.n - 1)
        return self.values[lo] + (idx - lo) * (self.values[hi] - self.values[lo])

    def _median_of(self, vals):
        if not vals: return 0
        s = sorted(vals); n = len(s)
        return s[n // 2] if n % 2 else (s[n//2 - 1] + s[n//2]) / 2

    def _calc_mode(self):
        """Moda: precio más frecuente. Si todos únicos → bin más poblado."""
        if self.n == 0: return 0
        from collections import Counter
        # Agrupar en bins de $5000 para encontrar zona más densa
        bin_size = max(5000, int(self.iqr / 5) if self.iqr > 0 else 5000)
        bins = Counter(int(v // bin_size) * bin_size for v in self.values)
        modal_bin = bins.most_common(1)[0][0]
        # Retornar la mediana de los valores en ese bin
        bin_vals = [v for v in self.values if int(v // bin_size) * bin_size == modal_bin]
        return self._median_of(bin_vals)

    # ── Scoring ──────────────────────────────────────────────────────────────

    def percentile_rank(self, x):
        """Percentil de x en la distribución (0-100). 0=más barato, 100=más caro."""
        if self.n < 2: return 50
        below = sum(1 for v in self.values if v < x)
        return int(100 * below / self.n)

    def z_score(self, x):
        """Z-score estándar. < -2: muy barato, > +2: muy caro."""
        if self.std == 0 or self.n < self.MIN_SAMPLE: return 0
        return (x - self.mean) / self.std

    def z_score_modified(self, x):
        """
        Z-score modificado de Iglewicz & Hoaglin (1993).
        Usa mediana y MAD → robusto contra outliers.
        |mz| > 3.5 = outlier claro.
        """
        if self.mad == 0 or self.n < self.MIN_SAMPLE: return self.z_score(x)
        return 0.6745 * (x - self.median) / self.mad

    def composite_score(self, x):
        """
        Score compuesto 0-100 que combina:
          40% percentil de posición
          35% z-score modificado normalizado (más robusto)
          25% distancia relativa a la mediana

        0 = extremadamente barato, 100 = extremadamente caro.
        50 = exactamente en la mediana.
        """
        if self.n < self.MIN_SAMPLE:
            return self.percentile_rank(x)

        # Percentil: ya está en 0-100
        pct = self.percentile_rank(x)

        # Z-score modificado normalizado a 0-100
        mz  = self.z_score_modified(x)
        mz_norm = min(100, max(0, (mz + 3) / 6 * 100))

        # Distancia relativa a la mediana (normalizada por IQR)
        if self.iqr > 0:
            dist = (x - self.median) / self.iqr  # -∞ a +∞
            dist_norm = min(100, max(0, (dist + 2) / 4 * 100))
        else:
            dist_norm = 50

        return round(0.40 * pct + 0.35 * mz_norm + 0.25 * dist_norm)

    def price_segment(self, x):
        """
        Segmento estadístico del precio:
          budget   : x < Q1           (cuartil inferior)
          standard : Q1 <= x <= Q3    (rango intercuartílico)
          premium  : Q3 < x <= Tukey  (sobre la mediana pero no outlier)
          luxury   : x > Tukey fence  (outlier superior de Tukey)
        """
        if self.n < self.MIN_SAMPLE:
            return 'standard'
        if x < self.q1:   return 'budget'
        if x <= self.q3:  return 'standard'
        if x <= self.fence_high: return 'premium'
        return 'luxury'

    def is_cheap_outlier(self, x):
        """Outlier inferior de Tukey: x < Q1 - 1.5*IQR"""
        return self.n >= self.MIN_SAMPLE and self.iqr > 0 and x < self.fence_low

    def is_expensive_outlier(self, x):
        """Outlier superior de Tukey: x > Q3 + 1.5*IQR"""
        return self.n >= self.MIN_SAMPLE and self.iqr > 0 and x > self.fence_high

    def to_dict(self):
        return {
            'n': self.n, 'mean': round(self.mean), 'median': round(self.median),
            'mode': round(self.mode), 'std': round(self.std), 'cv': round(self.cv, 1),
            'q1': round(self.q1), 'q3': round(self.q3), 'iqr': round(self.iqr),
            'mad': round(self.mad), 'fence_low': round(self.fence_low),
            'fence_high': round(self.fence_high)
        }


# ─── Análisis histórico ──────────────────────────────────────────────────────

class HistoricalAnalysis:
    """
    Análisis de tendencia de precio histórico usando media móvil.
    """
    def __init__(self, history_points):
        """history_points: [{'fecha': 'YYYY-MM-DD', 'precio': N}]"""
        self.points = sorted(history_points, key=lambda p: p['fecha'])

    def moving_average(self, window=7):
        """Media móvil de los últimos N días."""
        if len(self.points) < 2: return None
        recent = self.points[-window:]
        return sum(p['precio'] for p in recent) / len(recent)

    def trend(self):
        """
        Tendencia de precio:
          'bajando'  : precio actual < media móvil Y variación > 5%
          'subiendo' : precio actual > media móvil Y variación > 5%
          'estable'  : variación <= 5%
        """
        if len(self.points) < 2: return 'estable'
        current = self.points[-1]['precio']
        ma = self.moving_average()
        if ma is None or ma == 0: return 'estable'
        delta_pct = (current - ma) / ma * 100
        if delta_pct <= -5:  return 'bajando'
        if delta_pct >= 5:   return 'subiendo'
        return 'estable'

    def price_velocity(self):
        """
        Velocidad de cambio de precio: % de cambio por día en los últimos 30 días.
        Positivo = subiendo, negativo = bajando.
        """
        if len(self.points) < 2: return 0
        p0, p1 = self.points[0], self.points[-1]
        try:
            d0 = datetime.strptime(p0['fecha'], '%Y-%m-%d')
            d1 = datetime.strptime(p1['fecha'], '%Y-%m-%d')
            days = max(1, (d1 - d0).days)
            return round((p1['precio'] - p0['precio']) / p0['precio'] / days * 100, 4)
        except:
            return 0

    def min_price(self):
        return min(p['precio'] for p in self.points) if self.points else 0

    def max_price(self):
        return max(p['precio'] for p in self.points) if self.points else 0

    def is_historical_low(self, current, tolerance=0.05):
        """True si el precio actual es el mínimo histórico (con tolerancia del 5%)."""
        hist_min = self.min_price()
        return hist_min > 0 and current <= hist_min * (1 + tolerance)


# ─── Helpers ─────────────────────────────────────────────────────────────────

def safe_price(raw):
    if not raw: return 0.0
    s = str(raw).strip()
    # Rechazar strings que contienen NaN, null, undefined
    if any(bad in s.lower() for bad in ('nan', 'null', 'undefined', 'none')): return 0.0
    s = re.sub(r'[^\d,.]', '', s)
    if not s: return 0.0
    # Manejar formato argentino: 1.249.999,99 o 1249999
    dots  = s.count('.')
    comas = s.count(',')
    if comas == 1 and dots >= 1:
        # 1.249.999,99 → quitar puntos, coma → punto decimal
        s = s.replace('.', '').replace(',', '.')
    elif comas == 1 and dots == 0:
        # 99,99 → coma decimal AR, sin separador de miles → punto decimal
        s = s.replace(',', '.')
    elif dots == 1 and comas == 0:
        # Un solo punto: puede ser separador decimal o de miles, según la cantidad
        # de dígitos después del punto y el tamaño de la parte entera (precios AR
        # son típicamente enteros en pesos).
        intpart, frac = s.split('.')
        if len(frac) == 3:
            s = s.replace('.', '')                 # 199.500 → 199500 (miles)
        elif len(frac) <= 2 and len(intpart) <= 3:
            pass                                    # 199.50 / 12.99 → decimal real (precio < 1000)
        else:
            # 1234.5 / 12345.67 → punto como separador de miles AR (precio entero)
            s = s.replace('.', '')
    else:
        # Sin separadores claros → quitar todo excepto dígitos
        s = s.replace('.', '').replace(',', '')
    try:
        v = float(s)
        return v if 0 < v < 100_000_000 else 0.0
    except:
        return 0.0


def tfidf_simple(docs):
    stop = {'de','la','el','los','las','un','una','para','con','en','y','a',
            'zapatillas','remera','campera','pantalon','short','buzo','calza',
            'pollera','mochila','gorra','medias','bota','ojota'}
    tf = []
    all_terms = set()
    for doc in docs:
        terms = [w for w in re.findall(r'[a-zA-ZáéíóúñÁÉÍÓÚÑ]+', doc.lower())
                 if w not in stop and len(w) > 2]
        freq = defaultdict(int)
        for t in terms: freq[t] += 1
        tf.append(freq)
        all_terms.update(freq.keys())

    n = len(docs)
    idf = {t: math.log((n+1) / (sum(1 for f in tf if t in f) + 1)) + 1 for t in all_terms}
    tfidf = []
    for freq in tf:
        total = max(sum(freq.values()), 1)
        tfidf.append({t: (freq[t] / total) * idf.get(t, 1) for t in freq})
    return tfidf


def cosine_sim(a, b):
    keys = set(a) & set(b)
    if not keys: return 0.0
    dot = sum(a[k] * b[k] for k in keys)
    na  = math.sqrt(sum(v*v for v in a.values()))
    nb  = math.sqrt(sum(v*v for v in b.values()))
    return dot / (na * nb) if na and nb else 0.0


def cluster_productos(nombres, threshold=0.28):
    if not nombres: return []
    vecs = tfidf_simple(nombres)
    n    = len(vecs)
    cluster_id = [-1] * n
    next_cluster = 0
    centroids    = []

    for i in range(n):
        best_cluster, best_sim = -1, threshold
        for ci, centroid in enumerate(centroids):
            sim = cosine_sim(vecs[i], centroid)
            if sim > best_sim:
                best_sim, best_cluster = sim, ci

        if best_cluster == -1:
            cluster_id[i] = next_cluster
            centroids.append(dict(vecs[i]))
            next_cluster += 1
        else:
            cluster_id[i] = best_cluster
            c = centroids[best_cluster]
            members = sum(1 for cid in cluster_id if cid == best_cluster)
            for k in set(c) | set(vecs[i]):
                c[k] = (c.get(k, 0) * (members - 1) + vecs[i].get(k, 0)) / members

    return cluster_id


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 3:
        print("Uso: ml_pipeline.py productos.json ml_output.json [precio_historico.json]",
              file=sys.stderr)
        sys.exit(1)

    prod_path = sys.argv[1]
    out_path  = sys.argv[2]
    hist_path = sys.argv[3] if len(sys.argv) > 3 else None

    with open(prod_path, 'r', encoding='utf-8') as f:
        productos = json.load(f)
    print(f"[ML] {len(productos)} productos", file=sys.stderr)

    today = datetime.now().strftime('%Y-%m-%d')

    # ─── 1. Construir PriceStats por grupo ───────────────────────────────────
    print("[ML] Calculando estadísticas por categoría+género...", file=sys.stderr)
    # Cargar modelo entrenado para refinar categorías
    json_arg = sys.argv[1] if len(sys.argv) > 1 else "ml_productos.json"
    db_path_hint = os.path.join(os.path.dirname(os.path.abspath(json_arg)), "scraper.db")
    text_model, label_enc = load_trained_models(db_path_hint)
    ml_refinements = 0  # contador de categorías refinadas por ML

    # ── Normalización de categorías ──────────────────────────────────────
    def norm_cat(raw):
        """Normaliza nombre de categoría para agrupamiento consistente."""
        if not raw: return 'general'
        s = str(raw).lower().strip()
        for a, b in [('á','a'),('é','e'),('í','i'),('ó','o'),('ú','u'),('ü','u'),('ñ','n')]:
            s = s.replace(a, b)
        return s.strip()

    # Jerarquía padre→hijos para subcategorías con pocas muestras
    CAT_PARENTS = {
        # Calzado
        'zapatilla running': 'zapatilla', 'zapatilla urbana': 'zapatilla',
        'zapatilla entrenamiento': 'zapatilla', 'zapatilla skate': 'zapatilla',
        'sneaker': 'zapatilla',
        # Inferior
        'jean': 'pantalon', 'jogging': 'pantalon', 'baggy': 'pantalon',
        'short': 'pantalon', 'calza': 'pantalon', 'pollera': 'pantalon',
        # Superior
        'remera': 'indumentaria', 'buzo': 'indumentaria', 'sweater': 'indumentaria',
        'campera': 'indumentaria', 'puffer': 'indumentaria', 'musculosa': 'indumentaria',
        'camisa': 'indumentaria', 'ropa training': 'indumentaria',
        # Calzado general
        'botas': 'calzado', 'botines': 'calzado', 'ojotas': 'calzado',
        # Accesorios
        'mochila': 'accesorio', 'gorra': 'accesorio', 'medias': 'accesorio',
        # Nuevas categorías
        'borcego':      'calzado',
        'sandalia':     'calzado',
        'mocasin':      'calzado',
        'zapato':       'calzado',
        'pantufla':     'calzado',
        'chomba':       'indumentaria',
        'casaca':       'indumentaria',
        'chaleco':      'indumentaria',
        'saco':         'indumentaria',
        'traje':        'indumentaria',
        'piloto':       'indumentaria',
        'vestido':      'indumentaria',
        'enterito':     'indumentaria',
        'bermuda':      'pantalon',
        'malla':        'indumentaria',
        'calzoncillos': 'indumentaria',
        'corpino':      'indumentaria',
        'rinonera':     'accesorio',
        'riñonera':     'accesorio',
        'billetera':    'accesorio',
        'cinturon':     'accesorio',
        'cinturón':     'accesorio',
        'gorro':        'accesorio',
        'bufanda':      'accesorio',
        'guantes':      'accesorio',
        'lentes':       'accesorio',
        'bolso':        'accesorio',
        'tarjetero':    'accesorio',
        'notebook':     'tecnologia',
        'monitor':      'tecnologia',
        'teclado':      'tecnologia',
        'mouse':        'tecnologia',
        'gpu':          'tecnologia',
        'ram':          'tecnologia',
        'cpu':          'tecnologia',
        'gabinete':     'tecnologia',
        'auricular':    'tecnologia',
        'perfume':      'nutricion',
    }

    MIN_GROUP = 10   # mínimo de productos para usar la subcategoría directamente

    # Paso 1: contar por categoría para decidir si usar o subir a padre
    cat_counts = defaultdict(int)
    for p in productos:
        cat_counts[norm_cat(p.get('categoria'))] += 1

    def elegir_cat(cat_raw, rubro):
        """Elige la clave de agrupamiento: subcategoría si tiene suficientes muestras,
        sino la categoría padre para estadísticas más robustas."""
        nc = norm_cat(cat_raw)
        if rubro == 'tecnologia':
            return f"tech|{nc}"
        if cat_counts.get(nc, 0) >= MIN_GROUP:
            return nc
        parent = CAT_PARENTS.get(nc)
        if parent and cat_counts.get(parent, 0) >= MIN_GROUP:
            return parent
        return nc   # usar igualmente, incluso si es pequeño

    # Precio por unidad (pack/combo aware): unidades>1 → precio_unit = precio/unidades;
    # unidades==1 (default, producto sin pack) → precio_unit == precio, sin cambios de
    # comportamiento. Se usa para TODO el agrupamiento de precios y scoring por producto
    # (percentile/z/composite/segment/oferta_real), nunca para display, descuento (precioOriginal)
    # ni historial — esos siguen trackeando el precio de estantería (shelf price).
    def precio_unitario(p):
        unidades = max(1, int(p.get('cantidadUnidades', 1) or 1))
        precio   = p.get('precio', 0)
        return precio / unidades if unidades > 1 else precio

    grupos_precios = defaultdict(list)
    for p in productos:
        cat   = (p.get('categoria') or 'General').strip() or 'General'
        rubro = (p.get('rubro') or 'indumentaria').strip()
        genero = (p.get('genero') or '').strip()
        key = elegir_cat(cat, rubro)
        # Para ropa, añadir género como dimensión secundaria solo si grupo grande
        if rubro != 'tecnologia' and genero and cat_counts.get(norm_cat(cat), 0) >= 20:
            key = f"{key}|{genero}"
        grupos_precios[key].append(precio_unitario(p))

    # Categoría normalizada sola (para stats de scoring por producto — distinta de
    # cat_prices/cat_stats_output más abajo, que es SOLO para el panel de tendencias y
    # se mantiene en precio de estantería)
    cats_precios = defaultdict(list)
    for p in productos:
        cat = norm_cat((p.get('categoria') or 'General').strip() or 'General')
        cats_precios[cat].append(precio_unitario(p))

    # Construir objetos PriceStats
    stats_grupos = {key: PriceStats(vals) for key, vals in grupos_precios.items()}
    stats_cats   = {cat:  PriceStats(vals) for cat,  vals in cats_precios.items()}

    # Log estadísticas por categoría
    for cat, st in sorted(stats_cats.items(), key=lambda x: -x[1].n)[:8]:
        print(f"[ML]   {cat}: n={st.n} med=${st.median:,.0f} "
              f"mad=${st.mad:,.0f} cv={st.cv:.0f}% "
              f"fence=[${st.fence_low:,.0f}, ${st.fence_high:,.0f}]",
              file=sys.stderr)

    # ─── 2. Historial de precios ─────────────────────────────────────────────
    print("[ML] Cargando historial...", file=sys.stderr)
    history = {}
    if hist_path and os.path.exists(hist_path):
        try:
            with open(hist_path, 'r', encoding='utf-8') as f:
                history = json.load(f)
        except:
            history = {}

    # Actualizar historial con precios actuales
    for p in productos:
        pid = p.get('url', '') or p.get('nombre', '')
        if not pid: continue
        if pid not in history:
            history[pid] = []
        last = history[pid][-1] if history[pid] else None
        if not last or last['fecha'] != today or abs(last['precio'] - p.get('precio', 0)) > 0.01:
            history[pid].append({'fecha': today, 'precio': p.get('precio', 0)})
            if len(history[pid]) > 60:
                history[pid] = history[pid][-60:]

    # ─── 3. Distribución de descuentos por categoría ─────────────────────────
    print("[ML] Analizando distribución de descuentos...", file=sys.stderr)
    descuentos_por_cat = defaultdict(list)
    for p in productos:
        precio = p.get('precio', 0)
        raw_orig = (p.get('precioOriginal') or '').strip()
        orig = safe_price(raw_orig) if raw_orig and raw_orig.lower() not in ('nan','null','none','') else 0.0
        # Hard guard: orig must be at least 20% above precio to be a real discount
        if orig > 0 and orig < precio * 1.20:
            orig = 0.0
        if orig > precio > 0 and 1.0 < orig/precio <= 5.0:
            cat = (p.get('categoria') or 'General').strip() or 'General'
            descuentos_por_cat[cat].append(orig / precio)

    stats_descuentos = {cat: PriceStats(vals) for cat, vals in descuentos_por_cat.items()}

    # Distribución global de descuentos (agregado de todas las categorías) — usada como
    # fallback para categorías long-tail con muestra insuficiente (R6).
    all_descuentos = [r for vals in descuentos_por_cat.values() for r in vals]
    stats_desc_global = PriceStats(all_descuentos) if len(all_descuentos) >= 5 else None
    n_fallback_global = 0  # contador de productos que usaron el fallback global (R6.4)

    # ─── 4. Score compuesto + análisis histórico por producto ────────────────
    print("[ML] Calculando scores compuestos...", file=sys.stderr)
    scores = {}

    for p in productos:
        pid    = p.get('url', '') or p.get('nombre', '')
        rubro  = (p.get('rubro') or 'indumentaria').strip()
        precio = p.get('precio', 0)
        precio_unit = precio_unitario(p)
        cat    = (p.get('categoria') or 'General').strip() or 'General'
        genero = (p.get('genero') or '').strip()

        # Usar la misma lógica de agrupamiento que en la construcción de stats
        cat_nc   = norm_cat(cat)
        key_base = elegir_cat(cat, rubro)
        if rubro != 'tecnologia' and genero and cat_counts.get(cat_nc, 0) >= 20:
            key_full = f"{key_base}|{genero}"
            st = (stats_grupos.get(key_full)
                  or stats_grupos.get(key_base)
                  or stats_cats.get(cat_nc)
                  or PriceStats([precio_unit]))
        else:
            st = (stats_grupos.get(key_base)
                  or stats_cats.get(cat_nc)
                  or PriceStats([precio_unit]))

        # Scores estadísticos — calculados sobre precio_unit (precio/unidades cuando
        # es un pack/combo; precio_unit == precio cuando unidades==1, sin cambio de
        # comportamiento para productos de unidad simple)
        pct          = st.percentile_rank(precio_unit)
        z            = st.z_score(precio_unit)
        mz           = st.z_score_modified(precio_unit)
        composite    = st.composite_score(precio_unit)
        segment      = st.price_segment(precio_unit)
        cheap_outlier = st.is_cheap_outlier(precio_unit)
        exp_outlier   = st.is_expensive_outlier(precio_unit)

        # Análisis histórico
        hist_pts  = history.get(pid, [])
        hist_anal = HistoricalAnalysis(hist_pts)
        trend     = hist_anal.trend()
        velocity  = hist_anal.price_velocity()
        hist_low  = hist_anal.is_historical_low(precio) if len(hist_pts) >= 3 else False
        hist_min  = hist_anal.min_price()
        hist_max  = hist_anal.max_price()

        # Análisis de descuento
        orig  = safe_price(p.get('precioOriginal', ''))
        ratio = orig / precio if orig > precio > 0 else 1.0
        descuento_pct = (1 - precio / orig) * 100 if orig > precio > 0 else 0
        orig_price = orig

        # Comparar descuento con distribución de descuentos de la categoría
        st_desc = stats_descuentos.get(cat)
        descuento_significativo = False
        if ratio > 1.0:
            if st_desc and st_desc.n >= 5 and st_desc.median > 1.05:
                # Significativo: supera la mediana de la categoría Y el descuento es
                # al menos Q3. Criterio preferido — categoría con muestra suficiente.
                threshold = max(st_desc.median, 1.25)
                descuento_significativo = ratio >= threshold
            elif stats_desc_global and stats_desc_global.n >= 5:
                # Fallback global: la categoría no tiene muestra suficiente —
                # usar la distribución global de descuentos de toda la corrida (R6).
                threshold_global = max(stats_desc_global.median, 1.25)
                if stats_desc_global.median > 1.05 and ratio >= threshold_global:
                    descuento_significativo = True
                n_fallback_global += 1
            # else: ni categoría ni global tienen muestra suficiente → False (conservador)

        # Calidad de la estadística: baja si CV muy alto (categoría con precios dispersos)
        stat_quality = 'alta' if st.cv < 40 else ('media' if st.cv < 80 else 'baja')

        # Incluir categoría refinada por ML si fue modificada
        cat_refinada = p.get('categoria') if p.get('ml_cat_conf') else None

        scores[pid] = {
            # Scores
            'pctil':        pct,
            'composite':    composite,
            'zScore':       round(z, 2),
            'mzScore':      round(mz, 2),
            # Segmentación
            'segment':      segment,
            'cheapOutlier': cheap_outlier,
            'expOutlier':   exp_outlier,
            # Descuento
            'ratio':        round(ratio, 3),
            'descuentoPct': round(descuento_pct, 1),
            'descuentoSig': descuento_significativo,
            'origPrice':    orig_price,
            # Histórico
            'tendenciaPrecio': trend,
            # Categoría refinada por modelo ML (None si no fue modificada)
            'categoriaML': cat_refinada,
            'catMLConf':   round(p.get('ml_cat_conf', 0.0), 3),
            'priceVelocity':   velocity,
            'histLow':         hist_low,
            'histMin':         hist_min,
            'histMax':         hist_max,
            'histN':           len(hist_pts),
            # Metadata
            'rubro':        rubro,
            'statQuality':  stat_quality,
            'statN':        st.n,
            # Placeholder para badge (se asigna después)
            'badge': '',
            # Atributos visuales (PR4) — poblados en la etapa 1b SOLO cuando
            # needs_image_fallback() dispara para este producto; el default
            # '' / 0.0 se mantiene sin cambios para los que nunca pasan por
            # la clasificación de imagen (invariante texto-gana).
            'fit':        '',
            'print':      '',
            'neckline':   '',
            'color':      '',
            'generoML':   '',
            'genImgConf': 0.0,
        }

    # Logging de validación R6.4: uso del fallback global de descuentos
    if stats_desc_global:
        print(f"[ML] descuentos: fallback global usado en {n_fallback_global} productos "
              f"(median_global={stats_desc_global.median:.3f}, n={stats_desc_global.n})",
              file=sys.stderr)
    else:
        print(f"[ML] descuentos: sin muestra global suficiente para fallback "
              f"(n_total_descuentos={len(all_descuentos)})", file=sys.stderr)

    # ─── 5. Clustering TF-IDF con bigrams ────────────────────────────────────
    print("[ML] Clustering TF-IDF...", file=sys.stderr)
    nombres_clustering = [
        (p.get('marca', '') + ' ' + p.get('nombre', '')).strip()
        for p in productos
    ]
    cluster_ids   = cluster_productos(nombres_clustering)
    cluster_sizes = defaultdict(int)
    for cid in cluster_ids: cluster_sizes[cid] += 1

    # threshold_alta recalibrado: relativo a la distribución real de cluster_sizes de
    # esta corrida (no a len(productos)), para que el badge 'tendencia' sea alcanzable
    # con clustering greedy/incremental (R8).
    sizes = sorted(cluster_sizes.values(), reverse=True)
    if sizes:
        import statistics
        p90 = sizes[max(0, int(len(sizes) * 0.10) - 1)]   # ~percentil 90 de tamaños de cluster
        median_size = statistics.median(sizes)
        threshold_alta = max(8, min(p90, median_size * 3))
    else:
        median_size = 0
        threshold_alta = 8

    for i, p in enumerate(productos):
        pid = p.get('url', '') or p.get('nombre', '')
        cid = cluster_ids[i] if i < len(cluster_ids) else -1
        scores[pid]['clusterId']    = cid
        scores[pid]['clusterSize']  = cluster_sizes.get(cid, 1)
        scores[pid]['altaDemanda']  = cluster_sizes.get(cid, 1) >= threshold_alta

    # Logging de validación R8.3: threshold_alta efectivo + clusters/productos afectados
    n_clusters_alta = sum(1 for sz in sizes if sz >= threshold_alta)
    n_prods_alta    = sum(1 for v in scores.values() if v.get('altaDemanda'))
    print(f"[ML] threshold_alta efectivo={threshold_alta} "
          f"(clusters={len(sizes)}, max={sizes[0] if sizes else 0}, mediana={median_size}, "
          f"clusters_sobre_umbral={n_clusters_alta}, productos_altaDemanda={n_prods_alta})",
          file=sys.stderr)

    # ─── 1b. Refinar categorías con modelo texto+imagen (ensemble) ─────────────
    # PR4: la inferencia de imagen bespoke (MobileNetV3/EfficientNet) fue
    # reemplazada por ml_embeddings.py (Marqo-FashionSigLIP, zero-shot).
    # `ml_embeddings` se importa acá (no al tope del módulo) para que
    # ml_pipeline.py siga siendo importable/testeable sin torch/open_clip
    # instalados — mismo criterio de import perezoso que el resto del
    # pipeline (ver load_trained_models). Un import fallido degrada esta
    # etapa entera a "solo texto", idéntico al comportamiento previo cuando
    # no había modelo de imagen disponible.
    try:
        import ml_embeddings
    except ImportError as e:
        ml_embeddings = None
        print(f"[ML] ml_embeddings no disponible, clasificación de imagen deshabilitada: {e}",
              file=sys.stderr)

    if text_model is not None or ml_embeddings is not None:
        print("[ML] Refinando categorias con ensemble texto+imagen...", file=sys.stderr)
        genericas = GENERICAS

        MAX_IMG_INFERENCES = 400

        # Pre-calcular predicciones de texto para todos los productos
        txt_preds = [predict_category(text_model, label_enc,
                                      (p.get('nombre') or '').lower())
                     for p in productos]

        # Identificar candidatos a inferencia de imagen — gate: needs_image_fallback()
        # (T4.1/T4.2): confianza de texto baja, categoría genérica, O género en
        # blanco (nuevo en PR4). Nunca se re-cuestiona una categoría de texto
        # específica y confiada con género presente (invariante texto-gana).
        img_candidates = [
            i for i, (p, (tc, tf)) in enumerate(zip(productos, txt_preds))
            if ml_embeddings is not None
            and needs_image_fallback(tf, p.get('categoria'), p.get('genero'), genericas)
            and (p.get('img') or p.get('imagenUrl') or '')
        ]
        if len(img_candidates) > MAX_IMG_INFERENCES:
            # Priorizar los de menor confianza de texto
            img_candidates.sort(key=lambda i: txt_preds[i][1])
            img_candidates = img_candidates[:MAX_IMG_INFERENCES]

        # Embeber + clasificar + extraer color. El color NUNCA se cachea (solo
        # el embedding, en image_embeddings) — cada URL candidata se descarga
        # una vez y se reusa para el embedding (en cache miss, vía
        # preloaded_images) y para dominant_color(), mismo patrón que
        # ml_embeddings.backfill().
        img_results = {}
        if img_candidates:
            candidate_urls_by_idx = {
                i: (productos[i].get('img') or productos[i].get('imagenUrl') or '')
                for i in img_candidates
            }
            distinct_urls = list(dict.fromkeys(candidate_urls_by_idx.values()))
            images = {}
            for url in distinct_urls:
                try:
                    images[url] = ml_embeddings._download_image(url)
                except Exception as e:
                    print(f"[ML] Descarga de imagen fallida para {url}: {e}", file=sys.stderr)
                    images[url] = None

            embeddings = ml_embeddings.embed_images(
                distinct_urls, db_path=db_path_hint,
                model_version=ml_embeddings.MODEL_VERSION,
                preloaded_images=images,
            )

            for i in img_candidates:
                url = candidate_urls_by_idx[i]
                try:
                    embedding = embeddings.get(url)
                    attrs = ml_embeddings.classify(embedding, db_path=db_path_hint)
                    color = ''
                    image = images.get(url)
                    if image is not None:
                        try:
                            color = ml_embeddings.dominant_color(image)
                        except Exception as e:
                            print(f"[ML] dominant_color fallido para {url}: {e}", file=sys.stderr)
                    img_results[i] = (attrs, color)
                except Exception as e:
                    print(f"[ML] Clasificación de imagen fallida para producto {i}: {e}", file=sys.stderr)
            print(f"[ML] Inferencia imagen: {len(img_results)}/{len(img_candidates)} OK "
                  f"({len(img_candidates)} candidatos de {len(productos)} productos)",
                  file=sys.stderr)

        for idx, p in enumerate(productos):
            nombre     = (p.get('nombre') or '').lower()
            cat_actual = (p.get('categoria') or '').strip()
            pid        = p.get('url', '') or p.get('nombre', '')

            # Guard dedicado (ADR-5): "Conjunto" (combo/multi-pieza, ver
            # NormalizerService.clasificar()) nunca debe ser re-clasificado por
            # el ensemble ML. NO se agrega "conjunto" al set `genericas` de
            # arriba: ese set, usado en la condición de L925 (`cat_actual.lower()
            # in genericas or confianza >= 0.92`), funciona como "más facil de
            # sobreescribir" (salta el umbral de alta confianza), no como
            # "protegido contra sobreescritura" — agregarlo ahi haria a
            # "Conjunto" MAS propenso a ser reclasificado, el efecto opuesto al
            # que pide ADR-5. Por eso el guard vive aca, antes de cualquier
            # predicción/aplicación, y se salta el producto incondicionalmente.
            if cat_actual.lower() == 'conjunto':
                continue

            # 1) Predicción de texto
            txt_cat, txt_conf = txt_preds[idx]

            # 2) Predicción de imagen (resultado pre-calculado) — atributos
            # visuales nuevos (PR4): fit/print/neckline/color/generoML/
            # genImgConf. Additivos, sin gate de texto (el texto no predice
            # estos signals, así que no hay nada que "ganarles"). `categoria`
            # de imagen SÍ sigue el resto del gate de abajo (paso 4) — nunca
            # sobreescribe una categoría de texto específica y confiada.
            attrs, color = img_results.get(idx, (None, ''))
            img_cat, img_conf = ('', 0.0)
            if attrs is not None:
                img_cat  = attrs.get('categoria', '') or ''
                img_conf = attrs.get('catMLConf', 0.0) or 0.0
                if img_cat and _TEXT_LABEL_SET and img_cat not in _TEXT_LABEL_SET:
                    img_cat, img_conf = '', 0.0

                scores[pid]['fit']        = attrs.get('fit', '')
                scores[pid]['print']      = attrs.get('estampado', '')
                scores[pid]['neckline']   = attrs.get('escote', '')
                scores[pid]['color']      = color
                scores[pid]['generoML']   = attrs.get('genero', '')
                scores[pid]['genImgConf'] = attrs.get('genImgConf', 0.0)

            # 3) Elegir la mejor predicción
            if img_cat and img_conf > txt_conf:
                pred_cat, confianza = img_cat, img_conf
            elif txt_cat:
                pred_cat, confianza = txt_cat, txt_conf
            else:
                continue

            # 4) Aplicar solo si es seguro — jerarquía + tipos incompatibles
            if pred_cat != cat_actual and confianza >= 0.82:
                # Tipos mutuamente excluyentes — nunca cruzar entre ellos
                TIPOS = {
                    'calzado':    {'zapatilla','zapatilla running','zapatilla urbana',
                                   'zapatilla entrenamiento','zapatilla skate','sneaker',
                                   'botines','botas','ojotas','borcego','sandalia',
                                   'mocasin','zapato','pantufla'},
                    'superior':   {'remera','musculosa','camisa','chomba','casaca','sweater',
                                   'buzo','campera','puffer','piloto','chaleco','saco',
                                   'traje','remeron','corpino','malla'},
                    'inferior':   {'jean','pantalon','jogging','short','bermuda','calza',
                                   'baggy','pollera','shorts','vestido','enterito'},
                    'interior':   {'calzoncillos','corpino','malla'},
                    'accesorios': {'gorra','gorro','mochila','bolso','billetera','rinonera',
                                   'riñonera','medias','cinturon','cinturón','bufanda',
                                   'guantes','lentes','tarjetero','bandolera'},
                    'tech':       {'notebook','monitor','gabinete','gpu','ram','teclado',
                                   'pc','auricular','mouse','cpu','cooling'},
                    'nutricion':  {'suplemento','alimentos','perfumes','perfume','perfumina'},
                }
                def tipo_de(cat):
                    c_l = cat.lower().replace('ó','o').replace('á','a').replace('ú','u')
                    for tipo, cats in TIPOS.items():
                        if c_l in cats: return tipo
                    return 'generico'

                tipo_actual = tipo_de(cat_actual)
                tipo_pred   = tipo_de(pred_cat)

                # Bloquear cruce entre tipos específicos (remera→zapatilla, ojota→remera, etc.)
                if tipo_actual != 'generico' and tipo_pred != 'generico' and tipo_actual != tipo_pred:
                    continue

                # No downgrade: específico → padre genérico del mismo tipo
                CAT_PADRES = {
                    'zapatilla running':'zapatilla','zapatilla urbana':'zapatilla',
                    'zapatilla entrenamiento':'zapatilla','zapatilla skate':'zapatilla',
                    'sneaker':'zapatilla','ojotas':'zapatilla',
                    'botines':'calzado','botas':'calzado',
                    'jean':'pantalon','jogging':'pantalon','baggy':'pantalon',
                }
                parent_actual = CAT_PADRES.get(cat_actual.lower(), '')
                if parent_actual and pred_cat.lower() == parent_actual:
                    continue  # nunca Ojotas→Zapatilla ni Running→Zapatilla

                # Solo aplicar si la categoría actual es genérica O confianza muy alta
                if cat_actual.lower() in genericas or confianza >= 0.92:
                    p['categoria_original'] = cat_actual
                    p['categoria']   = pred_cat
                    p['ml_cat_conf'] = round(confianza, 3)
                    ml_refinements  += 1
        print(f"[ML] Categorias refinadas (texto+imagen): {ml_refinements}", file=sys.stderr)

    # ─── 6. Asignación de badges con lógica estadística ──────────────────────
    print("[ML] Asignando badges estadísticos...", file=sys.stderr)
    badge_counts = defaultdict(int)

    for p in productos:
        pid  = p.get('url', '') or p.get('nombre', '')
        s    = scores[pid]
        comp = s['composite']
        mz   = s['mzScore']
        alta = s['altaDemanda']
        hist = s['histLow']
        trend = s['tendenciaPrecio']
        cheap = s['cheapOutlier']
        exp   = s['expOutlier']
        ratio = s['ratio']
        desc_sig = s['descuentoSig']
        desc_pct = s['descuentoPct']
        orig_p   = s['origPrice']
        hist_n   = s['histN']

        # Guard de oferta cosmética: si ya tenemos suficiente historial de precio
        # PROPIO de este producto (≥3 puntos), el descuento solo cuenta como real
        # si el precio resultante está cerca de su mínimo histórico (hist=histLow,
        # ya calculado con tolerancia 5%). Sin esto, un "precio original" inflado
        # recién antes del descuento pasaba como oferta real apenas la categoría
        # tuviera buenos descuentos en general — sin chequear contra el propio
        # historial de ESTE producto. Si no hay historial suficiente (producto
        # nuevo), no se penaliza: se conserva el criterio estadístico de categoría.
        no_es_cosmetica_vs_historial = (hist_n < 3) or hist

        # OFERTA REAL: descuento estadísticamente significativo Y precio competitivo
        # Condiciones:
        #   - Ratio dentro de rango sano (1.20 a 3.5)
        #   - Precio original real (> 0)
        #   - Descuento de al menos 20%
        #   - Precio post-descuento en mitad inferior del mercado
        #   - Descuento supera la mediana de descuentos de la categoría (o fallback global)
        #   - No contradice el historial de precio propio del producto (ver guard arriba)
        es_oferta_real = (ratio > 0
              and 1.20 <= ratio <= 3.5        # mínimo 20% de descuento real
              and orig_p > 0
              and desc_pct >= 20              # al menos 20% de descuento
              and comp <= 40                  # precio resultante en cuartil inferior
              and desc_sig                    # supera la mediana de descuentos de la categoría
              and no_es_cosmetica_vs_historial # no contradice el historial propio
              and not (isinstance(ratio, float) and (ratio != ratio)))
        scores[pid]['ofertaReal'] = bool(es_oferta_real)
        # NOTA: ofertaReal es un campo independiente del badge mostrado. Un producto
        # con ofertaReal=True puede recibir un badge distinto a 'oferta_real' si también
        # califica para un badge de mayor prioridad en la cadena de abajo (por ej.
        # 'precio_historico_bajo' o 'precio_bajo'). Esto es comportamiento preexistente
        # y esperado (prioridad de badges), no una inconsistencia a corregir.

        badge = ''

        # PRECIO BAJO HISTÓRICO: precio actual es mínimo histórico (requiere historial)
        if hist and comp <= 35:
            badge = 'precio_historico_bajo'

        # PRECIO BAJO ESTADÍSTICO: outlier inferior de Tukey O z-score < -1.5
        elif cheap or (comp <= 20 and mz <= -1.5):
            badge = 'precio_bajo'

        # OFERTA REAL: ver cálculo de es_oferta_real arriba
        elif es_oferta_real:
            badge = 'oferta_real'

        # TENDENCIA: cluster de alta demanda Y precio razonable
        elif alta and comp <= 65:
            badge = 'tendencia'

        # PRECIO BAJANDO: tendencia histórica a la baja
        elif trend == 'bajando' and comp <= 40:
            badge = 'precio_bajando'

        # PRECIO ALTO ESTADÍSTICO: outlier superior de Tukey O z-score > +1.5
        elif exp or (comp >= 80 and mz >= 1.5):
            badge = 'precio_alto'

        # DESCUENTO COSMÉTICO: hay "oferta" pero el descuento es irrelevante
        elif ratio > 1.0 and desc_pct < 12:
            badge = 'descuento_cosmetico'

        scores[pid]['badge'] = badge
        if badge: badge_counts[badge] += 1

    print("[ML] Badges asignados:", dict(badge_counts), file=sys.stderr)

    # Logging de validación R9: consistencia ofertaReal == True vs badge oferta_real
    n_oferta_real = sum(1 for v in scores.values() if v.get('ofertaReal'))
    print(f"[ML] ofertaReal=True en {n_oferta_real} productos "
          f"(badge oferta_real={badge_counts.get('oferta_real', 0)})", file=sys.stderr)

    # Logging de validación: precios originales no parseables (rechazados a 0.0)
    n_orig_cero = sum(1 for p in productos
                       if (p.get('precioOriginal') or '').strip()
                       and safe_price(p.get('precioOriginal', '')) == 0.0)
    print(f"[ML] safe_price: {n_orig_cero}/{len(productos)} precioOriginal no parseables "
          f"(rechazados a 0.0)", file=sys.stderr)

    # ─── 7. Tendencias globales ───────────────────────────────────────────────
    cat_counts  = defaultdict(int)
    cat_prices  = defaultdict(list)
    for p in productos:
        cat = (p.get('categoria') or 'General').strip() or 'General'
        cat_counts[cat] += 1
        cat_prices[cat].append(p.get('precio', 0))

    cat_stats_output = []
    for cat, cnt in sorted(cat_counts.items(), key=lambda x: -x[1])[:12]:
        st = stats_cats.get(cat, PriceStats([]))
        cat_stats_output.append({
            'categoria': cat,
            'count':     cnt,
            'avgPrecio': round(st.mean),
            'medPrecio': round(st.median),
            'q1':        round(st.q1),
            'q3':        round(st.q3),
            'cv':        round(st.cv, 1)
        })

    # Top productos: variedad real — no solo baratos
    def make_prod_dict(p, badge_override=None):
        pid = p.get('url','') or p.get('nombre','')
        sc  = scores.get(pid, {})
        return {
            'url':       p.get('url',''),
            'nombre':    p.get('nombre',''),
            'precio':    p.get('precio',0),
            'img':       p.get('img','') or p.get('imagenUrl',''),
            'sitio':     p.get('sitio',''),
            'marca':     p.get('marca',''),
            'composite': sc.get('composite',50),
            'badge':     badge_override or sc.get('badge',''),
            'segment':   sc.get('segment','standard'),
            'categoria': p.get('categoria',''),
        }

    # 1. Mínimos históricos
    hist_bajos = [make_prod_dict(p) for p in productos
        if scores.get(p.get('url','') or p.get('nombre',''),{}).get('badge') == 'precio_historico_bajo'][:4]

    # 2. Ofertas reales (descuento significativo)
    ofertas = [make_prod_dict(p) for p in productos
        if scores.get(p.get('url','') or p.get('nombre',''),{}).get('badge') == 'oferta_real'][:4]

    # 3. Budget: menor composite por categoría (1 por categoría)
    seen_cats_budget = set()
    budget_picks = []
    for p in sorted(productos,
            key=lambda p: scores.get(p.get('url','') or p.get('nombre',''),{}).get('composite',50)):
        cat = p.get('categoria','')
        if cat and cat not in seen_cats_budget:
            seen_cats_budget.add(cat)
            budget_picks.append(make_prod_dict(p))
            if len(budget_picks) >= 5: break

    # 4. Premium: mayor composite en rango premium (1 por categoría)
    seen_cats_prem = set()
    premium_picks = []
    for p in sorted(productos,
            key=lambda p: -scores.get(p.get('url','') or p.get('nombre',''),{}).get('composite',50)):
        pid = p.get('url','') or p.get('nombre','')
        sc  = scores.get(pid,{})
        cat = p.get('categoria','')
        if sc.get('segment') in ('premium','standard') and 40 <= sc.get('composite',50) <= 75:
            if cat and cat not in seen_cats_prem:
                seen_cats_prem.add(cat)
                premium_picks.append(make_prod_dict(p))
                if len(premium_picks) >= 4: break

    top_prods_full = hist_bajos + ofertas + budget_picks + premium_picks

    # Clusters con bigrams
    stop_label = {'de','la','el','los','las','un','una','para','con','en','y','a',
                  'talle','color','negro','blanco','gris','azul','rojo','verde',
                  'hombre','mujer','unisex'}
    cluster_bigrams = defaultdict(list)
    for i, p in enumerate(productos):
        cid    = cluster_ids[i] if i < len(cluster_ids) else -1
        nombre = p.get('nombre', '')
        words  = [w.lower() for w in nombre.split()
                  if len(w) > 2 and w.lower() not in stop_label]
        for j in range(len(words) - 1):
            cluster_bigrams[cid].append(f"{words[j].capitalize()} {words[j+1].capitalize()}")
        if words:
            cluster_bigrams[cid].append(words[0].capitalize())

    def _label_key(label):
        """Bag-of-words normalizado para deduplicar labels que sólo difieren en
        orden de palabras o capitalización (R7)."""
        return frozenset(
            w for w in re.sub(r'[^\w\s]', '', label.lower()).split()
            if w not in stop_label and len(w) > 2
        )

    candidatos_considerados = 0
    trending_clusters = []
    seen_keys = set()
    for cid, size in sorted(cluster_sizes.items(), key=lambda x: -x[1])[:20]:
        labels = cluster_bigrams.get(cid, [])
        if not labels: continue
        candidatos_considerados += 1
        label_counts = sorted(set(labels), key=lambda l: -labels.count(l))
        label, key = None, None
        for cand in label_counts:
            k = _label_key(cand)
            if k and k not in seen_keys:        # k vacío (sólo stopwords) → descartar
                label, key = cand, k
                break
        if label is None: continue
        seen_keys.add(key)
        trending_clusters.append({'cluster': cid, 'label': label, 'size': size})
        if len(trending_clusters) >= 10: break

    # Logging de validación: dedup de clusters (R7, §7)
    print(f"[ML] trending_clusters: {len(trending_clusters)} únicos de "
          f"{candidatos_considerados} candidatos", file=sys.stderr)

    # Stats globales del dataset
    all_prices = [p.get('precio', 0) for p in productos if p.get('precio', 0) > 0]
    global_stats = PriceStats(all_prices).to_dict() if all_prices else {}

    # ─── 8. Output ────────────────────────────────────────────────────────────
    output = {
        'scores': scores,
        'globalStats': global_stats,
        'categoriaStats': {cat: stats_cats[cat].to_dict()
                           for cat in list(cats_precios.keys())[:20]},
        'tendencias': {
            'categoriaStats':   cat_stats_output,
            'topProductos':     top_prods_full,
            'trendingClusters': trending_clusters,
            'totalProductos':   len(productos),
            'badgeCounts':      dict(badge_counts),
            'fecha':            today
        }
    }

    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    if hist_path:
        with open(hist_path, 'w', encoding='utf-8') as f:
            json.dump(history, f, ensure_ascii=False)

    print(f"[ML] Output → {out_path}", file=sys.stderr)


if __name__ == '__main__':
    main()
