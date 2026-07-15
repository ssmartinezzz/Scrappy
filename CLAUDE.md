# Fashion Scraper Argentina — Contexto del Proyecto

> Este archivo existe para que Claude pueda leer el estado completo del proyecto en una nueva sesión sin necesidad de que el usuario lo explique desde cero. Leelo siempre antes de sugerir cambios.
> Última actualización integral: 2026-07-14.

---

## Qué es

Scraper headless de tiendas online argentinas (indumentaria, gym, suplementos y hardware/PC) con dashboard web inteligente. Un solo `.bat` instala todo y ejecuta desde cero en Windows. El usuario configura parámetros de búsqueda, lanza el scraping (manual o por cronjobs), y navega los resultados con filtros, comparador multi-sitio, feed personalizado, armador de outfits, análisis de cuotas/inflación y panel de tendencias ML con clasificación de imagen zero-shot.

---

## Stack técnico

| Capa | Tecnología |
|------|-----------|
| Backend/Scraper | Java 21 + Spring Boot 3.2 + Playwright 1.44 |
| Servidor web | Tomcat embebido en localhost:3000 |
| Frontend | React 18 + Vite 5 (SPA en `frontend/`, buildea a `scraper/src/main/resources/static/`) |
| Base de datos | SQLite (archivo `scraper.db` junto al .jar) |
| ML Pipeline | Python 3.11 embeddable (subprocess desde Java) — estadístico + TF-IDF + zero-shot visual |
| Clasificación visual | Marqo-FashionSigLIP vía open_clip (requiere `transformers` para el tokenizer) |
| Build | Maven + Spring Boot Maven Plugin (fat JAR). Toolchain bundled en `_tools/` (sin mvn/java del sistema) |

---

## Estructura de archivos clave

```
fashion-scraper-new/
├── CLAUDE.md                          ← Este archivo
├── SKILL.md                           ← Índice de documentación técnica
├── INSTALAR_Y_CORRER.bat              ← Instala Java + Maven + Python + Node + deps ML + compila + ejecuta
├── docs/                              ← ARCHITECTURE, ADD_SCRAPER, ML_PIPELINE, API_REFERENCE
└── scraper/
    ├── pom.xml                        ← sqlite-jdbc, playwright, opencsv, jackson; allure-bom (test)
    └── src/main/
        ├── java/ar/scraper/
        │   ├── App.java               ← Entry point Spring Boot
        │   ├── config/ScraperConfig   ← Lee config.properties, precio min/max
        │   ├── model/
        │   │   ├── Product.java       ← Record de 19 campos (ver "Model Product")
        │   │   └── ScrapeResult.java  ← Record: sitio, productos, error, duracionMs
        │   ├── pages/                 ← Page Object Model (Shopify/TN/VTEX/Vaypol/Woo/custom)
        │   ├── scrapers/
        │   │   ├── BaseScraper.java   ← Lanza Playwright, bloquea recursos pesados
        │   │   ├── ScraperFactory.java← Detecta plataforma por nombre/URL
        │   │   └── *Scraper.java      ← Shopify/TN/Vtex/Vaypol/WooCommerce/Maximus/FullH4rd/CompraGamer/Monkyforce
        │   ├── aggregator/            ← ResultAggregator + collaborators SOLID
        │   │   ├── FacetCalculator, NormalizerService (orquesta normalize/)
        │   │   ├── normalize/         ← PackQuantityDetector, CategoryClassifier, BrandExtractor,
        │   │   │                        GenderResolver, SizeNormalizer, SubcategoryResolver,
        │   │   │                        RubroResolver, GymratTagger + holders estáticos
        │   │   ├── grouping/          ← GroupingService, ProductIdentity, JaccardSimilarity
        │   │   └── text/              ← AccentStripper
        │   ├── ml/
        │   │   ├── PythonRunner.java  ← Subprocess Python; extrae ml_pipeline/ml_train/ml_embeddings del JAR;
        │   │   │                        secuencia índice visual (train texto + backfill embeddings); HF_HOME junto a scraper.db
        │   │   └── MlEnricher.java    ← Aplica scores Python → Product.MlScore
        │   ├── db/DatabaseService.java← SQLite: 16 tablas (ver "Base de datos")
        │   └── web/
        │       ├── ScraperService.java    ← Orquesta scraping async, carga DB al arrancar
        │       ├── ApiController.java     ← REST /api/** (ver "API REST")
        │       ├── CronApiController.java ← REST /api/cron/**
        │       └── SpaController.java     ← Sirve la SPA
        └── resources/
            ├── application.properties ← port=3000
            ├── logback-spring.xml     ← LOG_DIR configurable (default logs/): scraper.log + error.log rolling diario
            ├── config.properties      ← Sitios, precios, threads
            ├── ml/
            │   ├── ml_pipeline.py     ← Pipeline estadístico + stage 1b visual (se extrae junto al .jar)
            │   ├── ml_train.py        ← Entrena SOLO texto (TF-IDF+LogReg); --images es no-op
            │   └── ml_embeddings.py   ← Marqo-FashionSigLIP zero-shot + cache de embeddings + backfill CLI
            └── static/                ← Build output de Vite (NO editar directamente)
```

