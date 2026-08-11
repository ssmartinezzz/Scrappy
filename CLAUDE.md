# Fashion Scraper Argentina — Contexto del Proyecto

> Este archivo existe para que Claude pueda leer el estado completo del proyecto en una nueva sesión sin necesidad de que el usuario lo explique desde cero. Leelo siempre antes de sugerir cambios.
> Última actualización integral: 2026-07-25.

---

## Qué es

Scraper headless de tiendas online argentinas (indumentaria, gym, suplementos y hardware/PC) con dashboard web inteligente. Un solo `.bat` instala todo y ejecuta desde cero en Windows. El usuario configura parámetros de búsqueda, lanza el scraping (manual o por cronjobs), y navega los resultados con filtros, comparador multi-sitio, feed personalizado, armador de outfits, análisis de cuotas/inflación y panel de tendencias ML con clasificación de imagen zero-shot.

> **native-cli-installer** (2026-07-25): supersede a `interactive-cli-launcher`
> (2026-07-21) — `menu.ps1`/`menu.sh` fueron **retirados** (borrados, junto con
> `tests/menu.Tests.ps1`/`tests/menu_test.sh`). El `.bat`/`Ejecutar_instalar.sh`
> ahora **solo aprovisionan el toolchain** (JDK/Maven/Node/Python embeddable+ML/
> Postgres portable, más `uv` + un `_tools/cli-venv` dedicado — ver abajo) y en
> su tail invocan un **CLI nativo en Python** (`cli/`, corrido con
> `_tools/cli-venv/.../python.exe -m cli` desde la raíz del repo — NO
> `cli\__main__.py` directo, que falla por los imports absolutos `cli.*`). El
> CLI (no el instalador) ahora posee: `npm install`/`npm run build` +
> `mvn clean package` + copia del jar, la generación/reconciliación de `.env`
> (template-driven desde `.env.example`, nunca pisa valores existentes salvo
> `--regenerate`/`--force`), y la orquestación de backend (`:3000`) + frontend
> (`npm run preview` en `:5173`) — con el mismo contrato REST-only, teardown
> limpio en Q/Ctrl+C, y la carga JVM `-DDATABASE_PASSWORD=<valor, incluso
> vacío>` que evita el bug de Windows con env vars vacías. Presenta una TUI
> Textual si la terminal es interactiva, con fallback automático a un runner
> de texto plano (`--plain`, `NO_COLOR`, `TERM=dumb`, stdin/stdout no-tty, o
> Textual no instalable) — nunca crashea. `_tools/cli-venv` es un venv
> **uv-managed CPython 3.11.9 aislado** (NO el embeddable de ML) — el CLI
> nunca importa librerías ML, así que no comparte nada con el pipeline de
> imágenes ni arriesga el guard de versión de torch. Cobertura: `pytest
> tests/cli` (headless `core/` + Textual `Pilot` + injection-safety, que
> reemplaza estructuralmente a los viejos `menu.Tests.ps1`/`menu_test.sh`).

