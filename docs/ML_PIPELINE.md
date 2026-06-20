# Pipeline ML

## Flujo completo

```
Java: ResultAggregator.agregar()
    ↓ MlEnricher.serializarProductos() → ml_productos.json
    ↓ PythonRunner.ejecutar()
        → extrae ml_pipeline.py del JAR (si no existe)
        → subprocess: python ml_pipeline.py ml_productos.json ml_output.json precio_historico.json
    ↓ MlEnricher.enriquecer() → Product.MlScore populated
    ↓ DatabaseService.guardarMlOutput()
```

---

## Archivos generados en el directorio del .jar

| Archivo | Descripción |
|---------|-------------|
| `ml_pipeline.py` | Script extraído del JAR (no editar aquí, editar en `src/main/resources/ml/`) |
| `ml_productos.json` | Input: lista de productos normalizados |
| `ml_output.json` | Output: scores por URL + tendencias globales |
| `precio_historico.json` | Historial de precios acumulado entre runs |

---

## Estructura de `ml_output.json`

```json
{
  "scores": {
    "https://producto-url": {
      "pctil": 23,
      "badge": "precio_bajo",
      "ofertaReal": false,
      "descuentoCosmetico": false,
      "altaDemanda": true,
      "clusterId": 5,
      "clusterSize": 183,
      "tendenciaPrecio": "bajando"
    }
  },
  "tendencias": {
    "categoriaStats": [
      {"categoria": "Zapatillas", "count": 1173, "avgPrecio": 129837}
    ],
    "topProductos": [
      {"url": "...", "nombre": "...", "precio": 2999, "img": "...", "marca": "Nike"}
    ],
    "trendingClusters": [
      {"cluster": 5, "label": "Remera Nike", "size": 183}
    ],
    "totalProductos": 3034,
    "fecha": "2026-05-29"
  }
}
```

---

## Badges disponibles

| Badge | Condición | Color UI |
|-------|-----------|----------|
| `precio_bajo` | percentil ≤ 20 en su categoría, o precio bajando y pctil ≤ 25 | 💚 Verde |
| `oferta_real` | descuento ≥ 25% Y precio en mitad inferior del mercado | ✅ Púrpura |
| `tendencia` | cluster con ≥ 3% del catálogo total | 🔥 Naranja |
| `descuento_cosmetico` | descuento < 13% | ⚠️ Gris |
| `precio_alto` | percentil ≥ 80 | 📈 Rosa |

---

## Cómo agregar un nuevo badge

### 1. Definir la condición en `ml_pipeline.py`

```python
# En la sección "Asignar badge final" (al final del main())
# Agregar ANTES del badge existente de mayor prioridad

elif s.get('mi_condicion', False):
    badge = 'mi_badge'
```

### 2. Agregar label en `index.html`

```javascript
const BADGE_LABELS_SIDEBAR = {
  // ... existentes ...
  'mi_badge': '🎯 Mi Label'
};
```

### 3. Agregar CSS en `index.html`

```css
.badge-mi_badge {
  background: rgba(X,Y,Z,.15);
  color: #HEXCOLOR;
  border: 1px solid rgba(X,Y,Z,.3)
}
.ml-badge-btn.active.b-mi_badge { /* mismo */ }
```

---

## Cómo agregar un nuevo modelo

### Agregar campo al `scores` dict en `ml_pipeline.py`

```python
# En el main(), después del paso de clustering:

# ─── 6b. Mi nuevo modelo ──────────────
print("[ML] Mi modelo...", file=sys.stderr)
for p in productos:
    pid = p.get('url','') or p.get('nombre','')
    # Tu lógica aquí
    scores[pid]['mi_campo'] = True/False/valor
```

### Consumir en Java (`MlEnricher.java`)

```java
Product.MlScore ml = new Product.MlScore(
    s.path("pctil").asInt(50),
    s.path("badge").asText(""),
    s.path("ofertaReal").asBoolean(false),
    s.path("tendenciaPrecio").asText("estable"),
    s.path("pctil").asInt(50)
    // Para campos extra, agregar al record Product.MlScore primero
);
```

