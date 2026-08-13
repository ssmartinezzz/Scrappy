# Fashion Scraper Argentina — Guía

> **Este archivo es una guía índice: qué hay y dónde leerlo.** No es un
> changelog ni el lugar de las justificaciones. Si acá aparece un párrafo
> explicando *por qué* se tomó una decisión, está en el archivo equivocado y
> va a [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).
>
> El reparto entre los documentos raíz es deliberado:
> **`CLAUDE.md` = guía · [`CONTRIBUTING.md`](./CONTRIBUTING.md) = proceso ·
> [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) = por qué ·
> [`SKILL.md`](./SKILL.md) = índice de documentación.**
>
> Se carga en contexto en cada sesión, así que tiene que ser navegable: preferí
> una tabla y un puntero antes que un párrafo.
>
> Última actualización integral: 2026-08-12.

---

## Qué es

Scraper headless de tiendas online argentinas (indumentaria, gym, suplementos y
hardware/PC) con dashboard web inteligente. Un solo `.bat` instala todo y ejecuta
desde cero en Windows. El usuario configura parámetros de búsqueda, lanza el
scraping (manual o por cronjobs), y navega los resultados con filtros, comparador
multi-sitio, feed personalizado, armador de outfits, análisis de cuotas/inflación
y panel de tendencias ML con clasificación de imagen zero-shot.

**Tres vías de instalación**, todas soportadas:

1. **Windows portable** — `INSTALAR_Y_CORRER.bat` vendoriza todo el toolchain en `_tools/` e invoca el CLI nativo.
2. **POSIX** — `Ejecutar_instalar.sh`. Asume java/mvn/node/python3 del sistema; sí vendoriza `uv` + `cli-venv`.
3. **Docker** (aditiva, no reemplaza a las otras) — `docker compose up`: postgres + backend + frontend.

---

## Stack técnico

Tres servicios independientes (backend API-only, frontend Vite, ML Python
subprocess) sobre PostgreSQL, 100% configurados por variables de entorno.

| Capa | Tecnología |
|------|-----------|
| Backend/Scraper | Java 21 + Spring Boot 3.2 + Playwright 1.44 — **API-only**, no sirve la SPA |
| Servidor web | Tomcat embebido en `localhost:3000` (configurable) |
| Frontend | React 18 + Vite 5 (`frontend/`), servicio propio, habla al backend por CORS vía `VITE_API_BASE_URL` |
| Base de datos | PostgreSQL (`DATABASE_URL`) — Flyway `V1` (15 tablas + `sp_upsert_run`/`sp_soft_delete_ausentes` en plpgsql), `V2` (auditoría del agente), `V3` (lock de clasificación manual); pool HikariCP |
| ML Pipeline | Python 3.11 embeddable, subprocess desde Java — estadístico + TF-IDF + zero-shot visual; conecta a Postgres vía `psycopg2` |
| Clasificación visual | Marqo-FashionSigLIP vía `open_clip` (requiere `transformers` para el tokenizer) |
| Build | Maven + Spring Boot Maven Plugin (fat JAR), invocado por el **CLI nativo** (`cli/core/builder.py`), no por el installer |
| CLI nativo | `cli/` — Python: `core/` headless + consola Textual + fallback texto plano. Corre sobre `_tools/cli-venv` (CPython 3.11.9 uv-managed, aislado del embeddable de ML) |
| Config | Env-only. `.env` gitignored, generado por `cli/core/env_file.py` desde `.env.example`. Jamás parseado en runtime por Java/Python — solo variables de proceso |

📄 Decisiones y su justificación: [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).

---

## Estructura de archivos clave

