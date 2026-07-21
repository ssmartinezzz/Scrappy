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
      "composite": 28,
      "zScore": -0.82,
      "mzScore": -0.70,
      "segment": "budget",
      "badge": "below_market",
      "badges": ["below_market", "trending"],
      "ofertaReal": true,
      "descuentoSig": true,
      "descuentoPct": 27.5,
      "tendenciaPrecio": "bajando",
      "histLow": true,
      "priceVelocity": -0.03,
      "rubro": "moda"
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

> Actualizado por `badges-oportunidades-revamp`. Los badges son **multi-badge, no
> exclusivos**: cada producto puede calificar para varios a la vez (condiciones
> independientes, no una cadena `elif`). Se persisten en `productos.ml_badge` como
> TEXT comma-delimited, **principal primero** (p.ej. `verified_deal,trending`). El
> orden de prioridad de abajo define cuál es el "principal" (`badge`) y el resto va
> en el set completo (`badges[]`). Las keys viejas (`precio_bajo`, `oferta_real`,
> `tendencia`, `descuento_cosmetico`, `precio_alto`) **ya no existen**.

Orden de prioridad (principal = primero del set), tal como `assign_badges()` en
`ml_pipeline.py` (~línea 454):

| Badge | Label UI | Condición (resumen) |
|-------|----------|---------------------|
| `all_time_low` | Mínimo histórico | precio actual = mínimo histórico propio del producto |
| `below_market` | Por debajo del mercado | precio unitario claramente bajo el mercado de su categoría (pctil/z-score modificado) |
| `verified_deal` | Descuento verificado | descuento significativo Y precio en la mitad inferior del mercado |
| `trending` | En demanda | pertenece a un cluster TF-IDF con demanda alta |
| `price_dropping` | Bajando de precio | tendencia de precio a la baja (`priceVelocity` negativa) |
| `above_market` | Caro vs. mercado | precio unitario por encima del mercado de su categoría |
| `fake_discount` | Descuento dudoso | descuento cosmético / no verificado contra historial propio |

`ofertaReal` es un **boolean aparte e independiente** del set de badges (spec
"ofertaReal Boolean Independence") — nunca se deriva del badge mostrado, aunque en
la práctica un producto con `ofertaReal=true` normalmente también tiene
`verified_deal` en su set.

---

## Cómo agregar un nuevo badge

Los badges son un **set independiente**, no una cadena `elif`. Cada condición se
evalúa por separado y se hace `append` al set; el orden de `BADGE_PRIORITY` define
el principal.

### 1. Definir la condición en `ml_pipeline.py`

```python
# En assign_badges() (~línea 464): agregá un append INDEPENDIENTE, no un elif.
# La posición dentro de la función no importa para la prioridad — eso lo fija
# BADGE_PRIORITY (~línea 460); reordená ahí si querés cambiar cuál es principal.
def assign_badges(hist, comp, mz, cheap, alta, trend, exp, ratio, desc_pct, es_oferta_real):
    badges = []
    # ... condiciones existentes ...
    if mi_condicion:
        badges.append('mi_badge')
    return badges
```

Sumá `'mi_badge'` a `BADGE_PRIORITY` en la posición de prioridad que quieras.
El campo se propaga solo: `scores[pid]['badges']` (set completo) y
`scores[pid]['badge']` (principal = `badges[0]`), persistido en
`productos.ml_badge` comma-delimited.

### 2. Agregar el label en el frontend (React/Vite)

El frontend es React en `frontend/`, **no** `index.html`. Los labels y estilos de
badge viven en los componentes/constantes de `frontend/src/` (buscá el mapa de
labels de badge y la clase/variante correspondiente). Agregá tu key `mi_badge`
ahí con su label y color; `/api/data?badge=mi_badge` ya filtra por pertenencia al
set sin cambios de backend.

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
(detectado por `ar.scraper.aggregator.normalize.PackQuantityDetector.detectar(nombre, categoria)`,
invocado por `NormalizerService` como parte de su orquestación de normalización;
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

Se sincroniza con la tabla `precio_historico` en PostgreSQL.

**Actualización (decouple-services-postgres, Batch 2, design D4)**: desde este cambio, Python SÍ tiene acceso directo a la base — `ml_pipeline.py`/`ml_embeddings.py`/`ml_train.py` conectan vía `psycopg2` usando el env var `DATABASE_URL` (el mismo `DATABASE_URL` que usa Java para `spring.datasource.url`, pero traducido de formato JDBC a DSN libpq por `PythonRunner.toPsycopgDsn` antes de pasarlo al subproceso — psycopg2 no entiende el prefijo `jdbc:`). Ya no hay un `db_path` posicional ni un archivo `scraper.db` que resolver: `PythonRunner` fija `DATABASE_URL`/`SCRAPER_MODELS_ROOT`/`HF_HOME` como variables de entorno del subproceso (design D5).

**Purga automática**: entradas > 90 días se eliminan en cada run.
