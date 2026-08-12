# Fashion Scraper Argentina — Estado del Proyecto

> Este archivo describe **qué hay hoy**, para que una sesión nueva no tenga que
> reconstruirlo desde cero. Leelo siempre antes de sugerir cambios.
>
> El reparto entre los tres documentos raíz es deliberado:
> **`CLAUDE.md` = estado · [`CONTRIBUTING.md`](./CONTRIBUTING.md) = proceso ·
> [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) = por qué.**
> Si acá aparece una justificación larga, está en el lugar equivocado.
> Índice completo de documentación: [`SKILL.md`](./SKILL.md).
>
> Última actualización integral: 2026-08-11.

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
| maximus, fullh4rd, compragamer | Scrapers propios | tecnologia | Hardware/PC |
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
precio_historico     -- Precio por fecha (UNIQUE url+fecha)
ml_output            -- Último output JSON del pipeline
image_embeddings     -- Cache de embeddings Marqo (url PK, bytea, model_version)
categoria_stats      -- Stats de precio por categoría
sitios_dinamicos     -- Sitios agregados desde el dashboard
favoritos            -- Productos guardados
precios_externos     -- Comparativas MercadoLibre
outfit_feedback / outfit_feedback_item -- Likes/dislikes (legacy + por ítem)
saved_outfits        -- Outfits persistidos
categoria_dismiss    -- Categorías "no me interesa" del feed
financiacion_presets -- Presets de cuotas/recargo
cron_jobs / cron_executions -- Scraping programado + historial
agent_reclassify_audit      -- Auditoría de reclasificaciones humanas (V2)
```

`V3` no agrega tablas: marca en `productos` la clasificación fijada a mano para
que el pipeline no la pise, y extiende `agent_reclassify_audit`.

`V4` (`normalize-db-schema-fks-1nf`, slice A.1) agrega FKs desde
`producto_url`/`url` hacia `productos(url)`, con política por tabla decidida
explícitamente: `precio_historico.url` y `precios_externos.producto_url` en
`CASCADE` (VALID — cero orfandades verificadas en vivo); `favoritos.url` en
`RESTRICT` (`NOT VALID` — igual enforcement en inserts/deletes nuevos, pero no
valida el historial completo de una instalación existente; `VALIDATE
CONSTRAINT` queda diferido a propósito). `agent_reclassify_audit.url` sigue
**sin FK** — un audit trail no puede depender de que el dato mutable siga
existiendo. Por esto, `DELETE /api/db/productos` ahora devuelve **409** (con
la cantidad bloqueante) y no borra nada si algún favorito referencia un
producto vivo — sin `?force=`, decisión explícita para no reabrir el camino
de borrado silencioso.

`V5` (`normalize-db-schema-fks-1nf`, slice A.2) retipa las 8 columnas
INTEGER-boolean a `BOOLEAN` nativo — `productos.activo`/`gymrat`/
`marca_premium`/`ml_oferta`, `cron_jobs.enabled`/`force_retrain`/`use_gpu`,
`outfit_feedback_item.liked`, `financiacion_presets.activo` (9 columnas
físicas: `activo` existe en dos tablas) — y 2 columnas `TEXT` de fecha a tipos
nativos: `precio_historico.fecha`/`precios_externos.fecha` → `DATE`.
`productos.touched_at`/`created_at` retipan a `TIMESTAMPTZ` en esta MISMA
migración (no en la fecha "genérica" que le tocaría por criterio) únicamente
porque `sp_upsert_run`/`sp_soft_delete_ausentes` los escriben y Postgres no
tiene redefinición parcial de función — el resto de las ~20 columnas `TEXT`
`*_at` del esquema queda sin tocar, es un cambio de puro tipo sin costo de
recopia de función, y viaja en su propio slice. `ps.setString()` en un
parámetro bindeado contra una columna `DATE` ya no compila contra el tipo en
runtime — `date < character varying` no tiene operador — por eso
`ProductRepository.purgarHistorialViejo()` y `PreciosExternosRepository`
bindean `fecha` como `LocalDate` (`ps.setObject`), no como `String`.

`V6` (`normalize-db-schema-fks-1nf`, slice A.3) agrega tres CHECK **VALID**
sobre `productos`: `genero IN ('hombre','mujer','unisex','infantil','')`,
`rubro IN ('indumentaria','tecnologia','suplementos')`, `ml_segment IN
('budget','standard','premium','luxury')`. `NULL` pasa en las tres (ninguna
es `NOT NULL`); el string vacío pasa solo en `genero`, el sentinel de
abstención de `GenderResolver`. La migración normaliza primero la única fila
viva con `genero='Mujer'` con mayúscula. `ProposeReclassifyTool` (agente LLM)
ahora valida `genero` contra ese mismo dominio. Por qué del dominio elegido y
de dónde salió el dato con mayúscula (y qué camino de escritura sigue sin
validar): [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).

`V7` (`normalize-db-schema-fks-1nf`, slice B) saca las dos violaciones de 1FN de
`productos` a tablas hijas: `talles` (array JSON dentro de un `TEXT`) →
`producto_talle`, `ml_badge` (CSV) → `producto_badge`. Misma forma en las dos:
PK `(url, posicion)` + FK `ON DELETE CASCADE`. **Las dos columnas viejas se
borraron** — no quedan como sombra de solo lectura. `posicion` es lo que
preserva que `badges().get(0)` siga siendo el badge principal, y lo que hace
que el rollback (re-agregar con `json_agg`/`string_agg ORDER BY posicion`) sea
lossless. `sp_upsert_run` escribe las hijas con DELETE + `INSERT … WITH
ORDINALITY` por producto: una lista de talles que se achica no puede dejar
filas viejas. `cargarProductos()` sigue siendo de 3 sentencias constantes (las
dos hijas se leen enteras y se mergean por url, nunca un lookup por producto).
El `buildRowsJson` manda ahora `talles`/`mlBadges` como arrays JSON reales, no
como string serializado ni CSV. Por qué de cada una de esas decisiones, el
riesgo residual del backfill y el SQL de rollback (ejecutable, cubierto por
`V7RollbackRoundTripTest`): [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).

**Upsert:** URL nueva → INSERT + historial · precio igual → `touched_at` ·
precio cambió → UPDATE + historial · ausente en el run → soft-delete (`activo=false`).
Corre **server-side** en las funciones plpgsql `sp_upsert_run`/
`sp_soft_delete_ausentes`. La concurrencia la resuelve Postgres MVCC: no hay
locks de aplicación (la vieja lock-dance de SQLite fue removida por completo).

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
sitio, nombre, precio, precioOriginal, url, imagenUrl, categoria, genero,
talles, ml (MlScore), marca, rubro, gymrat, marcaPremium,
senal (SenalCompra), finan (SenalFinanciacion),
cantidadUnidades, subCategoria, visual (VisualAttrs)
```

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
| `safe_price` puede parsear mal ciertos formatos de `precioOriginal` | Heurística interina aceptada (1611/6692 rechazados a 0.0 en el último run) |
| Bare `except:` en `safe_price`/`price_velocity`/carga de historial | Nit no bloqueante — migrar a `except Exception:` |
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
