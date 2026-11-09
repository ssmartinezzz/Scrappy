# Pipeline ML

## Flujo completo

```
Java: ResultAggregator.agregar()
    ↓ MlEnricher.serializarProductos() → ml_productos.json
    ↓ PythonRunner.ejecutar()
        → extrae ml_pipeline.py del JAR (si no existe)
        → PrecioHistoricoExporter escribe ml_historial.json desde la tabla
        → subprocess: python ml_pipeline.py ml_productos.json ml_output.json ml_historial.json
        → borra ml_historial.json al terminar
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
| `ml_historial.json` | Input efímero: Java lo arma desde la tabla `precio_historico` antes de cada corrida y lo borra al terminar. El ML sólo lo lee |

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
| `all_time_low` | Mínimo histórico | precio actual = mínimo histórico propio del producto **y el precio se movió**: algún precio anterior es estrictamente mayor. Un historial plano (nunca cambió de precio) no es un mínimo, es sólo un precio |
| `below_market` | Por debajo del mercado | precio unitario claramente bajo el mercado de su categoría (pctil/z-score modificado) **y** corroborado por un movimiento real de precio: `all_time_low`, `verified_deal` o `price_dropping`. Sin eso el extremo barato lo dominan los mal clasificados (funda como Billetera, gorra de natación como Gorra) |
| `verified_deal` | Descuento verificado | descuento significativo Y precio en la mitad inferior del mercado Y, con ≥3 puntos de historial propio, el producto está en su mínimo histórico (mismo criterio que `all_time_low`). Un tachado que nunca cambió —oferta perpetua— no se verifica: en el catálogo del 2026-11-02 eso sacó 646 de 2111 |
| `trending` | En demanda | pertenece a un cluster TF-IDF con demanda alta |
| `price_dropping` | Bajando de precio | tendencia de precio a la baja (`priceVelocity` negativa) |
| `above_market` | Caro vs. mercado | precio unitario por encima del mercado de su categoría |
| `fake_discount` | Descuento dudoso | descuento cosmético / no verificado contra historial propio |

`ofertaReal` es un **boolean aparte e independiente** del set de badges (spec
"ofertaReal Boolean Independence") — nunca se deriva del badge mostrado, aunque en
la práctica un producto con `ofertaReal=true` normalmente también tiene
`verified_deal` en su set.

---

## Los thresholds no tienen escala — y eso es una invariante, no una casualidad

**Ninguna condición de `assign_badges` está denominada en pesos.** Todas son
posiciones sobre una distribución que se recalcula en cada corrida, por
categoría + género:

| Condición | Unidad |
|---|---|
| `comp <= 35 / 20 / 65 / 40`, `comp >= 80` | score compuesto 0-100 (40% percentil + 35% z-score modificado + 25% distancia a mediana/IQR) |
| `mz <= -1.5` / `mz >= 1.5` | z-score modificado sobre MAD |
| `cheap` / `exp` | cercos de Tukey, `q1 - 1.5*IQR` / `q3 + 1.5*IQR` |
| `desc_pct < 12` | porcentaje |
| `ratio > 1.0` | ratio |

**La consecuencia práctica**: cambiar qué productos entran al catálogo —subir
`precio.maximo`, agregar un sitio caro, sumar un rubro nuevo— corre las
distribuciones, y los badges se acomodan solos. **No hay recalibración.**

Se verificó ejercitando `PriceStats` y `assign_badges` reales sobre los precios
de INPRO medidos en vivo, escalando las distribuciones ×10, ×100, ×1000 y
×0,01: **352 combinaciones, cero cambios de badge** (88 sin corroboración y 264 con
ella: `below_market` sólo se asigna con mínimo histórico, oferta real o baja, y el barrido exige
que aparezca).
`ml-tests/test_ml_pipeline_scale_invariance.py` lo fija.

> ⚠️ **Al agregar un badge, mantené la propiedad.** Una condición como
> "`below_market` si está $50.000 por debajo de la mediana" parece razonable y
> rompe el pipeline en silencio: una remera de $30.000 y un standing desk de
> $2.400.000 conviven en la misma base, y un umbral en pesos significa cosas
> opuestas en cada una. Si necesitás una magnitud, expresala **relativa** —
> fracción del IQR, puntos de percentil, porcentaje de la mediana.

La única constante en pesos del archivo es el piso de `bin_size` en
`_calc_mode` (`max(5000, iqr/5)`). Es la excepción y está acotada: `mode` se
reporta en `to_dict()` y **no lo lee ningún score ni ningún badge**. Si algún
día entra al scoring, ese es el lugar a revisar.

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
- Historial de precios (tabla `precio_historico`) — trackea el precio de
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
`docs/KNOWN_ISSUES.md`.

---

## Parseo de `precioOriginal` — `safe_price` se borró

Desde `close-1nf-and-3nf-foundation` (design DD2), `ar.scraper.aggregator.text.PrecioParser`
(Java) es el único parser que corre sobre el string crudo que scrapea cada
sitio — al momento del scrape, no en este pipeline. `precioOriginal` llega
acá ya resuelto: un número o `None`, nunca el string original.

`safe_price` quedó con **cero callers** y **se borró**. Se la había dejado un
tiempo como "especificación de referencia" de la que `PrecioParser.java` y
`sp_parse_precio_ar` copiaban su contrato, y esa fue exactamente la trampa:
era la única de las tres implementaciones **fuera** de la cadena del fixture
compartido, o sea la única que podía romperse sin poner ningún test en rojo.
Una spec que nadie ejecuta no es una spec, es una copia esperando
desincronizarse. Hoy el contrato vive en `PrecioParser.java` y en
`sp_parse_precio_ar` (SQL, dentro de la migración `V17`), y los dos se
prueban contra el mismo fixture
(`scraper/src/test/resources/price-parser-cases.tsv`) para que no puedan
divergir en silencio — ni siquiera divergir de forma consistente hacia una
respuesta incorrecta: un bug real en la regla del punto único (exigir
parte entera chica para leer un decimal, en vez de bastar con que la parte
decimal tenga 1-2 dígitos) estaba clonado en las tres implementaciones y
sólo se detectó corriendo el parser contra el catálogo real. Corregido en
lockstep en las tres.

**Categoría de `categoriaStats` (`cats_precios`, línea ~680)**: la clave pasa
de `norm_cat((categoria or 'General').strip() or 'General')` a
`(categoria or '').strip() or 'Otros'` — la categoria CANÓNICA (V13, Title
Case), no la salida de `norm_cat`, porque `categoria_stats.categoria` tiene
FK a `categoria(nombre)` desde `V16` y ese FK nunca aceptó minúsculas.
`'Otros'` es el bucket de abstención de `V12`, que sí está en el canon —
`'general'` nunca lo estuvo. `norm_cat` sigue intacto para todo lo demás:
sigue siendo la clave de `grupos_precios`/`elegir_cat`, el scoring por
producto, un concern distinto.

---

## Historial de precios

La tabla `precio_historico` es el **único dueño** del historial de precios: guarda
CAMBIOS (ver [`DATABASE.md`](./DATABASE.md)). El ML no lleva estado propio.

Antes de cada corrida, `PrecioHistoricoExporter` (invocado por `PythonRunner`) arma
`ml_historial.json` desde la tabla, sólo para los productos que se van a puntuar:

```json
{
  "https://url-producto": [
    {"fecha": "2026-10-31", "precio": 15990},
    {"fecha": "2026-11-01", "precio": 15990},
    {"fecha": "2026-11-02", "precio": 14990}
  ]
}
```

El log de cambios se **densifica** a un punto por día, en escalón (el último precio
vigente se arrastra), sobre los últimos 60 días terminando hoy
(`HistorialDensificador`). Así `trend()` (media móvil de 7), `histN >= 3` y
`is_historical_low` conservan su semántica sin tocar la lógica de badges. Un producto
sin ninguna fila en la tabla no figura en el JSON.

`ml_pipeline.py` agrega el precio de hoy **en memoria** (el ML corre ANTES del upsert,
así que la tabla todavía no lo tiene) y **nunca reescribe el archivo**. `PythonRunner`
lo borra en un `finally`, también si el pipeline falla.

**Importación única del historial viejo**: antes el ML era dueño de ese JSON (un punto
por día visto, tope de 60). Si al empezar una corrida hay un `precio_historico.json`
en el directorio —sólo puede ser el viejo: el derivado se llama `ml_historial.json`—, se
aparta como `precio_historico.json.legacy`, se colapsa a cambios (último precio de cada
día, sólo donde el precio se movió) y se inserta en la tabla en una transacción, solo
para URLs presentes en `productos` y con `ON CONFLICT DO NOTHING` (idempotente; nunca
pisa lo que la tabla ya tiene). Recién entonces el archivo pasa a
`precio_historico.json.imported`. Si la importación falla, el `.legacy` queda y se
reintenta en la corrida siguiente.

**Actualización (decouple-services-postgres, Batch 2, design D4)**: desde este cambio, Python SÍ tiene acceso directo a la base — `ml_pipeline.py`/`ml_embeddings.py`/`ml_train.py` conectan vía `psycopg2` usando el env var `DATABASE_URL` (el mismo `DATABASE_URL` que usa Java para `spring.datasource.url`, pero traducido de formato JDBC a DSN libpq por `PythonRunner.toPsycopgDsn` antes de pasarlo al subproceso — psycopg2 no entiende el prefijo `jdbc:`). Ya no hay un `db_path` posicional ni un archivo `scraper.db` que resolver: `PythonRunner` fija `DATABASE_URL`/`SCRAPER_MODELS_ROOT`/`HF_HOME` como variables de entorno del subproceso (design D5).

**Purga automática**: entradas > 90 días se eliminan en cada run (salvo favoritos).

---

<!-- Movido desde CLAUDE.md (2026-09-28) -->
## Pipeline ML

Detalle y guía de extensión en [`docs/ML_PIPELINE.md`](./ML_PIPELINE.md).

**`ml_pipeline.py` (estadístico):** por categoría+género calcula `PriceStats`
(mediana, IQR, MAD, CV, Tukey fences). Score compuesto = 40% percentil + 35%
z-score modificado + 25% distancia a mediana/IQR → `price_segment`
(budget/standard/premium/luxury). **Todo el scoring usa precio unitario**
(`precio/cantidadUnidades`); display, descuento e historial usan precio de góndola.

**Badges (multi-badge, no exclusivo):** condiciones independientes, no una cadena
`elif`. Prioridad (el principal es el primero del set): `all_time_low` >
`below_market` > `verified_deal` > `trending` > `price_dropping` > `above_market`
> `fake_discount`. Persistido en `productos.ml_badge` como TEXT comma-delimited;
`/api/data?badge=` filtra por **pertenencia al set**, no por igualdad exacta.
`ofertaReal` es un boolean aparte.

**Stage 1b — ensemble texto+imagen:** gate `needs_image_fallback` (confianza de
texto <0.75, categoría genérica o género vacío). Máx 400 inferencias por run,
cache-first. Override de categoría gateado por incompatibilidad de tipos +
no-downgrade + confianza ≥0.82/0.92. Los atributos visuales se agregan de forma
**aditiva** — el texto gana.

**`ml_train.py`:** entrena SOLO el clasificador de texto (TF-IDF + `SGDClassifier` log loss;
con 60.9k filas y 260 clases, 50 s y 555 MB) → `_models/text_classifier.pkl`. `coef_` se guarda
en orden Fortran: en orden C, scipy lo copia entero en cada `predict_proba` de una fila (63 ms vs
0,68 ms sobre el mismo resultado). `--images` es no-op: la clasificación
visual es zero-shot, sin entrenamiento.

**`ml_embeddings.py`:** `hf-hub:Marqo/marqo-fashionSigLIP` vía `open_clip`,
zero-shot con prompts en inglés y labels en español, abstención por margen.
Cache en `image_embeddings` (invalidada por `MODEL_VERSION`). `HF_HOME` =
`<SCRAPER_MODELS_ROOT>/marqo`.

⚠️ **El scoring NO es reentrante, y a lo sumo corre UNA vez a la vez.**
`PythonRunner.ejecutar` resuelve tres rutas **fijas** en el cwd del proceso
—`ml_productos.json`, `ml_output.json`, `ml_historial.json`— y se las pasa
al subproceso como argv, así que dos corridas concurrentes se escriben los
archivos entre sí y ninguna falla ruidosamente: la segunda lee el input de la
primera o publica un output mezclado. Había dos llamadores capaces de chocar —el
path de scrape vía `ResultAggregator` y `POST /api/ml/aplicar`, que lanzaba su
hilo virtual **sin guard alguno**, a diferencia del entrenamiento, que ya
reservaba su slot con `intentarReservarSecuenciaIndiceVisual`—. Hoy el slot lo
toma `conReservaDeScoring` (CAS, molde del entrenamiento) y `/api/ml/aplicar`
rechaza en la puerta con **409** si hay un scrape `RUNNING` u otro scoring en
vuelo. El scrape es el dueño prioritario: degradarlo en silencio (su `ejecutar`
devolviendo `null` = corrida sin ML) para que entre un "aplicar" manual sería el
intercambio equivocado, y por eso el rechazo va en el endpoint y no en el path
de scrape. En el frontend, `MlStatusPanel.handleApply` **tiene** que avisar del
rechazo: mostraba "Aplicando..." tres segundos indistinguibles del camino feliz,
así que un guard correcto se veía como un no-op silencioso.

**`/api/ml/aplicar` también escribe en la base.** `/api/data` (y con él el panel de
Oportunidades) lee de la base, y `productos.ml_*` más `producto_badge` sólo los
escribía el upsert de un scrape. Por eso un "aplicar" manual actualizaba la memoria y
`ml_output` pero la base seguía idéntica (`all_time_low` 6463 antes y después).
Ahora, tras `enriquecer`, persiste con `ProductPort.actualizarMl`: un `UPDATE` de
`ml_score`/`ml_oferta`/`ml_tendencia`/`ml_segment`/`ml_zscore` y el reemplazo de los
`producto_badge` (principal primero, `posicion`), en una transacción. Sólo toca filas
que ya existen, nunca inserta un producto; el trigger de versión del catálogo se
dispara con el cambio de badges, que es lo que invalida las caches.

**Ojo con la taxonomía de `categoria`:** el vocabulario canónico pasó de 88 a
103 valores en `richer-category-taxonomy`, a **105** en `V32`, a **106** en `V38` (`Mini PC`) a **266** con `V64`..`V69` (Easy) y a **272** con `V74` (`Videojuego`, `Consola`, `Figura Coleccionable`, `Peluche`, `Juego de Mesa`, `Libro`), y vive en DOS
lugares que no pueden divergir — `CategoryGroups.canonicalCategories()` y la
tabla `categoria`. Dar de alta una categoría son **dos** cambios: el keyword que
la produce y la migración que la inserta. Si falta la migración, la FK rechaza
cada producto, pero `ProductRepository` se traga los errores SQL: el síntoma es
`"0 nuevos"` en una corrida sana, no un error. Detalle y porqué en
[`docs/DATABASE.md`](./DATABASE.md) (`V31`, `V32`) y
[`docs/ARCHITECTURE.md`](./ARCHITECTURE.md).

**El orden de `normalizarCategoria`:** nombre, hoja mapeada del árbol del sitio
(`sitio_categoria.categoria`, `V70`), `raw`, alias, peso, `Otros`. La hoja va antes que
`raw` porque es curada e idempotente: `raw` acierta o erra por coincidencia de palabras,
y al renormalizar es la categoría guardada, no el texto del sitio.

**`deporte` es una dimensión, no una categoría.** Una camiseta de Boca sigue siendo
`Remera` y además lleva `deporte='futbol'` (`V59`). `SportResolver` decide con el
mapa de `SportTaxonomy` (futbol, natacion, hockey, rugby, tenis, basquet, voley,
ciclismo, golf, combate; el primero que matchea gana). Dar de alta un deporte son
**dos** cambios, igual que una categoría: el keyword y la fila en la tabla `deporte`
(FK). Los clubes van con nombre completo (`river plate`, no `river`: "Kennebec
River" es Timberland). `EligibilityPolicy` saca de armadores, recomendados y picks
todo lo que tiene deporte o es `infantil`; el catálogo los sigue listando y filtra
con `?deporte=`. Los sufijos de talle de kids (`jr`, `ps`, `gs`, `td`…) sólo
cuentan en ropa (`V60`): fuera de ella "450 GS" son gramos y "PC/PS" es una consola.
Y todas las señales cortas de género (`nino`, `bebe`, `w`, `men`, `male`, `dama`) son
palabras enteras: como substring "argiNINA" era `infantil` y "neW Era" o "New
Balance" eran `mujer` (167 prendas activas, medido 2026-11-02). Las filas ya
guardadas se corrigen en el próximo scrape: sin el género crudo del sitio, una
migración no puede saber si el `mujer` venía del nombre o del sitio.

**Un nombre de marca no es un sustantivo de producto.** `Proteína` era la
categoría más grande del rubro suplementos y **61 de sus 201 filas no eran
proteína** (medido 2026-09-02): `"protein "` sin espacio adelante se metía
adentro de `MYPROTEIN` y `The Protein Lab`, y `"whey"` se comía la marca
`Natural Whey`, que en este catálogo vende cero whey. `V32` limpió eso —
`CategoryClassifier.sinMarcasQueNombranProteina` **borra** esas marcas del texto
antes de clasificar nutrición, en vez de vetar el producto, para no perder los
whey legítimos de esas mismas marcas— y recién después splitteó la categoría en
`Proteína Isolada` y `Proteína Vegetal`. El orden importa: splitear un balde
sucio da sub-baldes sucios.

**Clustering:** `cluster_productos` usa norms cacheadas + índice invertido
término→cluster + conteos O(1). Al tocarlo, construí los corpus de test con
tokens **alfabéticos**: el tokenizer descarta dígitos, así que un vocabulario
`tok1, tok2…` colapsa en UN cluster y esconde tanto el blowup como cualquier
regresión.

---
