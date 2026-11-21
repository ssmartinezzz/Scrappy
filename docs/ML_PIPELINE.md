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

**Sub-categoría en la clave de precios.** Un producto con `sub_categoria` se puntúa contra su
(categoría, sub) cuando ese grupo tiene al menos `MIN_GROUP` (10) filas; si no, contra la categoría
(con su cadena de padres). Todo producto sigue aportando a la distribución de su categoría, así que
quien no tiene sub conserva exactamente su puntaje. Mezclar subs miente: Cooler líquida (mediana
$222k) y pasta ($16,8k), CPU am5 ($637k) y am4 ($276k). Las etiquetas de deporte de
`SubcategoryResolver` (tier 2: `running`, `hockey`, `tenis`...) se ponen por coincidencia de nombre
sobre cualquier categoría, no son un tipo de producto, y **no** parten la distribución, salvo en
`CATEGORIAS_DONDE_EL_DEPORTE_ES_TIPO` (Pelota, Paleta, Zapatilla, Botas, Botines, Repuesto de
Bicicleta). Java manda `subCategoria` en `MlEnricher.serializarProductos`. Medido sobre las 82.653
filas activas (2026-11-19): 0 filas sin sub cambian, 9.729 filas con sub sí (tecnología 57 %, el resto
< 9 % salvo libros 24 % y deporte 16 %). `categoria_stats` sigue siendo por categoría (display).
Nota: `MlEnricher` no manda `rubro` ni `cantidadUnidades`, así que la rama `tech|` y el precio
unitario por pack no corren en producción; no se tocó.

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
103 valores en `richer-category-taxonomy`, a **105** en `V32`, a **106** en `V38` (`Mini PC`) a **266** con `V64`..`V69` (Easy) y a **272** con `V74` (`Videojuego`, `Consola`, `Figura Coleccionable`, `Peluche`, `Juego de Mesa`, `Libro`) y a **273** con `V82` (`Combo de Actualización`: CPU + motherboard vendidos juntos), y vive en DOS
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
y al renormalizar es la categoría guardada, no el texto del sitio. Excepción en
`NormalizerService` (no en el clasificador): en `elmundodeljuguete` una hoja mapeada a
categoría de juguetes va antes que el nombre.