Las copias `scraper/ml_pipeline.py`, `scraper/ml_train.py` y `scraper/ml_embeddings.py` que aparecen junto al jar son artefactos de extracción runtime — están gitignoreadas; la única fuente de verdad es `scraper/src/main/resources/ml/`.

---

## Sitios configurados (`config.properties`)

| Sitio | Plataforma | Rubro | Notas |
|-------|-----------|-------|-------|
| freres | Shopify | moda | |
| vcp | Shopify | moda | |
| forever | Shopify | moda | En el name-set SHOPIFY desde 2026-07-14 (antes caía a TN y daba 0 productos) |
| foreverbstrd | Tiendanube | moda | URL estilo Shopify (`/collections/all`) pero es TN real — NO agregarlo al name-set |
| harvey | Tiendanube | moda | Única con `urls_extra` (outlet `otras-temporadas1`, pagina con `?mpage=N`) |
| midway, batuk, tussy, bulks, bullbenny, barnes, eldon | Tiendanube | moda | Batuk+Huoky misma tienda (huoky comentado) |
| fuark, fursten | Tiendanube | gym | Fursten pagina solo vía fallback `?page=N`. No existe flag `GYM_SITIOS`: son TN comunes |
| monkyforce | Monkyforce (scraper propio) | gym | |
| entreno | Tiendanube | suplementos | `rubro=suplementos` |
| sporting | VTEX | deportes | |
| vaypol, city | Vaypol (custom Rails SSR) | deportes | |
| dcshoes | WooCommerce | moda | |
| maximus, fullh4rd, compragamer | Scrapers propios | tecnologia | Hardware/PC — el proyecto ya no es solo moda |
| vans | — | — | Comentado: plataforma Grimoldi custom, sin scraper |

---

## Detección de plataforma (`ScraperFactory.crear`, en orden)

```
WOOCOMMERCE → {dcshoes, woocommerce}
MAXIMUS     → {maximus}      FULLH4RD → {fullh4rd}     COMPRAGAMER → {compragamer}
VAYPOL      → {vaypol, city}
VTEX        → {sporting} o url contiene vtexcommercestable.com.br / vteximg.com.br
SHOPIFY     → {freres, vcp, forever} o url contiene myshopify.com
MONKYFORCE  → {monkyforce}
default     → TiendanubeScraper (JS heurístico)
```

`plataformaDeFavorito`/`crearParaFavorito` resuelven favoritos solo a SHOPIFY/VTEX (resto UNSUPPORTED).

---

## API REST (resumen; detalle en `docs/API_REFERENCE.md`)

| Grupo | Endpoints |
|-------|-----------|
| Scraping | GET `/api/status` · POST `/api/scrape?precioMin&precioMax&sitios&forceRetrain` |
| Catálogo | GET `/api/data` (page, size, q, talle, genero, categoria, subCategoria, sitio, marca, badge, segment, rubro, gymrat, orden, pack, precioMin/Max, fit, estampado, escote, colorDominante) · GET `/api/facets` · GET `/api/csv` · DELETE `/api/data?url=` (soft-delete) |
| ML | GET `/api/tendencias` · GET `/api/historial?url=` · POST `/api/ml/aplicar` · POST `/api/ml/renormalizar` · GET `/api/ml/estado` · POST `/api/ml/entrenar` (train texto + backfill embeddings) · GET `/api/ml/resultado` |
| Comparador | GET `/api/grupos` · GET `/api/buscar-externo` (MercadoLibre) |
| Financiación | CRUD `/api/financiacion/presets` · GET `/api/recomendacion?url=` · GET `/api/inflacion` (INDEC) |
| Outfits | GET `/api/outfits` · GET `/api/outfits/builder` · GET `/api/suplementos/builder` · POST `/api/outfits/feedback` · CRUD `/api/outfits/saved` |
| Para ti | GET `/api/recomendados` · POST `/api/recomendados/feedback` · POST/DELETE `/api/recomendados/dismiss-categoria` |
| Favoritos | GET/POST/DELETE `/api/favoritos` · POST `/api/favoritos/rescrape` |
| Picks/Marcas | GET `/api/mejores?rubro=` · GET `/api/marcas-browser` |
| Sitios/Config | GET/POST/DELETE `/api/sitios` · PUT `/api/config` |
| Cron | GET/POST `/api/cron` · GET/PUT/DELETE `/api/cron/{id}` · GET `/api/cron/{id}/executions` · POST `/api/cron/{id}/run-now` |
| DB | GET `/api/db/export` · POST `/api/db/import` · DELETE `/api/db/productos` · DELETE `/api/db/ml` |

