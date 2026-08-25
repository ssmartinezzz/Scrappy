# Fashion Scraper Argentina — Guía

> **Este archivo es una guía índice: qué hay y dónde leerlo.** No es un
> changelog ni el lugar de las justificaciones. Si acá aparece un párrafo
> explicando *por qué* se tomó una decisión, está en el archivo equivocado y
> va a [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).
>
> El reparto entre los documentos raíz es deliberado:
> **`CLAUDE.md` = guía · [`CONTRIBUTING.md`](./CONTRIBUTING.md) = proceso ·
> [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) = por qué ·
> [`docs/DATABASE.md`](./docs/DATABASE.md) = la base, entera ·
> [`SKILL.md`](./SKILL.md) = índice de documentación.**
>
> Nada de la base se documenta acá ni en `ARCHITECTURE.md`: esquema,
> migraciones, rollback y normalización viven en
> [`docs/DATABASE.md`](./docs/DATABASE.md), y `ARCHITECTURE.md` sólo lo indexa.
>
> Se carga en contexto en cada sesión, así que tiene que ser navegable: preferí
> una tabla y un puntero antes que un párrafo.
>
> Última actualización integral: 2026-08-18.

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
├── docs/                        ← DATABASE, ARCHITECTURE, API_REFERENCE, ADD_SCRAPER,
│                                  ML_PIPELINE, LLM_EMBED, LLM_AGENT_SETUP
├── openspec/                    ← Artefactos SDD (changes/ activos, changes/archive/ cerrados, specs/)
├── scripts/
│   ├── dev-db.sh                ← Postgres de dev on-demand (up/down/status)
│   └── hooks/commit-msg         ← bloquea COMMIT-1 y COMMIT-3 (activar: git config core.hooksPath scripts/hooks)
├── ml-tests/                    ← pytest del pipeline Python
├── tests/cli/                   ← pytest del CLI nativo
├── tests/e2e/                   ← e2e capa API (pytest) + `run-e2e.sh`, el runner de las dos capas
│                                  Levanta backend + preview y los apaga. NUNCA contra `vite dev` (ver Gotchas)
├── frontend/e2e/                ← e2e capa browser (Playwright): sesión, pestañas, roles, reseteo
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
        │   ├── security/                   ← PasswordHasher (Argon2id), TokenService (HS256),
        │   │                                  RefreshTokenService (rotación + reuso), RefreshCookie,
        │   │                                  AdminSeeder (siembra + adopción)
        │   │                                  ApiRoutePolicy (la matriz, como dato),
        │   │                                  SecurityConfig + JwtAuthFilter (el gate)
        │   │   └── reset/                 ←   PasswordResetService, ResetRateLimiter,
        │   │                                  ConsoleChannel (default) / SmtpChannel (opt-in)
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
| fullh4rd | Scraper propio | tecnologia | Hardware/PC. Sirve el `src` del listado **root-relative** (`/img/productos/{cat}/{slug}-0.jpg`) — se absolutiza con `ImageUrl` como cualquier otro |
| maximus | Scraper propio (API session-gated) | tecnologia | URL: `/Productos/{Slug}/maximus.aspx?/CAT={id}/SCAT=-1/M=-1/OR=1/PAGE={p}/` (el `{Slug}` es cosmético, sólo `CAT=` rutea — confirmado en vivo). Productos vía `POST /wfmWebSite2.aspx/wsNRW_Script` **desde adentro de la página ya navegada** — un cookie-less call responde HTTP 200 con `{"d":"-2, Módulo GlobalBluePoint© GBPScripts NO ADQUIRIDO."}` (el gate no se detecta por status code). `parseMaximusPayload` **lanza** `MaximusPayloadException` ante esa forma en vez de devolver una categoría vacía silenciosa — se propaga sin atrapar hasta `BaseScraper.ejecutar`. La API no trae un campo de imagen, pero sí `item_code4web`: la imagen es `{base}/Temp/App_WebSite/App_PictureFiles/Items/{item_code4web}_600.jpg` (HEAD 200 en 121/121, CAT 48/56/68/3/10, 2026-08-15). Sin código → abstención. **745 productos** en un run real de sitio completo (2026-08-13): 73 categorías descubiertas del nav, 1122 únicos, 745 dentro de `precio.maximo` |
| compragamer | Scraper propio (feed JSON) | tecnologia | Lee `static.compragamer.com/productos` directo (1389 items, sin auth, sin paginar) — no scrapea el DOM de la SPA Angular. **650 productos** en un run real tras filtrar por stock/vendible y bandas de precio (2026-08-13). Dos claves del feed hay que reconstruirlas, no usarlas crudas: la imagen es `imagenes.compragamer.com/productos/compragamer_Imganen_general_{imagenes[].nombre}.jpg` (el typo `Imganen` es de ellos; sin el prefijo, el bucket S3 da `403 AccessDenied`), y la URL de producto es `/producto/{slug}_{id}` — el router de la SPA rutea por el sufijo `_{id}` y manda `/producto/{id}` pelado al home |
| rockethard | Qloud (propio, multi-tienda) | tecnologia | Server-rendered, `?page=N`. **503 productos** en un run real de sitio completo con las bandas de precio de producción (2026-08-13) tras registrarlo — nunca había tenido fila en `sitio` ni entrada en `config.properties`. `/productos` es 404 confirmado, nunca usar esa ruta |
| venex | osCommerce (propio) | tecnologia | Descubrimiento en dos niveles: categoría top → sub-categorías leaf en su landing (la landing muestra 12 productos no representativos, nunca se cuentan). `?page=N`, se detiene en página vacía **o** repetida — pasado el final real, Venex repite la última página en vez de devolver vacío. `page.content()` sirve el DOM re-serializado por Chromium (comillas dobles + entidad `&quot;`), no el HTML crudo del servidor (comillas simples) — el parser normaliza antes de matchear. El argumento de `enhancedClick` se lee **con Jackson**, no campo por campo con regex: los nombres traen la pulgada escapada (`15.6\"`) y un `"name":"([^"]*)"` se corta ahí y tira la card entera en silencio — medido en `/notebooks/`, eso costaba 20 de 47 productos únicos (2026-08-15). **1294 productos** en un run real de sitio completo (las 19 categorías top, 2026-08-13), sub-contado por esa pérdida |
| inpro | Inpro (Tiendanube headless) | oficina | Sillas ergonómicas, standing desks, brazos de monitor, iluminación. **NO es plataforma `tiendanube`**: sirve los objetos crudos de la API de Tiendanube pero la vidriera es un Next.js propio en Vercel, y el storefront clásico no es alcanzable (`inpro.mitiendanube.com` redirige a *otra* tienda, `inproindumentaria.com.ar`; los slugs candidatos dan 410). El catálogo se lee del payload RSC (`self.__next_f`), no del DOM. Enumera por `/server-sitemap.xml` (106 productos, 16 categorías) → páginas de categoría (100 productos en 16 fetches) → los 6 handles que ninguna categoría mostró, de a uno. **101 productos** en una corrida real (2026-08-20); los 5 `pod-*` restantes son cabinas con `price: null`, se venden a consultar. El orden de las claves del JSON **no** es estable: en categoría el objeto abre con `id`, en producto con `name` — anclar en `{"id":` da 0 en la mitad de las superficies, en silencio |
| vans | — | — | Comentado: plataforma Grimoldi custom, sin scraper |