> **cli-command-console** (2026-08-05): la TUI dejó de ser un menú (sidebar de
> botones + panel de health + panel de estado + formulario de sitios + log,
> todo en cajas con borde) y pasó a ser una **consola por comandos**:
> `ScrappyConsole` = franja de health de UNA línea + consola (1fr) + prompt +
> línea de hint. Chrome total: 3 filas — corre en 60×18 sin maximizar nada,
> que era la queja. Las operaciones se tipean, no se apretan: el vocabulario
> vive en `cli/core/commands.py` (registro único del que salen el
> autocompletado con ghost-text, `help`, y el menú del runner plano — no
> pueden divergir), con Tab para completar el verbo, ↑↓ de historial, y
> aliases (`q`/`exit`/`st`/`ls`). Ya no hay bindings de una sola letra, así
> que `q` es un carácter tipeable en vez de una acción que se lo tragaba.
> Sólo quedan `ctrl+c` (salir, con `priority=True` porque `Input` bindea
> ctrl+c a `copy`) y `ctrl+l` (limpiar).
>
> **Y el bug de fondo, que NO era de layout sino de stdio:** `launch_backend`
> redirigía sólo `stderr` (el **stdout** quedaba heredado) y `launch_frontend`
> no redirigía nada — o sea Spring Boot y Vite escribían ANSI crudo sobre el
> mismo TTY que Textual estaba pintando, y el frame quedaba destruido (Textual
> no se entera, así que ningún repaint lo arregla). Ahora **los tres streams
> de todo hijo están atados** (`_stdio_kwargs`): stdout+stderr al archivo de
> `cli/core/logs.py` (`scraper/logs/backend.log`, `frontend.log`), stdin a
> `DEVNULL` (si no, el hijo compite por las teclas del prompt). PIPE no es
> opción: nadie lo drena y el hijo se cuelga cuando se llena el buffer. La
> salida se lee con el comando `logs [backend|frontend] [n]` (tail con seek
> desde el final, acotado). Bonus: el handle del log ahora se cierra en el
> teardown — antes se filtraba un fd por cada launch. Cobertura: `tests/cli`
> (180 tests), incluyendo un test con un **subproceso real** que escupe
> stdout/stderr y verifica vía `capfd` que ni un byte llega a nuestra terminal.

