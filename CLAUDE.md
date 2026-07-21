# Fashion Scraper Argentina — Contexto del Proyecto

> Este archivo existe para que Claude pueda leer el estado completo del proyecto en una nueva sesión sin necesidad de que el usuario lo explique desde cero. Leelo siempre antes de sugerir cambios.
> Última actualización integral: 2026-07-21.

---

## Qué es

Scraper headless de tiendas online argentinas (indumentaria, gym, suplementos y hardware/PC) con dashboard web inteligente. Un solo `.bat` instala todo y ejecuta desde cero en Windows. El usuario configura parámetros de búsqueda, lanza el scraping (manual o por cronjobs), y navega los resultados con filtros, comparador multi-sitio, feed personalizado, armador de outfits, análisis de cuotas/inflación y panel de tendencias ML con clasificación de imagen zero-shot.

---

## Stack técnico

> **decouple-services-postgres** (2026-07-21): el proyecto pasó de monolito
> SQLite+SPA-embebida a **3 servicios independientes** (backend API-only,
> frontend Vite, ML Python subprocess) sobre **PostgreSQL**, 100% configurados
> por variables de entorno. Ver `docs/ARCHITECTURE.md` para el diagrama de
> topología completo y el detalle de las decisiones D1-D8.

| Capa | Tecnología |
|------|-----------|
| Backend/Scraper | Java 21 + Spring Boot 3.2 + Playwright 1.44 — **API-only** (sin `SpaController`, no sirve la SPA) |
| Servidor web | Tomcat embebido en localhost:3000 (configurable) |
| Frontend | React 18 + Vite 5 (SPA en `frontend/`), servido como **servicio propio**, habla al backend por CORS vía `VITE_API_BASE_URL` |
| Base de datos | **PostgreSQL** (`DATABASE_URL`) — Flyway `V1__baseline.sql` (15 tablas + `sp_upsert_run`/`sp_soft_delete_ausentes` plpgsql), pool HikariCP |
| ML Pipeline | Python 3.11 embeddable (subprocess desde Java) — estadístico + TF-IDF + zero-shot visual; conecta a Postgres directo vía `psycopg2`/`DATABASE_URL` |
| Clasificación visual | Marqo-FashionSigLIP vía open_clip (requiere `transformers` para el tokenizer) |
| Build | Maven + Spring Boot Maven Plugin (fat JAR). Toolchain bundled en `_tools/` (jdk21/maven/node/python/**pgsql**, sin dependencias del sistema) |
| Config | Env-only (`.env` gitignored, generado por el installer/`Ejecutar_instalar.sh`, jamás parseado en runtime por Java/Python — solo variables de proceso). Backend/frontend fail-fast si falta una var requerida en el profile default (`SPRING_PROFILES_ACTIVE=dev` para fallbacks locales) — ver "Gotchas de entorno". Plantilla canónica: `.env.example` (raíz) + `frontend/.env.example` |

---

## Estructura de archivos clave

```
fashion-scraper-new/
├── CLAUDE.md                          ← Este archivo
├── SKILL.md                           ← Índice de documentación técnica
├── INSTALAR_Y_CORRER.bat              ← Instala Java + PostgreSQL portable + Maven + Python + Node + deps ML
│                                          (incl. psycopg2-binary) + compila + genera .env + ejecuta (Windows)
├── Ejecutar_instalar.sh               ← Mirror POSIX (Linux/macOS) — asume toolchain del sistema, no vendoriza
├── docs/                              ← ARCHITECTURE, ADD_SCRAPER, ML_PIPELINE, API_REFERENCE
└── scraper/
    ├── pom.xml                        ← postgresql, flyway, HikariCP, testcontainers (test); playwright, opencsv, jackson; allure-bom (test)
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
        │   │   │                        secuencia índice visual (train texto + backfill embeddings); env DATABASE_URL
        │   │   │                        (traducido a DSN psycopg2 vía toPsycopgDsn)/SCRAPER_MODELS_ROOT/HF_HOME
        │   │   └── MlEnricher.java    ← Aplica scores Python → Product.MlScore
        │   ├── db/DatabaseService.java← PostgreSQL (HikariCP pool): 15 tablas (ver "Base de datos")
        │   └── web/
        │       ├── ScraperService.java    ← Orquesta scraping async, carga DB al arrancar
        │       ├── ApiController.java     ← REST /api/** (ver "API REST")
        │       ├── CronApiController.java ← REST /api/cron/**
        │       └── CorsConfig.java        ← Allow-list CORS (APP_CORS_ALLOWED_ORIGINS) — backend API-only
        └── resources/
            ├── application.properties ← port=3000; spring.datasource.* env-driven
            ├── logback-spring.xml     ← LOG_DIR configurable (default logs/): scraper.log + error.log rolling diario
            ├── config.properties      ← Sitios, precios, threads
            ├── db/migration/
            │   └── V1__baseline.sql   ← Flyway: 15 tablas + sp_upsert_run/sp_soft_delete_ausentes
            └── ml/
                ├── ml_pipeline.py     ← Pipeline estadístico + stage 1b visual (se extrae junto al .jar)
                ├── ml_train.py        ← Entrena SOLO texto (TF-IDF+LogReg); --images es no-op
                └── ml_embeddings.py   ← Marqo-FashionSigLIP zero-shot + cache de embeddings + backfill CLI
```

Las copias `scraper/ml_pipeline.py`, `scraper/ml_train.py` y `scraper/ml_embeddings.py` que aparecen junto al jar son artefactos de extracción runtime — están gitignoreadas; la única fuente de verdad es `scraper/src/main/resources/ml/`.

El frontend (`frontend/`) ya NO se buildea a `scraper/src/main/resources/static/` — corre como servicio propio (`npm run dev`/`npm run build`), hablando al backend por CORS vía `VITE_API_BASE_URL`.

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
| DB | GET `/api/db/export` · POST `/api/db/import` (ambos **410 Gone** desde decouple-services-postgres — no hay archivo `scraper.db`; usar `pg_dump`/`pg_restore` contra `DATABASE_URL`) · DELETE `/api/db/productos` · DELETE `/api/db/ml` |

---

## Base de datos PostgreSQL (`DATABASE_URL`)

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

**Upsert:** URL nueva → INSERT + historial · precio igual → `touched_at` · precio cambió → UPDATE + historial · ausente en el run → soft-delete (`activo=0`). Desde decouple-services-postgres (Batch 1, design D2) esto corre server-side en las funciones plpgsql `sp_upsert_run`/`sp_soft_delete_ausentes` (Flyway `V1__baseline.sql`), no en Java — la decisión "¿cambió el precio?" queda dentro de una sola sentencia SQL, sin locks de aplicación (Postgres MVCC + `UNIQUE(url,fecha)` + `ON CONFLICT` alcanzan). El viejo `writeLock`/`readLock`/`refrescarSnapshot()`/`readConn` dedicada (parche para el single-writer de SQLite) fue **removido por completo** junto con el resto de la lock-dance.

---

## Pipeline ML

**`ml_pipeline.py` (v2, estadístico):** por categoría+género calcula `PriceStats` (mediana, IQR, MAD, CV, Tukey fences). Score compuesto = 40% percentil + 35% z-score modificado (MAD) + 25% distancia a mediana/IQR → `price_segment` (budget/standard/premium/luxury). **Todo el scoring usa precio unitario** (`precio/cantidadUnidades` en packs); display, descuento e historial usan precio de góndola.

**Badges emitidos (multi-badge, no exclusivo):** cada producto puede calificar para varios badges a la vez — condiciones independientes, no una cadena `elif`. Orden de prioridad (principal = primero del set): `all_time_low` (Mínimo histórico) > `below_market` (Por debajo del mercado) > `verified_deal` (Descuento verificado) > `trending` (En demanda) > `price_dropping` (Bajando de precio) > `above_market` (Caro vs. mercado) > `fake_discount` (Descuento dudoso). Persistido en `productos.ml_badge` como TEXT comma-delimited, principal primero (p.ej. `verified_deal,trending`); `/api/data?badge=` filtra por pertenencia al set, no por igualdad exacta. `ofertaReal` es un boolean aparte, independiente del badge mostrado. Guard anti-descuento-cosmético contra historial propio. Clustering TF-IDF greedy → `trending` + trendingClusters.

**Stage 1b — ensemble texto+imagen:** importa `ml_embeddings` (con fix de `sys.path` para el Python embeddable). Gate `needs_image_fallback`: confianza de texto <0.75, categoría genérica o género vacío. Máx 400 inferencias por run, cache-first. Override de categoría gateado por incompatibilidad de tipos + no-downgrade + confianza ≥0.82/0.92. Agrega attrs visuales (fit/estampado/escote/color) de forma aditiva — texto gana.

**`ml_train.py`:** entrena SOLO el clasificador de texto (TF-IDF + LogisticRegression, ~30s) → `_models/text_classifier.pkl`. El entrenamiento de imagen fue REMOVIDO; `--images` es no-op (la clasificación visual es zero-shot, sin entrenamiento).

**`ml_embeddings.py`:** `hf-hub:Marqo/marqo-fashionSigLIP` vía open_clip, zero-shot con prompts en inglés / labels en español, abstención por margen. Cache en tabla `image_embeddings` (invalidada por `MODEL_VERSION`, leída/escrita vía `psycopg2`/`DATABASE_URL`). `HF_HOME` default = `<SCRAPER_MODELS_ROOT>/marqo` (env, ya no derivado de una ruta de archivo DB). El tokenizer requiere el paquete `transformers` (lo instala el paso de deps ML del installer) — sin él, el modelo carga pero el backfill degrada a "modelo no disponible".

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

Catálogo `/catalogo` · Picks `/picks(/:categoria)` · Para ti `/recomendados` · Cronjobs `/cronjobs` · Marcas `/marcas` · Suplementos `/suplementos` · Análisis `/analisis/mercado` (KPIs + insights, ex-Tendencias curado) · `/analisis/oportunidades` (badges + top deals) · `/analisis/oportunidades/:badge` (drill-down paginado completo) · Comparar `/grupos` · Cuotas `/financiacion` · Favoritos `/favoritos` · Outfits `/outfits`. `/tendencias` redirige a `/analisis/mercado` (ruta retirada). `MlStatusPanel` + `GpuTrainingOverlay` como componentes (no ruta propia).

---

## Gotchas de entorno (Windows)

- **Jar stale:** el `.bat` saltea Maven si `scraper/scraper.jar` existe. Tras recompilar, copiar a mano `scraper/target/fashion-scraper-1.0.0.jar` → `scraper/scraper.jar`.
- **Python embeddable:** `python311._pth` congela `sys.path` (no agrega el dir del script ni respeta PYTHONPATH). `ml_pipeline.py` inserta su propio dir antes de importar `ml_embeddings`.
- **HF_HOME:** el runtime lo resuelve como `<SCRAPER_MODELS_ROOT>/marqo` (env var, ya no derivado de una ruta de archivo DB); el installer genera `.env` con `SCRAPER_MODELS_ROOT=<repo>/scraper/_models` y el warm-up apunta al mismo lugar.
- **Build:** usar el toolchain bundled (`_tools/jdk21`, `_tools/maven`) desde la RAÍZ del repo, nunca desde `scraper/`.
- **`DATABASE_URL` tiene DOS formatos según el consumidor:** Java/Spring necesita el prefijo `jdbc:` (`jdbc:postgresql://host:port/db`); psycopg2 (Python) NO entiende `jdbc:` — solo `postgresql://...`. `PythonRunner.toPsycopgDsn` traduce automáticamente antes de pasarlo al subproceso; si alguna vez se agrega OTRO consumidor de `DATABASE_URL`, revisar este mismo problema.
- **Postgres portable:** el installer lo provisiona bajo `_tools/pgsql` (binarios EDB) + `_tools/pgdata` (datadir, `initdb -A trust` — sin password en local). El servidor queda corriendo entre ejecuciones del `.bat` (no se detiene solo); reusa la misma instancia la próxima vez (`pg_ctl status` chequea antes de re-arrancar).
- **Tests contra Postgres real:** `PostgresTestBase` (`scraper/src/test/java/ar/scraper/db/support/`) auto-selecciona Testcontainers (si hay Docker) o modo portable-local (`_tools/pgsql`, sin Docker) o se skipea con un mensaje claro si no hay ninguno — nunca hace fallar toda la suite por falta de infra.
- **Fail-fast en vars de entorno requeridas:** el backend NO tiene defaults silenciosos para `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`/`APP_CORS_ALLOWED_ORIGINS` en el profile default — `RequiredEnvVarsGuard` (`ar.scraper.config`, `EnvironmentPostProcessor`) aborta el arranque con un mensaje que nombra cada variable faltante. `DATABASE_PASSWORD` vacío (trust-auth local) SÍ cuenta como "presente" — solo una var totalmente ausente del entorno cuenta como faltante. Fallbacks de desarrollo local viven en `application-dev.properties`, activo solo con `SPRING_PROFILES_ACTIVE=dev`; los tests activan el profile `test` (mismo efecto de skip) vía `spring.profiles.active` en el `systemPropertyVariables` del surefire plugin (`scraper/pom.xml`), no vía anotaciones por clase. El frontend exige `VITE_API_BASE_URL` para `vite build` (prod) — falla el build si falta; `vite dev` sigue usando el proxy local sin requerirla. La plantilla canónica de variables es `.env.example` (raíz) + `frontend/.env.example` (solo `VITE_API_BASE_URL`, no duplicado en la raíz).

## Problemas conocidos / pendientes

| Problema | Estado |
|---------|--------|
| Vans 0 productos (plataforma Grimoldi custom) | Comentado en config, pendiente investigación API |
| `SQLITE_BUSY_SNAPSHOT` / lock-dance de aplicación (writeLock/readLock/refrescarSnapshot) | RESUELTO 2026-07-21 (`decouple-services-postgres`): migración completa a PostgreSQL + write-path en funciones plpgsql server-side; toda la lock-dance de aplicación fue removida, la concurrencia la resuelve Postgres MVCC |
| Pack/unit pricing: posible drift de distribución ML en categorías con alta densidad de packs | Live — monitorear badges post-deploy, no recalibrar thresholds aún (ver docs/ML_PIPELINE.md) |
| `safe_price` puede parsear mal ciertos formatos de `precioOriginal` | Heurística interina aceptada (1611/6692 rechazados a 0.0 en el último run) |
| Bare `except:` en safe_price/price_velocity/history load | Nit no bloqueante — migrar a `except Exception:` |
| `/api/db/export`/`/api/db/import` (410 Gone) — sin backup/restore vía UI | Aceptado por diseño (task 4.10): usar `pg_dump`/`pg_restore` directo contra `DATABASE_URL`; frontend `exportarDB()`/`importarDB()` removidos |
| `sp_upsert_run` reactivando un producto soft-deleted reinserta `precio_historico` aunque el precio no haya cambiado | Follow-up no bloqueante, documentado en `sdd/decouple-services-postgres` — no fixeado en este change |
| Instalador Windows portable-only: `Ejecutar_instalar.sh` (POSIX) asume herramientas del sistema (java/mvn/python3/node/postgresql-server o Docker) en vez de vendorizar todo como el `.bat` | Escrito y revisado, NO ejecutado end-to-end en Linux/macOS real (sandbox de desarrollo es Windows-only) |

---

## Cómo continuar en nueva sesión

1. Leé este archivo (`CLAUDE.md`) completo
2. Revisá `SKILL.md` para el índice de documentación técnica
3. Si hay problemas, pedí los logs: `scraper/logs/scraper.log` y `scraper/logs/error.log` (rolling diario)
4. Plataformas: `docs/ADD_SCRAPER.md` · ML: `docs/ML_PIPELINE.md` · Endpoints: `docs/API_REFERENCE.md`