### Detección de plataforma (`ScraperFactory.crear`, en orden)

Desde `V20` esto lee `sitio.plataforma` vía `SiteRegistry`, no name-sets en
código (ver [`docs/ADD_SCRAPER.md`](./docs/ADD_SCRAPER.md)). La lista de abajo
es qué sitio hoy tiene sembrado cada valor, no un `Set.of(...)` a editar:

```
WOOCOMMERCE → dcshoes
MAXIMUS → maximus   FULLH4RD → fullh4rd   COMPRAGAMER → compragamer
VAYPOL  → vaypol, city
QLOUD   → rockethard
OSCOMMERCE → venex
INPRO   → inpro
VTEX    → sporting, o url contiene vtexcommercestable.com.br / vteximg.com.br
SHOPIFY → freres, vcp, forever, o url contiene myshopify.com
MONKYFORCE → monkyforce
default → TiendanubeScraper (JS heurístico)
```

`plataformaDeFavorito`/`crearParaFavorito` resuelven favoritos solo a SHOPIFY/VTEX.

---

## API REST

Detalle completo en [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md).
Contrato para el cliente de browser: [`docs/FRONTEND_AUTH_CONTRACT.md`](./docs/FRONTEND_AUTH_CONTRACT.md).

> 🔒 **El API está cerrado.** Desde `user-accounts-and-roles` toda ruta `/api/*`
> exige un access token salvo seis, y **una ruta sin fila en la tabla de política
> se rechaza, no se permite** — `ApiRoutePolicy.TABLE` no tiene catch-all y
> termina en `denyAll()`. Agregar un endpoint sin su fila lo deja en 403, y
> `RouteCoverageTest` lo rompe en el build antes de que llegue a producción.
>
> **El dashboard React (`frontend/`) autentica**, desde `frontend-auth-ui`:
> `frontend/src/lib/authSession.js` es el único módulo que sostiene el access
> token, el nonce CSRF y la identidad, y `authedFetch` es el único punto por el
> que pasan **todas** las llamadas de `api.js`. La sesión se recupera sola al
> recargar la página (bootstrap sin nonce, ver
> [`docs/FRONTEND_AUTH_CONTRACT.md`](./docs/FRONTEND_AUTH_CONTRACT.md)), y la
> UI es role-aware contra esta misma tabla — un VIEWER nunca ve un affordance
> ADMIN en el DOM (hidden, no disabled).
>
> Permitidas sin credencial, y son todas: `OPTIONS /**` (preflight),
> `POST /api/auth/login`, `POST`/`DELETE /api/auth/refresh`,
> `POST /api/auth/password-reset/request` y `/confirm`, `GET /`.
>
> **401 y 403 no son lo mismo**: 401 es "no sé quién sos, autenticá"; 403 es "sé
> quién sos y no podés". Un cliente que los confunde entra en loop de refresh o
> muestra un error de permisos cuando sólo se le venció el token.
>
> 👤 **Los datos personales están scopeados por dueño.** `favoritos`,
> `saved_outfits`, `outfit_feedback_item` y `categoria_dismiss` se leen y
> escriben con `usuario_id` como **primer parámetro obligatorio**, y **no existe
> ninguna variante sin scope** — un método que no existe no se puede llamar por
> error, y eso lo verifica el compilador y no un reviewer. Un ADMIN corre el
> MISMO SQL que un VIEWER con otro parámetro: el rol manda sobre el sistema, no
> sobre los datos personales ajenos.
>
> **La excepción deliberada**: el guard de `DELETE /api/db/productos` cuenta los
> favoritos de **todos**, no los del que llama. Scopearlo haría que un admin sin
> favoritos propios pasara el chequeo justo cuando es más engañoso. Hay tests que
> lo fijan para que nadie lo "haga consistente" con el resto.
>
> Una fila con `usuario_id IS NULL` queda **invisible para todos** (`NULL` no
> matchea con nadie), no visible para todos. `UnownedRowsWarner` avisa al
> arranque con los conteos por tabla y el SQL para adoptarlas.