---

## Cómo mejorar el clustering

El clustering actual es TF-IDF greedy con threshold de similitud 0.25. Para mejorarlo:

```python
# En cluster_productos(), cambiar threshold:
def cluster_productos(nombres, threshold=0.30):  # más alto = clusters más pequeños/precisos
```

```python
# Para usar bigrams más agresivamente, ajustar stop_label en el labeling:
stop_label = {'de','la',...}  # agregar más palabras irrelevantes
```

El clustering NO usa dependencias externas para mantener la instalación simple. Si en el futuro se quiere sklearn, agregar al `.bat`:
```bat
python -m pip install scikit-learn
```

---

## Precio por unidad (pack/combo pricing detection)

Desde `pack-pricing-detection`, cada producto trae un campo `cantidadUnidades`
(detectado por `NormalizerService.detectarCantidadUnidades()` sobre `nombre`;
default `1` si no es un pack/combo). `ml_pipeline.py` calcula:

```python
def precio_unitario(p):
    unidades = max(1, int(p.get('cantidadUnidades', 1) or 1))
    precio   = p.get('precio', 0)
    return precio / unidades if unidades > 1 else precio
```

`precio_unitario(p)` sustituye a `precio` en **todo** el agrupamiento y scoring
por producto: `grupos_precios`, `cats_precios`, y los cálculos de
`percentile_rank` / `z_score` / `z_score_modified` / `composite_score` /
`price_segment` / outliers de Tukey por producto. Esto evita que un "Pack x3
Remeras $15000" se compare contra remeras individuales como si costara $15000
cada una — se compara correctamente como ~$5000 por unidad.

**Lo que NO cambia** (sigue usando precio de estantería, no precio unitario):
- Display (`precio` crudo en el output, lo que ve el usuario).
- Cálculo de descuento (`precioOriginal` / `precio`, `descuentoPct`, `descuentoSig`).
- Historial de precios (`precio_historico.json`) — trackea el precio de
  estantería del producto, no el precio por unidad.
- `tendencias.categoriaStats` (panel de tendencias) — usa `cat_prices`/`cat_stats_output`,
  una agregación separada de `cats_precios`, intencionalmente en precio crudo.

Cuando `cantidadUnidades` está ausente o es `1` (caso default, la inmensa
mayoría del catálogo), `precio_unitario(p) == precio` exactamente — sin cambio
de comportamiento para productos de unidad simple.

### Riesgo conocido — monitorear distribución por categoría

Sustituir precio por precio-unitario desplaza la posición de un pack dentro de
la distribución de su categoría. Si una categoría tiene **alta densidad de
packs** (muchos productos multi-unidad sobre pocas muestras), la mediana/IQR de
esa categoría puede correrse, lo que también perturba levemente los scores de
los productos de unidad simple en esa misma categoría (comparten la misma
`PriceStats`). Esto es un efecto esperado del diseño, no un bug — pero amerita
monitoreo post-deploy: si en una categoría con muchos packs el badge
`precio_alto`/`oferta_real` empieza a verse inconsistente, revisar la
distribución `cats_precios` de esa categoría antes de re-calibrar umbrales.
Re-calibrar thresholds está **fuera de alcance** de este change — ver
`CLAUDE.md` → "Problemas conocidos / pendientes".

---

## Historial de precios

El archivo `precio_historico.json` acumula cambios de precio por URL. Estructura:
```json
{
  "https://url-producto": [
    {"fecha": "2026-05-20", "precio": 15990},
    {"fecha": "2026-05-28", "precio": 14990}
  ]
}
```

Se sincroniza con la tabla `precio_historico` en SQLite. Ambos se mantienen en paralelo porque el script Python no tiene acceso directo a la DB.

**Purga automática**: entradas > 90 días se eliminan en cada run.