**`Bombacha` es la ropa interior de abajo de mujer** (`V84`): `bombacha`, `panty`/`panties`,
`culotte`, `colaless`/`cola less`, `vedetina` y `tanga` (que vivía en `KW_CALZONCILLO` y
mandaba las tangas de mujer a `Calzoncillos`, o sea a hombre). `IndumentariaClassifier` la
chequea antes que `Calzoncillos`, con `KW_BOMBACHA_VETO`: traje de baño (`bikini`, `baño`,
`malla`, `playa`, `swim`), `pantalon culotte`/`jean`/`bermuda`/`short` y la bombacha gaucha
(`campo`, `gaucho`, `gabardina`, `rural`). `GenderResolver` la trata como categoría femenina
(como `Corpino`). `ropa interior femenina` sigue en `Corpino` (no distingue arriba de abajo).
El zero-shot de imagen tiene su etiqueta (`ml_embeddings.py`: "women's panties or bikini briefs
underwear, not swimwear"), y `ml_train.py` la agrupa igual que `calzoncillos`.

**`Perfume` tiene rubro propio y subcategorías** (`V90`). `RubroResolver` manda `Perfume` a `belleza` (hasta `V112` el rubro se llamaba `perfumeria`), salvo en un sitio
con `rubro_forzado` `tecnologia`/`oficina`, que sigue ganando. `SubcategoryResolver` lo parte por nombre en `Eau de Parfum`
(`eau de parfum`, `edp`, `parfum`), `Eau de Toilette` (`eau de toilette`, `edt`), `Body Splash` (`body splash`, `body mist`,
`splash`) y `Colonia` (`colonia`, `eau de cologne`); gana la primera en ese orden y, si ninguna aparece, queda `''`. El tier
deportivo (`hockey`, `running`...) no corre para `Perfume`. `KW_PERFUME` ya no matchea `splash` pelado ni `colonia` como
subcadena (las antiparras `Base-X Splash` y la `Teja Colonial` eran `Perfume`). `POST /api/ml/renormalizar` actualiza la `sub_categoria` de las filas guardadas pero NO su `rubro` (lo alinea `V90`), y unas `Perfume` mal clasificadas (los inflables `Splash` de Elmundodeljuguete) conservan la categoría hasta el próximo scrape del sitio.

**Moda de mujer por sustantivo líder, y `Bijouterie`** (`V92`). Las Pepas dejó 144 filas en `Otros`; quedan 15 (`llavero`, `aplique`,
`cuaderno`, `boligrafo`, `pin`: sin categoría). `IndumentariaClassifier` suma al final reglas por **primera palabra** del nombre
(`KW_*_LIDER`; `Remera con Aros` sigue siendo `Remera`): `blusa`/`camisola` → `Camisa`; `tapado`/`trench`/`parka`/`kimono` → `Campera`;
`babucha` → `Pantalón`; `enteriza` → `Enterito`; `chatita`/`guillermina de` → `Zapato`; `capucha`/`pañuelo` → `Bufanda` (con
`KW_PANUELO_VETO`: los pañuelos descartables no). `Bijouterie` es categoría nueva (aros, collar, choker, anillo, pulsera, broche,
scrunchie, hebilla, dije, brazalete): `CategoryClassifier` la chequea **antes** de `NonTextileGuard`, que veta esas palabras a propósito
(collares de mascota, anillos de sellado, broches de media sombra de Easy), y la protege `KW_BIJOUTERIE_VETO` (perro, gato, medida
`cm`/`mm`, `talla`, `reloj`, `inteligente`...). Un `Perfume para Auto` ya no es `Perfume`: `KW_LIMPIEZA_AUTO_LIDER` lo manda a `Limpieza de Auto`
(rubro `hogar`), junto a `aromatizante`. Canon 277.

**Belleza** (`V112`, `V113`, canon 290). El rubro `perfumeria` pasó a `belleza` y suma `Maquillaje`, `Cuidado de la Piel` y `Cuidado del Cabello`. `BellezaClassifier` corre antes de
`NonTextileGuard` (que veta `crema`/`shampoo`/`gel`/`protector solar` para las prendas): parte de `KW_BELLEZA_VETO` (mascotas, autos, pintura, madera, telas, `Perfume`/`Desodorante` en el nombre)
y `Maquillaje` suma `KW_MAQUILLAJE_VETO` (sets de juguete, el plural `Set De Maquillajes` de las jugueterías, estuches). Orden dentro del clasificador: labial-cuidado, `Maquillaje`, `Cabello`, `Piel`.
Una hoja curada fuera de belleza le gana a un nombre de belleza (y a `Perfume`). Sub-categorías: `Maquillaje` Labios/Uñas/Ojos/Rostro; `Piel` Protector Solar/Corporal/Facial;
`Cabello` Tintura/Shampoo/Acondicionador/Tratamiento; `Perfume` suma `Extracto` (`extrait`, antes de `Eau de Parfum`). Sin sub de deporte para las tres.

**`Desodorante` y `Llavero`** (`V93`, canon 279). `desodorante `/`antitranspirante` salen de `KW_PERFUME` a `KW_DESODORANTE`, que `AccesoriosClassifier`
chequea antes ("Desodorante ... fragancia X" es `Desodorante`); es categoría de `belleza` y no tiene subcategoría de perfume
(`SubcategoryResolver` sólo la da a `Perfume`). `Llavero` (`KW_LLAVERO`: `llavero`/`keychain` en cualquier parte del nombre) corre primero en
`CategoryClassifier`, antes de Figura Coleccionable/Juguete/Peluche y de `NonTextileGuard`, salvo que el nombre **arranque** con
mochila/bolso/cartera/riñonera; no pertenece a ningún grupo de rubro (el rubro sigue al sitio). `pin`/`pins` son líder de `Bijouterie`.
`V93` también realinea las `Perfume` guardadas sin palabra de fragancia (los inflables `Splash`, la `Teja Colonial`).

**`Vincha`, `Bandana` y `Traje Deportivo`** (`V94`, canon 282). `vincha`/`bandana` ya no son `Gorra` (`KW_VINCHA`/`KW_BANDANA`, antes de `KW_GORRA` en
`AccesoriosClassifier`; "Bermuda Bandana" sigue siendo `Bermuda`). `tracksuit`/`catsuit` son `Traje Deportivo` (`TrajeDeportivoClassifier`, antes de
`ConjuntoClassifier`) salvo una pieza suelta que arranca con campera/pantalón/...; Funko es siempre `Juguete` (primero en `CategoryClassifier`) y las
`Juguete` con `lego` tenían `sub_categoria = 'lego'` (desde `V96` son la categoría `LEGO`).

**`Camisaco`, `LEGO`, `TCG` y `Wall Panel`** (`V96`, canon 286). `camisaco` es `Camisaco` (`KW_CAMISACO`, antes de `Saco`; `KW_SACO` ahora usa
` saco ` con espacio, porque `saco ` terminaba `camisaco `). `blazer` sólo es `Saco` si **lidera** el nombre (`KW_SACO_BLAZER_LIDER`); "Nike Blazer
Low/Mid" son `Zapatilla Urbana`/`Skate` (`KW_URBANA_MODELO`/`KW_SKATE_MODELO`) y `Trailblazer` una `Mochila`; `mochila`/`backpack` vetan `Saco`.
`KW_TOP_VETO` (limpia, cepillo, bcaa, cápsula, proteína, whey, creatina) saca de `Top` los no-prenda, que siguen a su familia. `GymratTagger` nunca
marca `Camisa` ni `Chomba`. `LEGO` es una `Juguete` con la palabra `lego` entera (`KW_LEGO`), también cuando la `Juguete` viene de la hoja curada de Elmundodeljuguete (`CategoryClassifier.separarLego`, `V97`); `TCG` son los juegos de cartas coleccionables
(`KW_TCG`: tcg, trading card, booster pack/box, elite trainer box; o `card game`/`starter deck` junto a una franquicia de `KW_TCG_FRANQUICIA`), con
veto de taza/peluche/figura/consola (`KW_TCG_VETO`); un "Juego de Cartas" de fiesta sigue siendo `Juego de Mesa`. `SubcategoryResolver` da a la `TCG`
su franquicia (`pokemon`, `dragon ball`, `magic`, `yu-gi-oh`, `one piece`, `lorcana`, `digimon`) y ni `TCG` ni `LEGO` tienen subcategoría de deporte.
`Wall Panel` (`KW_WALL_PANEL`: wall panel, wallpanel, panel wpc) corre antes de `Placa Decorativa` en `ConstruccionClassifier`; el "panel ranurado" de
ferretería no es un wall panel.

**Sillas** (`V118`). `Silla` tiene `sub_categoria` por nombre: `gamer`, `oficina`, `infantil`, `exterior`, `comedor` (en ese orden de precedencia; sin señal queda vacía). El rubro lo decide la tabla
`CategoryGroups.rubroDeSubcategoria`: `comedor`, `exterior` e `infantil` son `hogar`, `gamer` y `oficina` siguen en `oficina`, y un sitio con `rubro_forzado` conserva el suyo. Detalle y medición en `docs/DATABASE.md` (`V118`).

**Electrodomésticos chicos y TV y Audio** (`V128`). `SubcategoryResolver` delega en `ElectroSubcategoryResolver`, que le da `sub_categoria` a `Pequeño Electrodoméstico` (13 valores, de `Cafetera` a `Tostadora y Sandwichera`) y a `TV y Audio` (10, de `Barra de Sonido` a `Tocadiscos`) por el **sustantivo líder** del nombre. Cada regla es `cat|sub|líderes|alguna|veto` y gana la primera que dispara: el nombre (sin acentos, con `/ : , ; + ( ) [ ]` como espacios, tras el prefijo de venta, `set`, `kit` y `mini`) debe empezar con un líder, contener alguna de `alguna` si la hay y ninguna de `veto`. Por eso "Soporte para Smart TV" es `Soporte`, "Accesorio para cafetera" es `Accesorio y Repuesto` y "Combo Licuadora + Batidora" es `Licuadora y Exprimidor`. Sin regla queda `''`, sin catch-all, y ninguna de las dos categorías recibe el tier 2 deportivo. El mismo vocabulario escribe `V128` sobre lo guardado (`V128ElectroTvAudioAgreementTest` lo vigila). Con sub, el precio se puntúa contra `<categoría>|<sub>` (ver "Sub-categoría en la clave de precios"): el efecto aparece en el próximo `aplicar`.

**Iluminación y Mueble** (`V129`). `SubcategoryResolver` delega en `HogarSubcategoryResolver` (mismo mecanismo, `LeadingNounRules`, y mismo formato de reglas que el electro) para darle `sub_categoria` a `Iluminación` (14 valores, de `Aplique` a `Velador`) y a `Mueble` (13, de `Alacena` a `Zapatero`) con las palabras del comercio argentino: la lamparita es `Lamparita`, no `Lámpara`. `lámpara`, `led` y `luz` no deciden solos, manda el segundo sustantivo ("Lámpara LED E27" es `Lamparita`, "Lámpara colgante" es `Colgante`, "Lámpara escritorio" es `Velador`); un combo, kit o set de cocina es `Cocina Modular`, y uno de otra cosa se lee por la primera pieza que nombra. `Placard`, `Mesa` ratona o de comedor y `Esquinero` quedan sin sub a propósito. Ninguna de las dos categorías recibe el tier 2 deportivo; `V129` escribe lo mismo sobre lo guardado (`V129HogarAgreementTest`). El precio se puntúa contra `<categoría>|<sub>` desde el próximo `aplicar`.

**Ropa de Cama y Colchón** (`V130`). `CamaSubcategoryResolver` usa el mismo mecanismo (`LeadingNounRules`). La ropa de cama (8 valores, de `Acolchado` a `Protector`) sale del sustantivo líder; el colchón (6 valores) siempre lidera con colchón o sommier, así que su sub es el **tamaño en plazas** (`1 Plaza`, `1 1/2 Plaza`, `2 Plazas`, `2 1/2 Plazas`, `Queen`, `King`), leído de las plazas o de las medidas del nombre; `King Koil` es una marca, no un tamaño. Sin tamaño o sin ser colchón (protector, base, cama) queda sin sub. `V130` escribe lo mismo sobre lo guardado (`V130CamaAgreementTest`). El precio se puntúa contra `<categoría>|<sub>` desde el próximo `aplicar`: un colchón de 1 plaza ya no se compara con uno King.

**Herramienta Manual** (`V131`). `HerramientaSubcategoryResolver` usa el mismo mecanismo (`LeadingNounRules`) y le da 12 valores por sustantivo líder con los nombres de una ferretería (`Pinza y Alicate`, `Llave y Tubo`, `Corte y Limado`, `Medición`, `Juego y Set`...). `set`, `kit` y `juego` se saltean: manda la herramienta que sigue. Toda regla veta las palabras de potencia (eléctrica, batería, `18v`, neumática, taladro), así que un taladro mal clasificado queda sin sub. `V131` escribe lo mismo sobre lo guardado (`V131HerramientaAgreementTest`); el precio se puntúa contra `<categoría>|<sub>` desde el próximo `aplicar`.

**Bicicleta** (`V132`). `BicicletaSubcategoryResolver` usa el mismo mecanismo (`LeadingNounRules`), pero como todo nombre lidera con `bicicleta` la sub es el **tipo** (`Mountain Bike`, `Paseo y Playera`, `Ruta`, `Urbana / Fixie`, `BMX`, `Plegable`, `Eléctrica`, `Infantil`, `Triciclo`, `Camicleta`): sale de una palabra explícita y, si no hay, del **rodado** (R27,5 y R29 son mountain bike, R12 a R16 infantil). R26 solo no decide. Una bici fija o de spinning es equipo de gimnasio y queda sin sub. `V132` escribe lo mismo sobre lo guardado (`V132BicicletaAgreementTest`); el precio se puntúa contra `<categoría>|<sub>` desde el próximo `aplicar`: una bici infantil ya no se compara con una mountain bike.

**Hardware por plataforma** (`V103`). `SubcategoryResolver` le da `sub_categoria` a `CPU` y `Motherboard` (`am4`, `am5`, `lga1700`, `lga1851`,
`lga1200`, `lga1151`), `RAM` (`notebook`, `ddr5`, `ddr4`, `ddr3`), `GPU` (`nvidia`, `amd`, `intel`), `Auricular` (`inalámbrico`, `gamer`, `oficina`,
`in-ear`) y `PC` (`gamer` si lleva GPU dedicada, `oficina` si no); `Gabinete` no tiene. Lee tokens alfanuméricos del nombre, no el nombre con
espacios: `AM5,`, `S.1700` y el pegado `S1700` cuentan. El socket sale de `SocketDeNombre` (`ar.scraper.model`, el mismo que los lectores de `pcs`):
token explícito, si no la generación del modelo de CPU (`Ryzen 5 9600X` → `am5`, `i5 10400` → `lga1200`, `Celeron G4900` → `lga1151`) o el chipset de
la mother (`B650` → `am5`, `B460` → `lga1200`). AM3, LGA2011 y Xeon quedan `''`. Sin token DDR, la RAM toma la generación de la velocidad
(≥ 4800 MHz `ddr5`, 2133-3600 `ddr4`, ≤ 1866 `ddr3`; DDR2 queda `''`). En `Auricular`, `in-ear` va primero si dice buds/TWS/true wireless, luego
`inalámbrico` > `gamer` (líneas de gaming conocidas, 7.1/surround/RGB) > `oficina` (Logitech `H###`/Zone, Genius `HS-###`). Las categorías de tecnología
(`TECNOLOGIA_SIN_DEPORTE`) ya no reciben el tier 2 deportivo: el `tenis` de un `Videojuego` era una fuga.

El sustantivo líder también gana sobre una palabra de GPU o de auricular en `TechClassifier`: `Water Cooler ... Titan 240 RX`, `Pasta Térmica ... Para
Cpu / Gpu`, `Gabinete ... GPU Mount`, `Fuente ... Para GPU` y `Mouse ... RX` ya no son `GPU` (`KW_NO_GPU_LIDER`); `Soporte`/`Rig` para una placa o unos
auriculares es un accesorio (`KW_SOPORTE_LIDER`); un combo, un mouse o un reloj que trae un auricular no es `Auricular`; `Headphone Jack` es una salida
de audio; los `My Arcade` son `Consola`; y `Combo Actualización ...` es `Combo de Actualización` aunque liste una RAM. `KW_GPU` pide un modelo:
` rx 4..9`, ` arc a3/a5/a7/b5/pro `.

**Género fijo por sitio.** `NormalizerService.GENERO_FIJO_POR_SITIO` (sitio → género) gana
sobre todo: `elmundodeljuguete` → `infantil`, `equus` → `hombre`, `laspepas` y `ayrun` → `mujer`.

**Ruido a `Otros` por sitio.** `NormalizerService.PALABRAS_OTROS_POR_SITIO` manda a `Otros`, antes
del clasificador, los nombres con ciertas palabras completas sólo en ese sitio: `oneplaceworld`
(hamaca, combo) y `kingofthekongo` (tote). Es por sitio porque en otras tiendas son productos
legítimos (`Reposera y Hamaca`, combos de suplementos, `Bolso`).

**`deporte` es una dimensión, no una categoría.** Una camiseta de Boca sigue siendo
`Remera` y además lleva `deporte='futbol'` (`V59`). `SportResolver` decide con el
mapa de `SportTaxonomy` (futbol, snowboard, natacion, hockey, rugby, tenis, basquet, voley,
ciclismo, golf, combate; el primero que matchea gana). `snowboard` (`V85`: `snowboard`,
`snow`, `ski`, `esqui`, `anon`) va **antes** de natacion a propósito: las antiparras de
nieve de Burton se llaman "Lentes Para Antiparras Anon M5" y `antiparra` es palabra de
natación. No hay `fijaciones` pelado (ferretería). Dar de alta un deporte son
**dos** cambios, igual que una categoría: el keyword y la fila en la tabla `deporte`
(FK). Los clubes van con nombre completo (`river plate`, no `river`: "Kennebec
River" es Timberland). `EligibilityPolicy` saca de armadores, recomendados y picks
todo lo que tiene deporte o es `infantil` (en picks, el rubro `juguetes` queda exento del
veto `infantil`: un juguete es infantil por definición); el catálogo los sigue listando y filtra
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

## Fugas del clasificador por nombre: Kit, Knit, Lamp, Cookie, Set y litros

Seis reglas del clasificador compartido leían una palabra de modelo o de tela como otro producto (medido sobre Sportline y la base de dev):

- **El primer sustantivo manda.** Con una prenda, calzado o bolso al frente (`KW_ROPA_O_CALZADO_LIDER`), `Kit`, `Lamp`, `Cookie`, `Maple`, `Pack` y `Set`
  son parte del nombre (`Pantalón adidas Kit`, `Remera Snauwaert Lamp`, `Zapatillas Timberland Maple Grove`, `Medias Topper Pack X 3`): ni `Conjunto`,
  ni `Iluminación`, ni `Alimentos`. Con un pantalón o short al frente (`KW_PIERNAS_LIDER`) las palabras de torso (`fleece`) no lo vuelven buzo.
- **`knit` ya no es `Sweater`** (`Remera Heavy Knit`, `Zapatillas Puma Knit`); `tejido` y `lana` siguen.
- **`Set`, `Kit`, `Combo` y `Pack` sólo hacen `Conjunto` si hay una prenda** (`KW_CONJUNTO_DEBIL` + torso, piernas o ropa suelta); `conjunto`, `total look` y
  `dos piezas` siempre (`KW_CONJUNTO_FUERTE`). Un `Set Lijas` o un `Set X 2 Velas` se abstiene y decide la hoja curada.
- **Un peso o volumen al final dice "alimento" sólo si nada más describe la fila** (`raw` vacío): `Fondo Galvite 0,5 L` bajo `Pinturas` es pintura.

Medido sobre las 82.654 filas activas de la base de dev (clasificador viejo contra nuevo, mismas entradas, sin `raw`): **1.850 filas cambian** (2,2 %): 1.759
salen de `Conjunto` (477 a `Otros`, 369 a `Juguete`, el resto a su categoría: `Accesorio de Herramienta`, `Medias`, `Pantalón`, `Remera`, `Decoración`...),
86 de `Sweater` y 5 de `Alimentos`; `Conjunto` baja de 2.122 a 363. Ninguna fila entra a `Conjunto`. El clasificador tarda ~45 ms más sobre las 82.654
filas (≈0,5 µs por fila, ~9 %). Quedan mal, y ya lo estaban: `Set De Tenis` (zapatilla) y `Metal Machines ... Pack` (zapatilla running).