| Grupo | Endpoints |
|-------|-----------|
| Auth | POST `/api/auth/login` · POST/DELETE `/api/auth/refresh` · GET `/api/auth/me` · POST `/api/auth/password-reset/request` · `/confirm` |
| Usuarios | GET/POST `/api/usuarios` · PUT `/api/usuarios/{username}/rol` · DELETE `/api/usuarios/{username}` · PUT `/api/usuarios/{username}/activar` — **ADMIN, sin UI** |
| Scraping | GET `/api/status` · POST `/api/scrape` |
| Catálogo | GET `/api/data` · `/api/facets` · `/api/csv` · `/api/producto/{key}` (producto + historial) · DELETE `/api/data?url=` (soft-delete) |
| ML | GET `/api/tendencias` · `/api/historial` · `/api/ml/estado` · `/api/ml/resultado` · POST `/api/ml/aplicar` · `/api/ml/renormalizar` · `/api/ml/entrenar` |
| Comparador | GET `/api/grupos` · `/api/buscar-externo` (MercadoLibre) |
| Financiación | CRUD `/api/financiacion/presets` · GET `/api/recomendacion` · `/api/inflacion` (INDEC) |
| Outfits | GET `/api/outfits` · `/api/outfits/builder` · `/api/suplementos/builder` · `/api/suplementos/tipos` · POST `/api/outfits/feedback` · CRUD `/api/outfits/saved` |
| Para ti | GET `/api/recomendados` · POST `/api/recomendados/feedback` · POST/DELETE `/api/recomendados/dismiss-categoria` |
| Favoritos | GET/POST/DELETE `/api/favoritos` |
| Picks/Marcas | GET `/api/mejores?rubro=` · `/api/marcas-browser` |
| Sitios/Config | GET/POST/DELETE `/api/sitios` · PUT `/api/config` |
| Cron | GET/POST `/api/cron` · GET/PUT/DELETE `/api/cron/{id}` · `/api/cron/{id}/executions` · POST `/api/cron/{id}/run-now` |
| DB | GET `/api/db/export` · POST `/api/db/import` (**410 Gone**, usar `pg_dump`/`pg_restore`) · DELETE `/api/db/productos` (**409** si hay favoritos protegidos, sin `?force=`) · `/api/db/ml` |
| LLM Agent | POST `/api/agent/chat` · `/api/agent/apply` (ambos gateados por scraping) · GET `/api/agent/models` (no gateado) |