---

## Base de datos SQLite (`scraper.db`)

```
productos            -- Catálogo canónico (upsert por URL; cols ML, rubro, gymrat, pack, visual attrs)
precio_historico     -- Precio por fecha (UNIQUE url+fecha)
ml_output            -- Último output JSON del pipeline
image_embeddings     -- Cache de embeddings Marqo (url PK, BLOB, model_version)
categoria_stats      -- Stats de precio por categoría (panel tendencias)
sitios_dinamicos     -- Sitios agregados desde el dashboard
favoritos            -- Productos guardados
precios_externos     -- Comparativas MercadoLibre
outfit_feedback / outfit_feedback_item -- Likes/dislikes de outfits (legacy + por ítem)
saved_outfits        -- Outfits persistidos
categoria_dismiss    -- Categorías "no me interesa" del feed
financiacion_presets -- Presets de cuotas/recargo
cron_jobs / cron_executions -- Scraping programado + historial de corridas
```

**Upsert:** URL nueva → INSERT + historial · precio igual → `touched_at` · precio cambió → UPDATE + historial · ausente en el run → soft-delete (`activo=0`).

---

## Pipeline ML

**`ml_pipeline.py` (v2, estadístico):** por categoría+género calcula `PriceStats` (mediana, IQR, MAD, CV, Tukey fences). Score compuesto = 40% percentil + 35% z-score modificado (MAD) + 25% distancia a mediana/IQR → `price_segment` (budget/standard/premium/luxury). **Todo el scoring usa precio unitario** (`precio/cantidadUnidades` en packs); display, descuento e historial usan precio de góndola.

**Badges emitidos (cadena de prioridad):** `precio_historico_bajo`, `precio_bajo`, `oferta_real`, `tendencia`, `precio_bajando`, `precio_alto`, `descuento_cosmetico`. `ofertaReal` es un boolean aparte, independiente del badge mostrado. Guard anti-descuento-cosmético contra historial propio. Clustering TF-IDF greedy → `tendencia` + trendingClusters.

**Stage 1b — ensemble texto+imagen:** importa `ml_embeddings` (con fix de `sys.path` para el Python embeddable). Gate `needs_image_fallback`: confianza de texto <0.75, categoría genérica o género vacío. Máx 400 inferencias por run, cache-first. Override de categoría gateado por incompatibilidad de tipos + no-downgrade + confianza ≥0.82/0.92. Agrega attrs visuales (fit/estampado/escote/color) de forma aditiva — texto gana.

**`ml_train.py`:** entrena SOLO el clasificador de texto (TF-IDF + LogisticRegression, ~30s) → `_models/text_classifier.pkl`. El entrenamiento de imagen fue REMOVIDO; `--images` es no-op (la clasificación visual es zero-shot, sin entrenamiento).

**`ml_embeddings.py`:** `hf-hub:Marqo/marqo-fashionSigLIP` vía open_clip, zero-shot con prompts en inglés / labels en español, abstención por margen. Cache en tabla `image_embeddings` (invalidada por `MODEL_VERSION`). `HF_HOME` default = `_models/marqo` junto a `scraper.db`. El tokenizer requiere el paquete `transformers` (lo instala el paso 3f del .bat) — sin él, el modelo carga pero el backfill degrada a "modelo no disponible".

---

## Model `Product` (record, 19 campos)

```java
sitio, nombre, precio, precioOriginal, url, imagenUrl, categoria, genero,
talles, ml (MlScore), marca, rubro, gymrat, marcaPremium,
senal (SenalCompra), finan (SenalFinanciacion),
cantidadUnidades, subCategoria, visual (VisualAttrs)
```