> **docker-install-alternative** (2026-07-21, PR #109): existe una **tercera vía
> de instalación aditiva** por Docker — `docker compose up` levanta postgres +
> backend + frontend. NO reemplaza ni toca el flujo portable (`.bat`/`.sh`/
> el CLI nativo en `cli/`/`_tools/`); es para quien ya tiene Docker (Linux/macOS/CI/server).
> El backend es UNA imagen que bundlea Java 21 + Python 3.11 + Playwright/
> Chromium (el ML sigue siendo subprocess in-process, no un servicio aparte).
> Frontend = `vite build` → nginx, con `VITE_API_BASE_URL` como **build ARG**
> (build-time, no runtime). Plantilla de variables propia: `docker.env.example`
> (copiala a `.env`). Ver `docs/ARCHITECTURE.md` (topología + decisiones) y
> gotchas de Docker abajo.

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
| Build | Maven + Spring Boot Maven Plugin (fat JAR), ahora invocado por el **CLI nativo** (`cli/core/builder.py`), no por el installer. Toolchain bundled en `_tools/` (jdk21/maven/node/python/pgsql/**uv+cli-venv**, sin dependencias del sistema) |
| CLI nativo | `cli/` — Python (headless `core/` + presentador Textual + fallback texto plano), corrido sobre `_tools/cli-venv` (uv-managed CPython 3.11.9, aislado del embeddable de ML). Reemplaza a `menu.ps1`/`menu.sh` (retirados) y absorbe build + `.env` + orquestación backend/frontend — ver nota `native-cli-installer` arriba |
| Config | Env-only (`.env` gitignored, generado/reconciliado por el **CLI nativo** — `cli/core/env_file.py`, template-driven desde `.env.example` — ya no por el installer, jamás parseado en runtime por Java/Python — solo variables de proceso). Backend/frontend fail-fast si falta una var requerida en el profile default (`SPRING_PROFILES_ACTIVE=dev` para fallbacks locales) — ver "Gotchas de entorno". Plantilla canónica: `.env.example` (raíz) + `frontend/.env.example` |

---

## Estructura de archivos clave

```
fashion-scraper-new/
├── CLAUDE.md                          ← Este archivo
├── SKILL.md                           ← Índice de documentación técnica
├── INSTALAR_Y_CORRER.bat              ← Instala Java + PostgreSQL portable + Maven + Python + Node + deps ML
│                                          (incl. psycopg2-binary) + uv + _tools\cli-venv, e invoca el CLI nativo
│                                          (`-m cli`) — YA NO compila ni genera .env (eso es del CLI) (Windows)
├── Ejecutar_instalar.sh               ← Mirror POSIX (Linux/macOS) — asume toolchain del sistema (java/mvn/
│                                          python3/node), aprovisiona Postgres + uv/_tools/cli-venv, invoca el CLI
├── cli/                               ← CLI nativo (Python): core/ headless (config/env_file/builder/rest/
│                                          processes/errors/commands/logs) + tui/ (consola Textual) + plain/
│                                          (fallback texto plano) + __main__.py (detección de capacidad +
│                                          routing). core/commands.py = registro único de verbos (autocompletado
│                                          + help + menú plano); core/logs.py = archivos de log de servicios +
│                                          tail acotado (los hijos NUNCA escriben en la terminal — ver
│                                          cli-command-console arriba). Reemplaza a menu.ps1/menu.sh
│                                          (retirados 2026-07-25, native-cli-installer) — ver requirements.txt
├── Dockerfile                         ← Backend multi-stage (maven build → Playwright-java v1.44.0 + Temurin 21 + Python 3.11 + deps ML)
├── frontend/Dockerfile                ← Frontend multi-stage (vite build, ARG VITE_API_BASE_URL → nginx)
├── frontend/nginx.conf                ← nginx SPA fallback a index.html
├── docker-compose.yml                 ← postgres + backend + frontend, volúmenes pgdata/models/logs
├── docker-compose.override.yml.example← Override para Postgres externo
├── docker.env.example                 ← Plantilla de env del modo Docker (copiar a .env). Aditivo — NO reemplaza .env.example
├── .dockerignore / frontend/.dockerignore ← Excluyen _tools/, target/, node_modules del build context
├── tests/cli/                          ← pytest del CLI nativo: core/ (env-gen, VITE-ordering, injection-safety,
│                                           teardown-funnel) + Textual Pilot (tui/) + degradation-routing (__main__).
│                                           Reemplaza estructuralmente a tests/menu.Tests.ps1/tests/menu_test.sh
│                                           (retirados) — la injection-safety test es el reemplazo directo.
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
| Outfits | GET `/api/outfits` · GET `/api/outfits/builder` · GET `/api/suplementos/builder` · GET `/api/suplementos/tipos` · POST `/api/outfits/feedback` · CRUD `/api/outfits/saved` |
| Para ti | GET `/api/recomendados` · POST `/api/recomendados/feedback` · POST/DELETE `/api/recomendados/dismiss-categoria` |
| Favoritos | GET/POST/DELETE `/api/favoritos` · POST `/api/favoritos/rescrape` |
| Picks/Marcas | GET `/api/mejores?rubro=` · GET `/api/marcas-browser` |
| Sitios/Config | GET/POST/DELETE `/api/sitios` · PUT `/api/config` |
| Cron | GET/POST `/api/cron` · GET/PUT/DELETE `/api/cron/{id}` · GET `/api/cron/{id}/executions` · POST `/api/cron/{id}/run-now` |
| DB | GET `/api/db/export` · POST `/api/db/import` (ambos **410 Gone** desde decouple-services-postgres — no hay archivo `scraper.db`; usar `pg_dump`/`pg_restore` contra `DATABASE_URL`) · DELETE `/api/db/productos` · DELETE `/api/db/ml` |
| LLM Agent | POST `/api/agent/chat` (tool-use, gateado por scraping) · POST `/api/agent/apply` (único write, tras confirmación humana, gateado por scraping) · GET `/api/agent/models` (no gateado) — ver "LLM Catalog Agent" abajo |

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
agent_reclassify_audit -- Auditoría de reclasificaciones humanas vía POST /api/agent/apply (V2 Flyway)
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

## LLM Catalog Agent (`ar.scraper.agent`, llm-catalog-nlp)

Agente de chat con tool-use, **provider-pluggable**, para revisar/corregir la
clasificación (categoría/subcategoría/marca/género) de productos reales por
lenguaje natural. Seam `ChatProvider` (records de dominio, sin formas
OpenAI/Anthropic filtradas a los callers) con un único adapter hoy:
`OpenAiCompatProvider` (Ollama `/v1/chat/completions` + `/v1/models`, vía
`java.net.http.HttpClient`+Jackson, mismo patrón que `InflacionService`).

**El agente tiene EXACTAMENTE 3 herramientas, TODAS de solo lectura** dentro
del loop acotado (`CatalogAgentService`, `MAX_ITERATIONS=6`): `search_products`,
`view_product`, `propose_reclassify`. La reclasificación es **two-phase
propose/confirm** — `propose_reclassify` valida (URL existe + categoría ∈
taxonomía canónica de `CategoryGroups`/`GarmentTaxonomy`) y devuelve un diff,
**nunca escribe**; el único write real es `POST /api/agent/apply`, fuera del
loop, solo tras confirmación explícita del usuario en la UI, re-validando
server-side.

> **agent-chat-finetune** (2026-07-25): el botón de confirmación nunca había
> funcionado — `POST /api/agent/apply` leía un shape de `Map` distinto al
> `ReclassifyProposal` que el agente realmente produce (todo click daba 400).
> Fix: el endpoint acepta ahora el `ReclassifyProposal` tipado tal cual
> (`@JsonIgnoreProperties(ignoreUnknown = true)`, sin DTO paralelo). Persiste
> vía `DatabaseService.aplicarReclasificacionAuditada` — UPDATE + INSERT de
> auditoría (`agent_reclassify_audit`) en una sola transacción, con rollback
> completo si cualquiera de los dos falla; su booleano de retorno SIEMPRE se
> chequea (antes, `actualizarNormalizacion` tragaba excepciones en silencio y
> devolvía `200 "Reclasificación aplicada."` sin haber escrito nada — el
> mismo defecto también afectaba a `POST /ml/renormalizar`, que ahora
> reporta `escrituras*` reales además del diff intencional). Nuevo **staleness
> guard**: compara `categoriaActual` del body contra la categoría real leída
> de la DB (no del snapshot en memoria) — un conflicto devuelve `422
> conflicto_stale` con los valores vigentes en vez de sobrescribir a ciegas.
>
> **agent-chat-continuity** (2026-07-29): el chat funcionaba UNA vez y después
> rechazaba todo con "No pude responder eso con datos reales del catálogo"
> (`TurnOutcome.UNGROUNDED`). Causa raíz: el historial que volvía al modelo era
> **lossy** — el frontend guardaba solo `{role, text}` y `ApiController` lo
> reconstruía como `new ChatMessage(role, text, List.of(), null)`, sin rastro
> de tool calls. El modelo leía un transcript donde el assistant simplemente
> respondía en prosa e imitaba ese patrón: dejaba de llamar herramientas, y el
> guard de grounding de #121 (correctamente, dado lo que veía) descartaba su
> texto. Se retroalimentaba, porque el aviso de rechazo no entra a `messages`.
>
> Fix estructural, sin estado de sesión en el servidor: cada mensaje assistant
> ahora carga su **`trace`** (`ToolStep` = un step del loop con sus llamadas),
> el cliente lo reenvía, y `CatalogAgentService.replayInto` **re-ejecuta** esas
> llamadas contra el snapshot vivo antes de contactar al proveedor. El cliente
> round-tripea **solo lo que el modelo PIDIÓ** (`name` + `arguments`), nunca lo
> que el catálogo RESPONDIÓ → un `trace` manipulado no puede inyectar un dato
> falso, y la evidencia replayada está al día (tras un `/agent/apply`
> confirmado, un `view_product` replayado devuelve la categoría NUEVA).
> El grounding sigue siendo **estrictamente por turno** — el replay reconstruye
> contexto, no lo lava; el falso negativo restante se cubre con **un** empujón
> correctivo (`GROUNDING_NUDGE`) antes de rechazar. Bounds: `MAX_REPLAY_CALLS=12`
> (cola más reciente, contigua) + 8 steps × 6 calls de transporte.
> Además: `parseAgentRole` ahora solo acepta `user`/`assistant` (un `system`
> o `tool` del cliente degrada a `user`), y una reclasificación confirmada
> entra al transcript como mensaje ("Cambio aplicado en el catálogo: …"),
> que antes el modelo nunca llegaba a saber.
>
> **Parche del catálogo en memoria** (fix posterior): `/api/data` y
> `/api/mejores` sirven de `lastResult`, NO de la DB en cada request — ese
> snapshot solo se reconstruye al arrancar o tras un scrape. Sin parchear la
> memoria, una reclasificación persistida quedaba invisible en la UI (Picks
> incluido) hasta reiniciar el backend. `agentApply` ahora llama a
> `ScraperService.actualizarProductoEnMemoria` DESPUÉS de verificar que la
> escritura ocurrió, mismo patrón que `eliminarProductoDeMemoria` tras un
> soft-delete. Recalcula facetas (a diferencia del soft-delete) porque
> reclasificar mueve al producto entre categorías y los contadores del filtro
> quedarían mintiendo.

Config 100% por env (`LLM_PROVIDER`/`LLM_MODEL`/`LLM_BASE_URL`/`LLM_API_KEY`,
todas opcionales con default local Ollama — **no** están en
`RequiredEnvVarsGuard`). Selector de modelo en runtime (`GET /api/agent/models`
descubre dinámicamente los modelos pulleados, sin hardcodear lista; `model`
opcional por-request en `POST /api/agent/chat`, sin estado mutable server-side).
`POST /api/agent/chat`/`POST /api/agent/apply` están gateados por scraping
(409 si `RUNNING`, misma VRAM que compite con Marqo-FashionSigLIP);
`GET /api/agent/models` NO. Frontend: widget flotante `AgentChatPanel.jsx`
montado a nivel `AppLayout` (no un botón dentro de `MlStatusPanel.jsx` — mudó
ahí en los commits 3fb328e/ddd48ed), con selector de modelo + `ProposalCard`
([Sí]/[No] normal, o [Volver a consultar]/[Descartar] cuando la propuesta
quedó stale).

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

- **Logs de los servicios lanzados por el CLI:** desde `cli-command-console` el backend y el frontend NO escriben en la terminal (romperían el render de la consola) — su stdout+stderr van a `scraper/logs/backend.log` y `scraper/logs/frontend.log`. Se leen con el comando `logs [backend|frontend] [n]` (en la consola o en modo plano), o abriendo el archivo. El viejo `scraper/logs/backend-launcher.err.log` ya no se usa. Esto es aparte del logback del backend (`scraper.log`/`error.log`), que sigue igual.
- **Jar stale:** el CLI nativo (`cli/core/builder.py`) saltea el build si `scraper/scraper.jar` existe. Tras recompilar a mano, copiar `scraper/target/fashion-scraper-1.0.0.jar` → `scraper/scraper.jar`, o borrar el jar y correr `build` desde el CLI de nuevo.
- **Python embeddable:** `python311._pth` congela `sys.path` (no agrega el dir del script ni respeta PYTHONPATH). `ml_pipeline.py` inserta su propio dir antes de importar `ml_embeddings`. Esto es **solo del embeddable de ML** (`_tools/python`) — el `_tools/cli-venv` del CLI nativo es un venv uv-managed normal, sin este problema (por diseño: no comparte nada con el embeddable — ver nota `native-cli-installer`).
- **HF_HOME:** el runtime lo resuelve como `<SCRAPER_MODELS_ROOT>/marqo` (env var, ya no derivado de una ruta de archivo DB); el CLI genera `.env` con `SCRAPER_MODELS_ROOT=<repo>/scraper/_models` y el warm-up apunta al mismo lugar.
- **Build:** usar el toolchain bundled (`_tools/jdk21`, `_tools/maven`) desde la RAÍZ del repo, nunca desde `scraper/` — esto ahora lo hace el CLI nativo (`cli/core/builder.py`), invocado desde su propio menú (`build`), no el `.bat`/`.sh`.
- **CLI nativo (`_tools/cli-venv`):** si `import textual` falla dentro de `_tools/cli-venv`, el instalador aborta con un mensaje accionable (Python es load-bearing en ambos SO desde `native-cli-installer`). Para reprovisionarlo desde cero: borrar `_tools/uv` y `_tools/cli-venv` y re-correr el `.bat`/`Ejecutar_instalar.sh`. `python -m cli` (NO `cli/__main__.py` directo — falla por imports absolutos `cli.*`) debe correrse con cwd = raíz del repo.
- **`DATABASE_URL` tiene DOS formatos según el consumidor:** Java/Spring necesita el prefijo `jdbc:` (`jdbc:postgresql://host:port/db`); psycopg2 (Python) NO entiende `jdbc:` — solo `postgresql://...`. `PythonRunner.toPsycopgDsn` traduce automáticamente antes de pasarlo al subproceso; si alguna vez se agrega OTRO consumidor de `DATABASE_URL`, revisar este mismo problema.
- **Postgres portable:** el installer lo provisiona bajo `_tools/pgsql` (binarios EDB) + `_tools/pgdata` (datadir, `initdb -A trust` — sin password en local). El servidor queda corriendo entre ejecuciones del `.bat` (no se detiene solo); reusa la misma instancia la próxima vez (`pg_ctl status` chequea antes de re-arrancar).
- **Tests contra Postgres real:** `PostgresTestBase` (`scraper/src/test/java/ar/scraper/db/support/`) auto-selecciona Testcontainers (si hay Docker) o modo portable-local (`_tools/pgsql`, sin Docker) o se skipea con un mensaje claro si no hay ninguno — nunca hace fallar toda la suite por falta de infra.
- **Fail-fast en vars de entorno requeridas:** el backend NO tiene defaults silenciosos para `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`/`APP_CORS_ALLOWED_ORIGINS` en el profile default — `RequiredEnvVarsGuard` (`ar.scraper.config`, `EnvironmentPostProcessor`) aborta el arranque con un mensaje que nombra cada variable faltante. `DATABASE_PASSWORD` vacío (trust-auth local) SÍ cuenta como "presente" — solo una var totalmente ausente del entorno cuenta como faltante. Fallbacks de desarrollo local viven en `application-dev.properties`, activo solo con `SPRING_PROFILES_ACTIVE=dev`; los tests activan el profile `test` (mismo efecto de skip) vía `spring.profiles.active` en el `systemPropertyVariables` del surefire plugin (`scraper/pom.xml`), no vía anotaciones por clase. El frontend exige `VITE_API_BASE_URL` para `vite build` (prod) — falla el build si falta; `vite dev` sigue usando el proxy local sin requerirla. La plantilla canónica de variables es `.env.example` (raíz) + `frontend/.env.example` (solo `VITE_API_BASE_URL`, no duplicado en la raíz).
- **Docker (modo aditivo, no solo Windows):**
  - `VITE_API_BASE_URL` es **build-time** (Vite lo hornea en el bundle) → cambiarlo requiere `docker compose up --build`, un `up` a secas NO lo toma. El resto de las vars son runtime (se toman con reiniciar).
  - En `DATABASE_URL` el host es **`postgres`** (nombre del servicio, DNS interna de Docker), NO `localhost`. La comunicación backend↔postgres es contenedor-a-contenedor; la del navegador↔backend usa `localhost:3000` (puerto publicado).
  - Triángulo que tiene que cerrar: `APP_CORS_ALLOWED_ORIGINS` (origen del frontend, `:8080`) ↔ `VITE_API_BASE_URL` (backend, `:3000`) ↔ port mappings del compose. Si no coinciden → error de CORS.
  - `pgdata`/`models`/`logs` son **volúmenes nombrados** → sobreviven a `docker compose down`; los pesos de Marqo/HF se bajan una sola vez (lazy, al primer run).
  - Sin Docker en el sandbox de dev (Windows-only): el smoke real de runtime (`compose up`/scrape) se valida en CI vía `.github/workflows/docker-smoke.yml` (`compose config` + `docker build` de ambas imágenes).

## Problemas conocidos / pendientes

| Problema | Estado |
|---------|--------|
| Vans 0 productos (plataforma Grimoldi custom) | Comentado en config, pendiente investigación API |
| `SQLITE_BUSY_SNAPSHOT` / lock-dance de aplicación (writeLock/readLock/refrescarSnapshot) | RESUELTO 2026-07-21 (`decouple-services-postgres`): migración completa a PostgreSQL + write-path en funciones plpgsql server-side; toda la lock-dance de aplicación fue removida, la concurrencia la resuelve Postgres MVCC |
| El chat del agente funcionaba una sola vez: tras el primer turno exitoso, toda consulta siguiente devolvía `UNGROUNDED` ("no puedo con el catálogo") | RESUELTO 2026-07-29 (`agent-chat-continuity`): el historial reenviado al modelo era lossy (sin tool calls), así que el modelo imitaba un transcript de prosa pelada y dejaba de llamar herramientas. Ahora cada turno lleva su `trace` y el servidor **re-ejecuta** esas llamadas contra el catálogo vivo (resultados jamás vienen del cliente) |
| Botón de confirmación del LLM Catalog Agent nunca funcionaba (`POST /api/agent/apply` 400 en todo click, contrato de body distinto al `ReclassifyProposal` real) + escrituras silenciosamente truncadas (`actualizarNormalizacion` tragaba excepciones y devolvía `200` sin haber escrito) | RESUELTO 2026-07-25 (`agent-chat-finetune`): body tipado, write path auditado con rollback atómico, staleness guard contra la DB (`422 conflicto_stale`) |
| Pack/unit pricing: posible drift de distribución ML en categorías con alta densidad de packs | Live — monitorear badges post-deploy, no recalibrar thresholds aún (ver docs/ML_PIPELINE.md) |
| `safe_price` puede parsear mal ciertos formatos de `precioOriginal` | Heurística interina aceptada (1611/6692 rechazados a 0.0 en el último run) |
| Bare `except:` en safe_price/price_velocity/history load | Nit no bloqueante — migrar a `except Exception:` |
| `/api/db/export`/`/api/db/import` (410 Gone) — sin backup/restore vía UI | Aceptado por diseño (task 4.10): usar `pg_dump`/`pg_restore` directo contra `DATABASE_URL`; frontend `exportarDB()`/`importarDB()` removidos |
| `sp_upsert_run` reactivando un producto soft-deleted reinserta `precio_historico` aunque el precio no haya cambiado | Follow-up no bloqueante, documentado en `sdd/decouple-services-postgres` — no fixeado en este change |
| Instalador Windows portable-only: `Ejecutar_instalar.sh` (POSIX) asume herramientas del sistema (java/mvn/python3/node/postgresql-server o Docker) en vez de vendorizar todo como el `.bat` — la parte NUEVA de `native-cli-installer` (uv + `_tools/cli-venv`) SÍ vendoriza igual que el `.bat` en ambos SO | El aprovisionamiento java/mvn/node sigue asumiendo el sistema (gap preexistente, no resuelto en `native-cli-installer` — fuera de su scope). La parte nueva (uv/cli-venv + invocación del CLI) SÍ fue ejecutada end-to-end en un sandbox Linux real 2026-07-25: `bash Ejecutar_instalar.sh` corrió las 4 fases completas, provisionó `_tools/uv`+`_tools/cli-venv` reales, y el tail invocó el CLI (`python -m cli`) que respondió `status` contra un backend real en `:3000`. `INSTALAR_Y_CORRER.bat` sigue sin ejecutarse end-to-end (sandbox de desarrollo de esta sesión también fue Linux, no Windows) |

---

## Cómo continuar en nueva sesión

1. Leé este archivo (`CLAUDE.md`) completo
2. Revisá `SKILL.md` para el índice de documentación técnica
3. Si hay problemas, pedí los logs: `scraper/logs/scraper.log` y `scraper/logs/error.log` (rolling diario)
4. Plataformas: `docs/ADD_SCRAPER.md` · ML: `docs/ML_PIPELINE.md` · Endpoints: `docs/API_REFERENCE.md`