---

## Base de datos PostgreSQL

📄 **Todo lo de la base vive en [`docs/DATABASE.md`](./docs/DATABASE.md)**:
esquema tabla por tabla, qué hizo cada migración `V1`..`V27` + las dos `R__`,
semántica del upsert, estado de normalización, decisiones con su porqué y el
SQL de rollback que ejecutan los tests.

Lo mínimo para no romper nada sin abrir ese archivo:

| Regla | |
|---|---|
| **Toda tabla nueva cumple 1FN y 3FN** | Precondición, no aspiración. Si no las cumple, se rediseña antes de escribir la migración |
| **Una migración aplicada es byte-frozen** | Flyway valida checksums; hasta agregar un comentario rompe `flyway validate`. Por eso el rollback se documenta, no se edita el `.sql` |
| **Las dos funciones plpgsql se editan en su `R__`** | `sp_upsert_run` y `sp_soft_delete_ausentes`. Nunca una migración versionada nueva para tocarlas |
| **El soft-delete está acotado a los sitios del batch** | "Ausente" sólo significa algo dentro de un sitio que se miró. Sin esa cota, scrapear un rubro daba por desaparecido el catálogo entero — pasó de verdad (2026-08-15) |
| **El upsert se traga los errores SQL** | `ProductRepository` loguea y devuelve `UpsertStats(0,0,0,0)`, que sale como `"0 nuevos"` y nunca como error. Todo test afirma `nuevos()` **antes** que cualquier valor de columna |
| **`favoritos` ya no tiene PK sobre `url`** | Desde `V26` la PK es subrogada y la unicidad por url vive en un índice **parcial** (`WHERE usuario_id IS NULL`). Postgres no infiere un índice parcial solo: todo `ON CONFLICT (url)` tiene que repetir ese `WHERE` o rechaza la sentencia entera, primer insert incluido |
| **`precio_historico` registra cambios, no avistajes** | Un producto que vuelve tras un soft-delete se trata por su precio, como cualquier fila existente — no como URL nueva |

**Lecturas:** `/api/data` y `/api/facets` consultan SQL (18 filtros, orden y
paginación como `WHERE`/`ORDER BY`/`LIMIT`). El resto de las superficies
(`/api/grupos`, `/api/mejores`, outfits, recomendados, agente) lee el snapshot
en memoria.

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

`rubro` tiene **cuatro** valores desde `V27`: `indumentaria` · `tecnologia` ·
`suplementos` · `oficina`. Lo resuelve `RubroResolver` por
`sitio.rubro_forzado`, **nunca** por la categoría: una silla la vende una
tienda de oficina, pero una silla suelta en una tienda de ropa no convierte a
esa tienda en otra cosa. La excepción es `suplementos`, donde la categoría sí
manda —un suplemento es un suplemento lo venda quien lo venda— y por eso gana
sobre el rubro forzado del sitio.

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
Outfits `/outfits` · Historial de precios `/historial/:key`. `/tendencias` redirige a `/analisis/mercado`.
`MlStatusPanel`, `GpuTrainingOverlay` y `AgentChatPanel` son componentes montados
a nivel `AppLayout`, no rutas.