```
Scrappy/
├── CLAUDE.md                    ← Este archivo (estado)
├── CONTRIBUTING.md              ← Proceso: commits, PRs, TDD, docs — reglas con ID citable
├── .github/PULL_REQUEST_TEMPLATE.md
├── .github/workflows/           ← backend-tests, cli-tests, frontend-tests, docker-smoke
├── SKILL.md                     ← Índice de documentación
├── INSTALAR_Y_CORRER.bat        ← Windows: aprovisiona _tools/ e invoca `-m cli`
├── Ejecutar_instalar.sh         ← Mirror POSIX
├── docker-compose.yml + Dockerfile + docker.env.example
├── cli/                         ← CLI nativo (Python)
│   ├── core/                    ←   headless: config, env_file, builder, rest,
│   │                                processes, commands, logs, errors
│   ├── tui/                     ←   consola Textual
│   ├── plain/                   ←   fallback texto plano
│   └── __main__.py              ←   detección de capacidad + routing
├── docs/                        ← ARCHITECTURE, API_REFERENCE, ADD_SCRAPER,
│                                  ML_PIPELINE, LLM_EMBED, LLM_AGENT_SETUP
├── openspec/                    ← Artefactos SDD (changes/ activos, changes/archive/ cerrados, specs/)
├── scripts/
│   ├── dev-db.sh                ← Postgres de dev on-demand (up/down/status)
│   └── hooks/commit-msg         ← bloquea COMMIT-1 y COMMIT-3 (activar: git config core.hooksPath scripts/hooks)
├── ml-tests/                    ← pytest del pipeline Python
├── tests/cli/                   ← pytest del CLI nativo
└── scraper/
    ├── pom.xml
    └── src/main/
        ├── java/ar/scraper/
        │   ├── App.java                    ← Entry point Spring Boot
        │   ├── config/                     ← ScraperConfig, RequiredEnvVarsGuard
        │   ├── model/Product.java          ← Record de 19 campos
        │   ├── pages/                      ← Page Object Model
        │   ├── scrapers/                   ← BaseScraper, ScraperFactory, *Scraper
        │   ├── aggregator/                 ← ResultAggregator + collaborators SOLID
        │   │   ├── normalize/              ←   PackQuantityDetector, CategoryClassifier,
        │   │   │                               BrandExtractor, GenderResolver, SizeNormalizer,
        │   │   │                               SubcategoryResolver, RubroResolver, GymratTagger
        │   │   ├── grouping/               ←   GroupingService, ProductIdentity, JaccardSimilarity
        │   │   └── text/AccentStripper     ←   hot path: 10 clases lo usan
        │   ├── ml/                         ← PythonRunner, MlEnricher, SenalCalculator
        │   ├── agent/                      ← LLM Catalog Agent (ChatProvider + tools)
        │   ├── health/SiteYieldGuard       ← detecta colapso por sitio vs. la corrida previa
        │   ├── db/DatabaseService.java     ← PostgreSQL (HikariCP), 15 tablas
        │   └── web/                        ← ApiController + *Endpoints + servicios
        │       ├── OutfitService           ←   armador aleatorio (Gym)
        │       ├── OutfitBudgetBuilder     ←   MCKP + greedy
        │       ├── OutfitRules             ←   género, estilo, SlotPick (compartido)
        │       ├── VisualCoherence         ←   estampado / fit / color
        │       ├── SupplementCombo         ←   combo de suplementos
        │       └── RecommendationService   ←   baseMlScore, ranking "Para ti"
        └── resources/
            ├── application.properties, logback-spring.xml, config.properties
            ├── db/migration/               ← Flyway
            └── ml/                         ← ml_pipeline.py, ml_train.py, ml_embeddings.py
```

`scraper/ml_*.py` junto al jar son **artefactos de extracción runtime**
(gitignoreados). La única fuente de verdad es `scraper/src/main/resources/ml/`.

---

## Sitios configurados (`config.properties`)

| Sitio | Plataforma | Rubro | Notas |
|-------|-----------|-------|-------|
| freres, vcp, forever | Shopify | moda | `forever` está en el name-set SHOPIFY desde 2026-07-14 (antes caía a TN y daba 0 productos) |
| foreverbstrd | Tiendanube | moda | URL estilo Shopify (`/collections/all`) pero es TN real — **NO** agregarlo al name-set |
| harvey | Tiendanube | moda | Única con `urls_extra` (outlet `otras-temporadas1`, pagina con `?mpage=N`) |
| midway, batuk, tussy, bulks, bullbenny, barnes, eldon | Tiendanube | moda | Batuk+Huoky misma tienda (huoky comentado) |
| fuark, fursten | Tiendanube | gym | Fursten pagina solo vía fallback `?page=N`. No existe flag `GYM_SITIOS` |
| monkyforce | Monkyforce (propio) | gym | |
| entreno | Tiendanube | suplementos | |
| sporting | VTEX | deportes | |
| vaypol, city | Vaypol (Rails SSR custom) | deportes | |
| dcshoes | WooCommerce | moda | |
| maximus, fullh4rd | Scrapers propios | tecnologia | Hardware/PC |
| compragamer | Scraper propio (feed JSON) | tecnologia | Lee `static.compragamer.com/productos` directo (~1400 items, sin auth, sin paginar) — no scrapea el DOM de la SPA Angular |
| vans | — | — | Comentado: plataforma Grimoldi custom, sin scraper |