Helpers: `esPack()`, `esTech()`, `esGymrat()`, `esMarcaPremium()`. `MlScore` incluye scoreP/badge/ofertaReal/tendencia/pctilCategoria/zScore/segment. `VisualAttrs` (fit/estampado/escote/colorDominante) es fill-only por campo.

---

## Flujo completo de un run

```
1. Usuario configura y lanza (splash, dashboard o cronjob)
2. POST /api/scrape → ScraperService.iniciarScraping()
3. Por sitio: ScraperFactory.crear() → BaseScraper.ejecutar()
4. ResultAggregator.agregar(): dedup → NormalizerService (categoría/género/talle/marca/pack/rubro)
   → PythonRunner (ml_pipeline.py, con stage 1b visual si hay modelo) → MlEnricher
   → DatabaseService.upsertProductos() + guardarMlOutput() + facets
5. En background (si corresponde): re-train texto + backfill de embeddings (índice visual)
6. Frontend polling /api/status → DONE → dashboard con filtros server-side
```

## Frontend (rutas principales)

Catálogo `/catalogo` · Picks `/picks(/:categoria)` · Para ti `/recomendados` · Cronjobs `/cronjobs` · Marcas `/marcas` · Suplementos `/suplementos` · Tendencias `/tendencias` · Comparar `/grupos` · Cuotas `/financiacion` · Favoritos `/favoritos` · Outfits `/outfits`. `MlStatusPanel` + `GpuTrainingOverlay` como componentes (no ruta propia).

---

## Gotchas de entorno (Windows)

- **Jar stale:** el `.bat` saltea Maven si `scraper/scraper.jar` existe. Tras recompilar, copiar a mano `scraper/target/fashion-scraper-1.0.0.jar` → `scraper/scraper.jar`.
- **Python embeddable:** `python311._pth` congela `sys.path` (no agrega el dir del script ni respeta PYTHONPATH). `ml_pipeline.py` inserta su propio dir antes de importar `ml_embeddings`.
- **HF_HOME:** el runtime lo resuelve junto a `scraper.db` (`scraper/_models/marqo`); el warm-up del installer apunta al mismo lugar y migra caches viejos de `_models/` en la raíz.
- **Build:** usar el toolchain bundled (`_tools/jdk21`, `_tools/maven`) desde la RAÍZ del repo, nunca desde `scraper/`.

## Problemas conocidos / pendientes

| Problema | Estado |
|---------|--------|
| Vans 0 productos (plataforma Grimoldi custom) | Comentado en config, pendiente investigación API |
| `SQLITE_BUSY_SNAPSHOT` en `upsertParcial`/cron cuando scrape y cron escriben a la vez | RESUELTO 2026-07-14: todas las escrituras serializadas en `writeLock` + `refrescarSnapshot()` + PRAGMA busy_timeout/WAL en `DatabaseService.initEn` |
| Lecturas sin lock sobre la `Connection` compartida pueden degradar (lista parcial/vacía + WARN) si coinciden con un commit/rollback | RESUELTO 2026-07-14: `DatabaseService` ahora abre una `readConn` dedicada (autoCommit=true) guardada bajo un `readLock` propio, separada de `conn`/`writeLock`; los 21 métodos de lectura standalone (cargarProductos, listCronJobs, etc.) corren aislados de las transacciones de escritura vía WAL, sin bloquear ni ser bloqueados por el escritor |
| Pack/unit pricing: posible drift de distribución ML en categorías con alta densidad de packs | Live — monitorear badges post-deploy, no recalibrar thresholds aún (ver docs/ML_PIPELINE.md) |
| `safe_price` puede parsear mal ciertos formatos de `precioOriginal` | Heurística interina aceptada (1611/6692 rechazados a 0.0 en el último run) |
| Bare `except:` en safe_price/price_velocity/history load | Nit no bloqueante — migrar a `except Exception:` |

---

## Cómo continuar en nueva sesión

1. Leé este archivo (`CLAUDE.md`) completo
2. Revisá `SKILL.md` para el índice de documentación técnica
3. Si hay problemas, pedí los logs: `scraper/logs/scraper.log` y `scraper/logs/error.log` (rolling diario)
4. Plataformas: `docs/ADD_SCRAPER.md` · ML: `docs/ML_PIPELINE.md` · Endpoints: `docs/API_REFERENCE.md`