---

## Gotchas

**El entorno de desarrollo NO tiene la forma de ninguna instalación real, y eso
esconde bugs de auth.** `vite dev` proxea `/api`, así que el frontend queda
**same-origin** con el backend. Las dos vías que se instalan de verdad son
**cross-origin**: portable/POSIX es `:5173 → :3000` y Docker es `:8080 → :3000`.
Cualquier cosa que dependa de la relación entre orígenes —`Origin`,
`Sec-Fetch-*`, `SameSite`, si el browser guarda una cookie— se comporta distinto
en dev y en producción, **y dev es la topología que nunca se instala**.

Esto ya costó dos veces. Primero se recomendó exigir `Sec-Fetch-Site:
same-origin` para el refresh de bootstrap, que habría dado 403 en las dos
instalaciones reales y sólo habría andado en dev. Después, y peor: el login se
mandaba sin `credentials: 'include'`, así que el browser descartaba la cookie de
refresh y **la recuperación de sesión al recargar nunca funcionó** en ninguna
instalación real — con 1570 tests de backend y 148 de frontend en verde encima.

Por eso `tests/e2e/run-e2e.sh` corre siempre contra `npm run preview` y **falla
ruidosamente si se descubre same-origin** en vez de pasar callado. Si tocás auth,
CORS o cookies, esa suite no es opcional: los tests unitarios no pueden ver esta
clase de bug, por construcción.

**Toolchain de esta máquina (Linux):** el Java está partido — compila con JDK 24,
corre los tests con JRE 21. El comando completo está en
[`CONTRIBUTING.md`](./CONTRIBUTING.md). `clean` no es opcional: sin él `mvn test`
puede pasar contra clases viejas y fingir verde.

**Jar stale:** `cli/core/builder.py` saltea el build si `scraper/scraper.jar`
existe. Tras recompilar a mano: copiar `scraper/target/fashion-scraper-1.0.0.jar`
→ `scraper/scraper.jar`, o borrar el jar y correr `build` desde el CLI.

**`page.content()` sirve el DOM re-serializado, no el HTML crudo del servidor:**
descubierto escribiendo `OsCommercePage` — un fixture construido a partir de
`curl` (comillas simples en un atributo `onclick`, JSON con comillas dobles
literales adentro) parseaba perfecto en test y rendía **0 productos en un run
real**. Chromium normaliza los atributos a comillas dobles y escapa las
comillas internas como `&quot;` al serializar `document.documentElement.outerHTML`
(que es lo que `page.content()` devuelve). Cualquier parser que lea un
atributo con JS/JSON embebido tiene que aceptar las dos formas (o normalizar
entidades antes de matchear) — no alcanza con probarlo contra un `curl`.

**Las URLs de imagen se absolutizan en UN solo lugar (`ar.scraper.pages.ImageUrl`):**
cada reader tenía su propia junta inline y cada una se quedaba en un punto
distinto — casi todas manejaban sólo la forma protocol-relative `//host/...`, así
que un sitio que sirve `src="/img/..."` guardaba un path pelado en
`productos.imagen_url` en **todas** sus filas. Un path relativo no es una imagen
peor: no es una imagen. `ImageUrl.absolutize` devuelve `""` cuando no puede
resolver, que es lo que el pipeline ya lee como abstención (`CODE-5`).

**Una clave del feed no es una URL:** Compragamer y Maximus exponen el
identificador de la imagen, no su dirección. Los dos necesitan que se reconstruya
la ruta del bucket alrededor de ese valor. Antes de dar por sentado que un sitio
"no tiene imágenes", buscar en el payload la clave con la que el propio sitio
arma su `<img>` — en Maximus el comentario del código afirmaba que no existía y
sí existía (`item_code4web`), y eso dejó 745 productos sin imagen.

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