### Detección de plataforma (`ScraperFactory.crear`, en orden)

```
WOOCOMMERCE → {dcshoes, woocommerce}
MAXIMUS → {maximus}   FULLH4RD → {fullh4rd}   COMPRAGAMER → {compragamer}
VAYPOL  → {vaypol, city}
VTEX    → {sporting} o url contiene vtexcommercestable.com.br / vteximg.com.br
SHOPIFY → {freres, vcp, forever} o url contiene myshopify.com
MONKYFORCE → {monkyforce}
default → TiendanubeScraper (JS heurístico)
```

`plataformaDeFavorito`/`crearParaFavorito` resuelven favoritos solo a SHOPIFY/VTEX.

---

## API REST

Detalle completo en [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md).

| Grupo | Endpoints |
|-------|-----------|
| Scraping | GET `/api/status` · POST `/api/scrape` |
| Catálogo | GET `/api/data` · `/api/facets` · `/api/csv` · DELETE `/api/data?url=` (soft-delete) |
| ML | GET `/api/tendencias` · `/api/historial` · `/api/ml/estado` · `/api/ml/resultado` · POST `/api/ml/aplicar` · `/api/ml/renormalizar` · `/api/ml/entrenar` |
| Comparador | GET `/api/grupos` · `/api/buscar-externo` (MercadoLibre) |
| Financiación | CRUD `/api/financiacion/presets` · GET `/api/recomendacion` · `/api/inflacion` (INDEC) |
| Outfits | GET `/api/outfits` · `/api/outfits/builder` · `/api/suplementos/builder` · `/api/suplementos/tipos` · POST `/api/outfits/feedback` · CRUD `/api/outfits/saved` |
| Para ti | GET `/api/recomendados` · POST `/api/recomendados/feedback` · POST/DELETE `/api/recomendados/dismiss-categoria` |
| Favoritos | GET/POST/DELETE `/api/favoritos` · POST `/api/favoritos/rescrape` |
| Picks/Marcas | GET `/api/mejores?rubro=` · `/api/marcas-browser` |
| Sitios/Config | GET/POST/DELETE `/api/sitios` · PUT `/api/config` |
| Cron | GET/POST `/api/cron` · GET/PUT/DELETE `/api/cron/{id}` · `/api/cron/{id}/executions` · POST `/api/cron/{id}/run-now` |
| DB | GET `/api/db/export` · POST `/api/db/import` (**410 Gone**, usar `pg_dump`/`pg_restore`) · DELETE `/api/db/productos` (**409** si hay favoritos protegidos, sin `?force=`) · `/api/db/ml` |
| LLM Agent | POST `/api/agent/chat` · `/api/agent/apply` (ambos gateados por scraping) · GET `/api/agent/models` (no gateado) |

---

## Base de datos PostgreSQL

```
productos            -- Catálogo canónico (upsert por URL; cols ML, rubro, gymrat, pack, visual attrs)
producto_talle       -- Talles por producto, ordenados (url+posicion PK) (V7)
producto_badge       -- Badges ML por producto, principal primero (url+posicion PK) (V7)
cron_job_sitio       -- Sitios de cada cronjob, ordenados (job_id+posicion PK) (V9)
precio_historico     -- Precio por fecha (UNIQUE url+fecha)
ml_output            -- Último output JSON del pipeline
image_embeddings     -- Cache de embeddings Marqo (url PK, bytea, model_version)
categoria_stats      -- Stats de precio por categoría, 12 columnas tipadas + FK a categoria (V16)
sitios_dinamicos     -- Sitios agregados desde el dashboard
sitio                -- Identidad de sitio: plataforma, es_premium, rubro_forzado (V18, leída por SiteRegistry desde V20)
favoritos            -- Productos guardados
precios_externos     -- Comparativas MercadoLibre
outfit_feedback_item -- Likes/dislikes por ítem (la tabla legacy por-outfit se borró en V15)
saved_outfits        -- Outfits persistidos
categoria_dismiss    -- Categorías "no me interesa" del feed
financiacion_presets -- Presets de cuotas/recargo
cron_jobs / cron_executions -- Scraping programado + historial
agent_reclassify_audit      -- Auditoría de reclasificaciones humanas (V2)
```

### Migraciones