**El CLI se autentica solo, y falla fuerte si no puede:** desde
`user-accounts-and-roles` fase 1, `RestClient` lee
`CLI_SERVICE_ACCOUNT_USERNAME`/`_PASSWORD` del `.env`, hace `POST /api/auth/login`
y adjunta `Authorization: Bearer`. Ante un 401 reautentica **una** vez y
reintenta; si el segundo intento también da 401, levanta `RestError` — nunca un
skip silencioso ni un loop de logins contra la cuenta que ya está fallando.
**Nunca** toca `/api/auth/refresh` ni una cookie: esa superficie es del browser.
Sin esas dos claves en el `.env` (instalación previa al cambio) el cliente se
comporta exactamente como antes: sin login y sin header.

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

**Trampas que dejó `user-accounts-and-roles` (todas cobraron al menos una vez):**

- **`PostgresTestBase.truncateAll` es una lista a mano, no un barrido del
  esquema.** Toda tabla nueva hay que agregarla ahí. Si te la olvidás no falla:
  contamina otros tests y se ve como un bug en otro lado. `rol` está excluida a
  propósito — es dato semilla de la migración, y truncarla deja el esquema sin
  vocabulario de roles.
- **Un test de esquema afirma el SQLState, no `SQLException`.** Un INSERT contra
  una tabla que todavía no existe también tira `SQLException`, así que la versión
  floja se pone verde ANTES de escribir la migración. `23514` = CHECK,
  `23505` = UNIQUE.
- **Los fixtures se escriben contra el esquema de HOY, no contra `V1`.**
  `saved_outfits.slots_json` la borró `V14`; `outfit_feedback_item.liked` es
  BOOLEAN desde `V5`. Mirar el baseline es mirar una foto vieja.
- **El placeholder `cambiame-por-una-password-real` vive en dos lados** y tienen
  que coincidir byte a byte: `.env.example` y `AdminSeeder.PLACEHOLDER`. Si se
  separan, el backend deja de negarse a sembrar con la password de ejemplo.
- **`AUTH_JWT_SECRET` y `CLI_SERVICE_ACCOUNT_PASSWORD` son pegajosos**: el CLI
  los genera una vez y NO los rota aunque regeneres el `.env` (`GENERATED_KEYS`
  en `cli/core/env_file.py`). Rotarlos cierra todas las sesiones o rompe todos
  los cronjobs contra una config que se ve perfecta, porque el seeder nunca pisa
  un hash existente.
- **`@WebMvcTest` registra los `Filter` pero no los `@Component` comunes.** Un
  test del slice de seguridad necesita importar `SecurityConfig`, `JwtAuthFilter`
  **y** `TokenService`, o el contexto no carga.
- **Un fixture tiene que sembrar el mismo rol que pone en el contexto de
  seguridad.** El rol se lee de la BASE en cada request —el token no lo lleva—
  así que decir ADMIN en el contexto y escribir VIEWER en la tabla da un sujeto
  que la app trata como VIEWER, correctamente, y un test que falla por algo que
  no tiene que ver con lo que quería probar.
- **Los relojes fijos de los tests caen en segundos exactos.** Por eso los 1540
  tests no vieron que `iat` (segundos) y `password_changed_at` (microsegundos)
  se comparaban directo, rechazando el token del usuario que acababa de cambiar
  su contraseña. **Todo cambio de auth se verifica además contra un proceso
  real**: la verificación manual encontró tres bugs que la suite no podía ver
  —dos que impedían arrancar y este—.
- **Convención de commits de la cadena**: subject conventional (`COMMIT-1`) y
  `Fase N — ...` como primera línea del body. El formato `fase:n - "msj"` lo
  rechaza `scripts/hooks/commit-msg`, y `--no-verify` apagaría también el chequeo
  de `COMMIT-3`.

**Docker:**
- `VITE_API_BASE_URL` es **build-time** (Vite lo hornea en el bundle) → cambiarlo exige `docker compose up --build`.
- En `DATABASE_URL` el host es **`postgres`** (nombre del servicio), no `localhost`.
- Triángulo que tiene que cerrar: `APP_CORS_ALLOWED_ORIGINS` (`:8080`) ↔ `VITE_API_BASE_URL` (`:3000`) ↔ los port mappings.
- `pgdata`/`models`/`logs` son volúmenes nombrados → sobreviven a `docker compose down`.
- Sin Docker en el sandbox de dev: el smoke real se valida en CI (`.github/workflows/docker-smoke.yml`).

---

## Problemas conocidos / pendientes

> Esta tabla lista **lo que está mal y sin arreglar**. Nada más.
>
> Una decisión tomada no es un problema pendiente, y mientras vivió acá mezclada
> con los bugs hizo que la lista pareciera deuda cuando no lo era. El *por qué*
> de cada decisión está en [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) y
> [`docs/DATABASE.md`](./docs/DATABASE.md), que es donde lo manda `DOC-1`.

### Bugs abiertos

| Problema | Estado |
|---------|--------|
| `Ejecutar_instalar.sh` asume java/mvn/node del sistema en vez de vendorizar como el `.bat` | Gap preexistente. La parte de `uv`/`cli-venv` sí vendoriza igual en ambos SO y se validó end-to-end en Linux; `INSTALAR_Y_CORRER.bat` nunca se corrió end-to-end (sandbox de dev = Linux) |
| `OutfitsEndpoints.outfits` pide TODOS los subtipos, así que el combo creció de 17 a 21 filas al agregar BCAA/Pre-Workout/Gainer/Colágeno | Solo crece donde el catálogo tiene esos productos; nunca fue una decisión explícita |

### Necesitan datos, no código

Ninguno de estos se puede cerrar sentado frente al editor: hace falta muestrear
el catálogo real primero.

| Pendiente | Qué falta |
|---------|--------|
| Pack/unit pricing: posible drift de distribución ML en categorías con alta densidad de packs | Monitorear badges en vivo. **No** recalibrar thresholds todavía |
| Un suplemento en cápsulas que declara su dosis en gramos ("Colágeno 10 g en cápsulas") parsea como envase de 10 g | Un umbral de tamaño calibrado con datos reales |
| El veto de formato y `FORMATO_ALIMENTO` de `SupplementCombo` se escribieron sin un catálogo para muestrear | Contrastarlos contra el catálogo real |
| La ventana de gracia de 10 s del refresh y los umbrales de rate-limit son propuestas, no mediciones | Ya no falta infraestructura: el cliente existe (`frontend/src/lib/authSession.js`) y `tests/e2e/run-e2e.sh` lo ejercita contra un backend real. Falta la medición en sí, que es un trabajo aparte — nadie corrió todavía refrescos concurrentes para ver dónde cae el número. Hasta entonces queda como está, documentado como propuesta |
| Parámetros de Argon2id sin medir en el Windows portable | Medidos acá (Linux dev): 76 ms hash / 76 ms verify con `m=16384, t=2, p=1`. Falta la máquina que importa — el costo es memory-bound y un laptop de gama baja puede ser varias veces más lento. Hasta tener ese número, los defaults quedan como están |

### Sin dueño

| Pendiente | Estado |
|---------|--------|
| Vans 0 productos (plataforma Grimoldi custom) | Comentado en `config.properties`, pendiente investigación de su API |
| Logg (`logg.com.ar`, ABP/ASP.NET) sigue sin scraper | **Fuera de scope por decisión explícita, no por fallar.** Diagnóstico completo en [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) y en el header de `V24` |

### Medido y descartado

Lo que alguna vez estuvo en esta lista y las mediciones sacaron de ella. Se deja
escrito para que no vuelva a proponerse.

| Sospecha | Qué dijo la medición |
|---------|--------|
| `/api/outfits` y `/api/outfits/builder` rearman el `FeedbackModel` y pegan 2 queries a la DB en **cada** request — "candidato a cachear por corrida" | **No es un problema de performance** (medido 2026-08-18, catálogo de 6700): `FeedbackModels.build` 0,208 ms · 2 queries con pool HikariCP 0,429 ms · `OutfitService.armar` —el trabajo real del endpoint— 0,258 ms. Total ≈ 0,64 ms por request. La caché exigiría invalidar en cinco métodos de escritura, y si se escapa uno el like de un usuario deja de afectar los outfits en silencio: correctitud a cambio de 0,64 ms imperceptibles |
| Idem, medido sin pool | ⚠️ **Trampa de medición, no un dato.** `PostgresTestBase` usa `SimpleDriverDataSource`, que abre una conexión nueva por llamada: las mismas 2 queries dan 13,5 ms así y 0,429 ms con HikariCP, 31x inflado. Cualquier medición de DB en este repo tiene que envolver el datasource de test en un `HikariDataSource` o el número es ficción |