Flyway, en `scraper/src/main/resources/db/migration/`. **Una migración aplicada
es byte-frozen**: Flyway valida checksums y hasta agregar un comentario rompe
`flyway validate`. Por eso el SQL de rollback vive en
[`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md), donde además lo **ejecutan**
los `V*RollbackRoundTripTest` para que el documento no pueda desincronizarse.

| | Qué hace |
|---|---|
| `V1` | Baseline: 15 tablas + `sp_upsert_run`/`sp_soft_delete_ausentes` |
| `V2` | `agent_reclassify_audit` |
| `V3` | Lock de clasificación manual |
| `V4` | FKs hacia `productos(url)`, política `ON DELETE` por tabla |
| `V5` | 8 columnas INTEGER-boolean → `BOOLEAN`; 2 fechas → `DATE` |
| `V6` | Tres CHECK de dominio sobre `genero`/`rubro`/`ml_segment` |
| `V7` | `talles` y `ml_badge` → `producto_talle` / `producto_badge` |
| `V8` | Las 19 columnas `*_at` restantes → `TIMESTAMPTZ` |
| `V9` | `cron_jobs.sitios_json` → `cron_job_sitio` |
| `V10` | `saved_outfits.*_json` → `jsonb` (revertido por `V14`) |
| `V11` | Valida `fk_favoritos_url`, sólo si no hay huérfanos |
| `V12` | Cierra el vocabulario de `categoria`, con bucket `Otros` |
| `V13` | Tabla `categoria(nombre PK)` + FK, clave natural |
| `V14` | `saved_outfit_item`: los blobs SÍ eran dato del dominio |
| `V15` | Borra `outfit_feedback` (legacy) |
| `V16` | `categoria_stats.payload` → 12 columnas tipadas + FK |
| `V17` | `precio_orig` → `double precision`, tres parsers colapsan en uno |
| `V18` | Tabla `sitio` (identidad de sitio), sembrada |
| `V19` | `BrandExtractor` abstiene en vez de caer al nombre del sitio |
| `V20` | `sitio` pasa a ser la fuente de `plataforma`; `RubroResolver` por igualdad |
| `V21` | Tabla `marca` + FK, clave natural |
| `V22` | Dropea `productos.marca_premium` (3FN) |
| `V23` | `productos.sitio_key` (generada) + FK a `sitio(sitio_key)` |
| `V24` | `sitio.plataforma` 9→11 valores (`qloud`, `oscommerce`) + seed Rockethard/Venex |
| `R__sp_upsert_run` | **La** definición de la función. Repetible: se edita acá |
| `R__sp_soft_delete_ausentes` | Ídem |

> ⚠️ **Las dos funciones plpgsql se editan en su archivo `R__`, y en ningún
> otro lado.** No agregues una migración versionada para tocarlas. Flyway
> re-aplica una repetible cuando cambia su checksum, y las corre después de
> todas las versionadas. Las copias históricas en `V1`/`V3`/`V5`/`V7`/`V17`/
> `V21`/`V22` son inmutables y quedan sólo como registro.

**El porqué de cada una está en [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)**,
incluidos los criterios que se repiten: cuándo tabla de lookup y cuándo CHECK,
qué lleva FK y qué no (un registro histórico nunca depende de que el dato
mutable siga existiendo), y por qué un valor ausente se dice con `NULL` o `""`
y nunca con un centinela.

### Estado normal

El esquema está en **1FN** y **2FN**. 3FN está parcialmente alcanzada: `V22`
cerró `marca_premium`, que era la violación más filosa (`url → sitio →
es_premium`), y `V23` le puso integridad referencial al sitio. Queda
`ml_output.payload` como **único** blob del esquema, deliberado: es un log de
corridas —se poda a 10 filas, nunca se consulta adentro, siempre se lee
entero— no dato del dominio.

> 1FN pide **dos** cosas, no una: sin grupos repetitivos **y** con valores
> atómicos por celda. Ese matiz es el que hizo que la afirmación anterior
> fuera falsa durante varias migraciones — no quedaban grupos repetitivos,
> pero `categoria_stats.payload` seguía siendo un registro entero serializado
> en una sola celda. El desarrollo está en
> [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).

**Upsert:** URL nueva → INSERT + historial · precio igual → `touched_at` ·
precio cambió → UPDATE + historial · ausente en el run → soft-delete
(`activo=false`). Corre **server-side** en `sp_upsert_run`/
`sp_soft_delete_ausentes`. La concurrencia la resuelve Postgres MVCC: no hay
locks de aplicación.

⚠️ **El upsert se traga los errores SQL**: `ProductRepository` loguea y
devuelve `UpsertStats(0,0,0,0)`, que sale como `"0 nuevos"` y nunca como error.
Todo test de round-trip afirma `nuevos()` **antes** que cualquier valor de
columna, porque `0` es la firma exacta de un fallo tragado.

### Lecturas

**`/api/data` y `/api/facets` consultan SQL** desde `sql-catalog-filtering`:
los 18 filtros, el orden y la paginación son `WHERE`/`ORDER BY`/`LIMIT`,
`talle` y `badge` salen por `EXISTS` contra las tablas hijas, y las facetas son
un `GROUP BY` cada una. `senal` y `finan` no se persisten: se calculan sobre
los ~24 productos de la página. El resto de las superficies (`/api/grupos`,
`/api/mejores`, outfits, recomendados, agente) lee el snapshot en memoria.

---

## Pipeline ML

Detalle y guía de extensión en [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md).

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

**`ml_train.py`:** entrena SOLO el clasificador de texto (TF-IDF + LogisticRegression,
~30s) → `_models/text_classifier.pkl`. `--images` es no-op: la clasificación
visual es zero-shot, sin entrenamiento.

**`ml_embeddings.py`:** `hf-hub:Marqo/marqo-fashionSigLIP` vía `open_clip`,
zero-shot con prompts en inglés y labels en español, abstención por margen.
Cache en `image_embeddings` (invalidada por `MODEL_VERSION`). `HF_HOME` =
`<SCRAPER_MODELS_ROOT>/marqo`.

**Clustering:** `cluster_productos` usa norms cacheadas + índice invertido
término→cluster + conteos O(1). Al tocarlo, construí los corpus de test con
tokens **alfabéticos**: el tokenizer descarta dígitos, así que un vocabulario
`tok1, tok2…` colapsa en UN cluster y esconde tanto el blowup como cualquier
regresión.

---

## Armadores de outfits

Dos algoritmos con objetivos distintos, ambos leyendo el catálogo en memoria
(no la DB), igual que `/api/data` y `/api/mejores`.

| | `OutfitService.armar` | `OutfitBudgetBuilder.armarPorCategorias` |
|---|---|---|
| Superficie | Gym (`/api/outfits`) | Presupuesto (`/api/outfits/builder`) |
| Objetivo | Variedad entre recargas | Óptimo global bajo presupuesto duro |
| Algoritmo | Muestreo aleatorio ponderado | MCKP con branch-and-bound (+ modo greedy) |
| Slots | torso, piernas, calzado + accesorio best-effort | Sub-slots: torso-base/outer, piernas, calzado, accesorio-head/feet/body |

**El peso de `armar` es el producto de cuatro factores, todos neutros en 1.0
cuando no hay señal:** cercanía a la mediana de la banda de precio (±30%) ×
boost de likes (cap 4.0) × `mlFactor` (oportunidad ML, cap 2.5) ×
`VisualCoherence` (estampado/fit/color). Ninguno es un filtro.

`mlFactor` = `clamp(baseMlScore(p)/50.0, 0.5, 2.5)`. **50.0 es
`baseMlScore(MlScore.EMPTY)`** — anclar ahí hace que un producto sin datos de ML
dé exactamente 1.0, así que un catálogo sin pipeline conserva los pesos previos.
El cap queda por debajo del de likes a propósito: un like es gusto, un badge es
una observación de precio.

**`VisualCoherence`** (pura, estática, compartida por los tres armadores) aplica
tres reglas sobre `Product.visual()`: un solo estampado por outfit (×0.5),
sin repetir fit extremo (×0.7, solo torso/piernas — `regular` es el neutro y
oversize-arriba/entallado-abajo es un look válido), y coordinación de color
(×0.7) por **rueda de tonos** (`rojo naranja amarillo verde celeste azul violeta
rosa`, circular; armonía = distancia ≤ 2). Los neutros (`negro blanco gris beige
marron`) no tienen posición en la rueda y combinan con todo. Un atributo vacío
**nunca** dispara una regla: vienen de un clasificador que se abstiene.

En el MCKP la penalización se aplica como **resta de un monto no-negativo**, así
que la cota superior del branch-and-bound sigue siendo válida y no se poda
ninguna rama óptima.

**Vetos duros** (estos sí son filtros, y corren aguas arriba del peso):
`genero=infantil` nunca es elegible · `Mochila`/`Bolso` fuera de accesorio ·
`Botines` fuera de calzado · marca `DC` fuera de calzado en Gym · el par
`marca|categoria` con dislike queda excluido de forma permanente.

**Combo de suplementos** (`SupplementCombo`): cada producto se asigna a
**exactamente un** subtipo en una pasada por precedencia (específico antes que
genérico — una barra de proteína es una barra, no un polvo). El nombre manda;
`p.categoria()` es fallback. Ranking del pick: marca preferida → precio por
unidad de medida → `baseMlScore` → url.

La **marca preferida** es un orden, no un conjunto: ENA → Gold Nutrition →
Star Nutrition → BSA → Xtrenght. Compara contra `Product.marca()`, que sale de
`BrandExtractor` — así que una marca sólo puede ganar acá si además está en
`BrandExtractor.MARCAS`. Las dos listas viajan juntas o la preferencia es código
muerto (lo fue: hasta 2026-08-11 la lista curada no tenía ni una marca de
suplementos, y todos caían al fallback por sitio). Ahí van sólo formas que se
sostienen solas bajo `\b`: `Star` y `Gold` pelados matchearían "All Star" y
"Gold Standard".

> ⚠️ `NO_ALFANUMERICO` tiene que seguir siendo el **primer** campo estático de
> `SupplementCombo`: varios inicializadores debajo normalizan keywords al
> construirse, y un `Pattern` declarado después llega null a su propio uso.
> `ExceptionInInitializerError` es el único síntoma.

---

## LLM Catalog Agent (`ar.scraper.agent`)

Agente de chat con tool-use, provider-pluggable, para revisar y corregir la
clasificación de productos por lenguaje natural. Seam `ChatProvider` con un
adapter hoy: `OpenAiCompatProvider` (Ollama).

**Exactamente 3 herramientas, TODAS de solo lectura**, dentro de un loop acotado
(`MAX_ITERATIONS=6`): `search_products`, `view_product`, `propose_reclassify`.
La reclasificación es **two-phase propose/confirm** — `propose_reclassify` valida
y devuelve un diff, nunca escribe. El único write real es `POST /api/agent/apply`,
fuera del loop, tras confirmación humana explícita y re-validando server-side.

**Continuidad del chat:** cada mensaje assistant carga su `trace` (las tool calls
que el modelo **pidió**, nunca lo que el catálogo respondió), el cliente lo
reenvía, y el servidor **re-ejecuta** esas llamadas contra el snapshot vivo antes
de contactar al proveedor. Un `trace` manipulado no puede inyectar un dato falso,
y la evidencia replayada está al día. Bounds: `MAX_REPLAY_CALLS=12`.

**Write path:** `aplicarReclasificacionAuditada` hace UPDATE + INSERT de auditoría
en una sola transacción con rollback completo, y su booleano de retorno **siempre**
se chequea. Staleness guard: compara `categoriaActual` contra la DB (no contra el
snapshot en memoria) y devuelve `422 conflicto_stale` en vez de sobrescribir a ciegas.
Tras escribir, parchea el catálogo en memoria y recalcula facetas.

Config por env (`LLM_PROVIDER`/`LLM_MODEL`/`LLM_BASE_URL`/`LLM_API_KEY`), todas
opcionales — **no** están en `RequiredEnvVarsGuard`.

📄 Detalle: [`docs/LLM_EMBED.md`](./docs/LLM_EMBED.md) · setup: [`docs/LLM_AGENT_SETUP.md`](./docs/LLM_AGENT_SETUP.md).

---

## Model `Product` (record, 19 campos)

```java
sitio, nombre, precio, precioOriginal (Double), url, imagenUrl, categoria, genero,
talles, ml (MlScore), marca, rubro, gymrat, marcaPremium,
senal (SenalCompra), finan (SenalFinanciacion),
cantidadUnidades, subCategoria, visual (VisualAttrs)
```

`precioOriginal` es `Double` desde `close-1nf-and-3nf-foundation` (antes
`String`): `null` es "no parseó / no había" (D1), nunca un sentinel string.
Un único parser, `ar.scraper.aggregator.text.PrecioParser`, lo resuelve al
momento del scrape — ver `V17` más abajo.

Helpers: `esPack()`, `esTech()`, `esGymrat()`, `esMarcaPremium()`.
`MlScore` incluye scoreP/badges/ofertaReal/tendencia/pctilCategoria/zScore/segment;
`MlScore.EMPTY` es `scoreP=50` sin badges.
`VisualAttrs` (fit/estampado/escote/colorDominante) es fill-only por campo, y
`EMPTY` significa "el clasificador se abstuvo", no "malo".

---

## Flujo completo de un run

```
1. Usuario configura y lanza (dashboard o cronjob)
2. POST /api/scrape → ScraperService.iniciarScraping()
3. Por sitio: ScraperFactory.crear() → BaseScraper.ejecutar()
4. ResultAggregator.agregar(): dedup → NormalizerService → PythonRunner
   (ml_pipeline.py + stage 1b visual) → MlEnricher → DatabaseService.upsertProductos()
5. Actualización progresiva por sitio: upsertParcial + fromDBParcial — solo las
   URLs del sitio recién terminado se re-enriquecen; el resto reusa el snapshot previo
6. En background si corresponde: re-train de texto + backfill de embeddings
7. Frontend pollea /api/status cada 1800ms → DONE → dashboard con filtros server-side
```

## Frontend (rutas)

Catálogo `/catalogo` · Picks `/picks(/:categoria)` · Para ti `/recomendados` ·
Cronjobs `/cronjobs` · Marcas `/marcas` · Suplementos `/suplementos` ·
Análisis `/analisis/mercado` · `/analisis/oportunidades(/:badge)` ·
Comparar `/grupos` · Cuotas `/financiacion` · Favoritos `/favoritos` ·
Outfits `/outfits`. `/tendencias` redirige a `/analisis/mercado`.
`MlStatusPanel`, `GpuTrainingOverlay` y `AgentChatPanel` son componentes montados
a nivel `AppLayout`, no rutas.

---

## Gotchas

**Toolchain de esta máquina (Linux):** el Java está partido — compila con JDK 24,
corre los tests con JRE 21. El comando completo está en
[`CONTRIBUTING.md`](./CONTRIBUTING.md). `clean` no es opcional: sin él `mvn test`
puede pasar contra clases viejas y fingir verde.

**Jar stale:** `cli/core/builder.py` saltea el build si `scraper/scraper.jar`
existe. Tras recompilar a mano: copiar `scraper/target/fashion-scraper-1.0.0.jar`
→ `scraper/scraper.jar`, o borrar el jar y correr `build` desde el CLI.

**`DATABASE_URL` tiene DOS formatos según el consumidor:** Java/Spring necesita
el prefijo `jdbc:` (`jdbc:postgresql://…`); psycopg2 **no** lo entiende, solo
`postgresql://…`. `PythonRunner.toPsycopgDsn` traduce antes de pasarlo al
subproceso. Si se agrega otro consumidor de `DATABASE_URL`, revisar esto.

**Fail-fast de env vars:** el backend no tiene defaults silenciosos para
`DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`/`APP_CORS_ALLOWED_ORIGINS`
en el profile default — `RequiredEnvVarsGuard` aborta el arranque nombrando cada
variable faltante. Un `DATABASE_PASSWORD` **vacío** (trust-auth local) cuenta como
presente; solo una var totalmente ausente cuenta como faltante. Fallbacks de dev
en `application-dev.properties` (`SPRING_PROFILES_ACTIVE=dev`); los tests activan
el profile `test` vía surefire, no por anotación.

**Logs de los servicios lanzados por el CLI:** backend y frontend **no** escriben
en la terminal (romperían el render de la consola). Van a
`scraper/logs/{backend,frontend}.log` y se leen con el comando `logs`. Esto es
aparte del logback del backend (`scraper.log`/`error.log`, rolling diario).

**Python embeddable:** `python311._pth` congela `sys.path` (no agrega el dir del
script ni respeta `PYTHONPATH`); `ml_pipeline.py` inserta su propio dir antes de
importar `ml_embeddings`. Esto es **solo** del embeddable de ML (`_tools/python`) —
`_tools/cli-venv` es un venv uv normal y no tiene el problema, por diseño.

**CLI (`_tools/cli-venv`):** si `import textual` falla, el instalador aborta con
mensaje accionable. Para reprovisionar: borrar `_tools/uv` y `_tools/cli-venv` y
re-correr el instalador. Se invoca `python -m cli` con cwd = raíz del repo —
**no** `cli/__main__.py` directo, que falla por los imports absolutos `cli.*`.

**Postgres portable:** vive en `_tools/pgsql` (binarios) + `_tools/pgdata`
(`initdb -A trust`, sin password local). Queda corriendo entre ejecuciones;
`pg_ctl status` chequea antes de re-arrancar. Para dev sin el instalador:
`scripts/dev-db.sh`.

**Tests contra Postgres:** `PostgresTestBase` auto-selecciona Testcontainers (si
hay Docker) o el portable local, y se skipea con mensaje si no hay ninguno —
nunca hace fallar la suite por falta de infra.

**`AccentStripper` es hot path:** lo usan 10 clases, en el path de normalización
por scrape Y en el de `/api/grupos` por request. `/api/grupos` re-agrupa todo el
catálogo filtrado en **cada** request, paginación incluida — nada se cachea entre
páginas. Ignora a propósito acentos en mayúscula y circunflejo/cedilla/tilde;
ampliarlo cambiaría la clasificación de productos, no solo la velocidad.

**Docker:**
- `VITE_API_BASE_URL` es **build-time** (Vite lo hornea en el bundle) → cambiarlo exige `docker compose up --build`.
- En `DATABASE_URL` el host es **`postgres`** (nombre del servicio), no `localhost`.
- Triángulo que tiene que cerrar: `APP_CORS_ALLOWED_ORIGINS` (`:8080`) ↔ `VITE_API_BASE_URL` (`:3000`) ↔ los port mappings.
- `pgdata`/`models`/`logs` son volúmenes nombrados → sobreviven a `docker compose down`.
- Sin Docker en el sandbox de dev: el smoke real se valida en CI (`.github/workflows/docker-smoke.yml`).

---

## Problemas conocidos / pendientes

| Problema | Estado |
|---------|--------|
| Vans 0 productos (plataforma Grimoldi custom) | Comentado en config, pendiente investigación de su API |
| Pack/unit pricing: posible drift de distribución ML en categorías con alta densidad de packs | Live — monitorear badges, no recalibrar thresholds aún |
| `precio_orig` sigue teniendo strings genuinamente no parseables en la base histórica | `close-1nf-and-3nf-foundation`/`V17`: 817/3148 filas con texto (25,95%) no parsean ni con el parser AR-locale correcto — quedan `NULL`, no `0`; medido contra datos reales, no estimado |
| Bare `except:` en `price_velocity`/carga de historial | Nit no bloqueante — migrar a `except Exception:`. El de `safe_price` se fue con la función, borrada al quedar sin callers |
| `/api/db/export`/`import` en 410 Gone — sin backup/restore por UI | Aceptado por diseño: usar `pg_dump`/`pg_restore` contra `DATABASE_URL` |
| `sp_upsert_run` reactivando un producto soft-deleted reinserta `precio_historico` aunque el precio no haya cambiado | Follow-up no bloqueante |
| Un suplemento en cápsulas que declara su dosis en gramos ("Colágeno 10 g en cápsulas") parsea como envase de 10 g | Necesita un umbral de tamaño calibrado con datos reales |
| El veto de formato y `FORMATO_ALIMENTO` de `SupplementCombo` se escribieron sin un catálogo para muestrear | Revisar contra datos reales |
| `OutfitsEndpoints.outfits` pide TODOS los subtipos, así que el combo creció de 17 a 21 filas al agregar BCAA/Pre-Workout/Gainer/Colágeno | Solo crece donde el catálogo tiene esos productos; nunca fue una decisión explícita |
| `/api/outfits` y `/api/outfits/builder` rearman el `FeedbackModel` completo (índice URL de todo el catálogo) y pegan 2 queries a la DB en **cada** request, incluido cada click de regenerar | Encontrado 2026-08-11, no arreglado — candidato a cachear por corrida |
| `Ejecutar_instalar.sh` asume java/mvn/node del sistema en vez de vendorizar como el `.bat` | Gap preexistente. La parte de `uv`/`cli-venv` sí vendoriza igual en ambos SO y se validó end-to-end en Linux; `INSTALAR_Y_CORRER.bat` nunca se corrió end-to-end (sandbox de dev = Linux) |

---

## Cómo continuar en una sesión nueva

1. Leé este archivo completo.
2. Leé [`CONTRIBUTING.md`](./CONTRIBUTING.md) antes de escribir código o commitear.
   Sus reglas tienen ID (`COMMIT-2`, `CODE-3`, `TEST-1`…): citalas en vez de parafrasearlas.
3. Si es un clon nuevo: `git config core.hooksPath scripts/hooks`.
4. [`SKILL.md`](./SKILL.md) es el índice del resto de la documentación.
5. Si hay problemas, pedí `scraper/logs/scraper.log` y `scraper/logs/error.log`.