### La banda de precios: `precio.maximo=5000000`

**Era `300000` hasta `add-inpro-office-store` (2026-08-20).** Esa banda no era un
bug —filtraba lo que decía filtrar— pero borraba en silencio justo los productos
caros de dos rubros enteros:

| Sitio | Qué se perdía con 300.000 |
|---|---|
| Maximus (medido 2026-08-13) | notebooks `CAT=56` conservaba 0 de 16, computadoras armadas `CAT=68` 0 de 59, GPUs `CAT=48` 5 de 59 — **377 de 1122, 34%** |
| INPRO (medido 2026-08-20) | **32 de 101, 32%**: TODAS las sillas ergonómicas de gama y TODOS los standing desks salvo los tres más baratos |

Con `5000000`, INPRO entra entero: **101 de 101, 0% filtrado** (verificado contra
el sitio en vivo). El producto más caro del catálogo es `LiberNovo Omni` a
$2.999.000.

| Lo que hay que saber | |
|---|---|
| **La banda es GLOBAL** | No hay override por sitio. `precio.maximo` sale de `config.properties`, lo lee `ScraperConfig`, y subirla alcanza a **todos** los sitios configurados — 26 con INPRO, 25 en `master`. Una banda por sitio sería una feature aparte |
| **`PUT /api/config` NO persiste** | `ScraperConfig.setPrecioMaximo` sólo toca el `Properties` en memoria: lo que se cambia desde el dashboard se pierde al reiniciar. El valor durable es el del archivo |
| **El número vive en cuatro lugares y tienen que decir lo mismo** | `config.properties` · el default de `ScraperConfig.getPrecioMaximo()` · `frontend/src/lib/scrapeDefaults.js` · y un test del frontend lee el `.properties` para que no puedan separarse |
| ⚠️ **Los conteos por sitio de la tabla de sitios son con la banda VIEJA** | Están fechados y medidos a 300.000, así que **subestiman** la cobertura real de ahora. Re-medirlos es trabajo pendiente, no un dato que ya tengamos |
| **Mueve las distribuciones del ML, y no hay nada que recalibrar** | Entran productos caros que antes no estaban, así que mediana, IQR y percentiles por categoría se corren. Los thresholds **no se tocan**: ninguna condición de `assign_badges` está denominada en pesos — todas son posiciones sobre distribuciones que se recalculan por corrida (`comp` 0-100, z-score modificado, cercos de Tukey, porcentajes). Medido ejercitando el código real: 88 combinaciones, escalando las distribuciones x10/x100/x1000/x0.01, **cero cambios de badge**. Lo fija `ml-tests/test_ml_pipeline_scale_invariance.py`. La única constante en pesos del archivo es el piso de `bin_size` en `_calc_mode`, y `mode` se reporta sin alimentar ningún score |

Decisiones que antes vivían acá y ahora están donde corresponde:
`/api/db/export`/`import` en 410 Gone → [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md) ·
`precio_orig` con strings genuinamente no parseables → [`docs/DATABASE.md`](./docs/DATABASE.md).

---

## Cómo continuar en una sesión nueva

1. Leé este archivo completo.
2. Leé [`CONTRIBUTING.md`](./CONTRIBUTING.md) antes de escribir código o commitear.
   Sus reglas tienen ID (`COMMIT-2`, `CODE-3`, `TEST-1`…): citalas en vez de parafrasearlas.
3. Si es un clon nuevo: `git config core.hooksPath scripts/hooks`.
4. [`SKILL.md`](./SKILL.md) es el índice del resto de la documentación.
5. Si hay problemas, pedí `scraper/logs/scraper.log` y `scraper/logs/error.log`.
