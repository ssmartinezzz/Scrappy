# Arquitectura del Fashion Scraper

## Decisiones principales y su justificación

---

### ¿Por qué un fat JAR con todo incluido?

**Decisión**: Spring Boot fat JAR con Tomcat embebido, backend **API-only** (sin servir la SPA).

**Razón**: es una herramienta local mono-usuario en Windows, no un servicio desplegado. Para ese escenario, un `.bat` que descarga Java + Postgres portable + Node + Python y ejecuta `java -jar scraper.jar` es la UX más simple posible: cero-setup, sin infraestructura previa. No hay Dockerfile, no hay instalaciones previas, no hay conflictos de versiones.

**Actualización (docker-install-alternative, 2026-07-21)**: la afirmación "no hay Dockerfile" de arriba queda como contexto histórico de por qué el installer portable fue la primera opción, no como estado actual — ahora existe una alternativa Docker **aditiva** (`Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`) para quien prefiera `docker compose up` en vez del `.bat`/`Ejecutar_instalar.sh` portable. Es un camino de instalación adicional, no un reemplazo: el installer portable y `_tools/` siguen intactos y sin cambios (el launcher interactivo que el installer portable invoca en su tail pasó de `menu.ps1`/`menu.sh` al CLI nativo en `cli/` — ver `native-cli-installer` más abajo — sin afectar este camino Docker). El backend usa la imagen `mcr.microsoft.com/playwright/java:v1.44.0-jammy` (Chromium + libs ya matcheadas a `playwright.version=1.44.0`) con Temurin 21 instalado explícitamente encima (la imagen base trae JDK 17) y Python 3.11 + deps ML (`psycopg2-binary`, `torch`/`torchvision` CPU, `open_clip_torch`, `huggingface_hub`, `transformers`) para que `PythonRunner.detectarPython()` resuelva `python3` por PATH sin necesitar `_tools/`. El frontend usa un build multi-stage (`node:20-alpine` → `nginx:alpine`) con `VITE_API_BASE_URL` como build ARG. Volúmenes nombrados (`pgdata`, `models`, `logs`) preservan datos y pesos ML descargados entre `docker compose down`/`up`. Ver `docker.env.example` para la plantilla de variables de este modo (distinta de `.env.example`).

Topología del modo Docker (mapea 1:1 a los 3 servicios de abajo; el ML sigue
siendo un subprocess **dentro** del contenedor backend, no un servicio propio):

```
docker compose up
┌─────────────────────┐   CORS    ┌──────────────────────────────┐
│ frontend (nginx)    │  :8080    │ backend (Java+Python+Chromium)│
│  host :8080 → :80   │──────────►│  host :3000 → :3000           │
│  build ARG          │  fetch    │  ├─ ML subprocess (psycopg2)  │
│  VITE_API_BASE_URL  │           │  └─ Playwright/Chromium       │
└─────────────────────┘           └──────────────┬───────────────┘
        depends_on: backend                       │ DNS interna: "postgres"
                                   ┌──────────────▼───────────────┐
                                   │ postgres:16-alpine (healthcheck)│
                                   └──────────────┬───────────────┘
volúmenes nombrados:  pgdata (DB)  ·  models (pesos Marqo/HF, lazy)  ·  logs
```

`APP_CORS_ALLOWED_ORIGINS` (`:8080`) y `VITE_API_BASE_URL` (`:3000`) tienen que
cerrar entre sí. `DATABASE_URL` apunta a `postgres:5432` (nombre del servicio, no
`localhost`) o a un Postgres externo (ver `docker-compose.override.yml.example`).

**Actualización (decouple-services-postgres, Batch 3/D6)**: el backend dejó de servir `static/` (se retiró `SpaController`); el proyecto pasó de "monolito con SPA embebida" a **3 servicios independientes** (backend API, frontend Vite, ML Python subprocess lanzado por el backend), cada uno configurado 100% por variables de entorno (`spec` "Environment-Only Configuration"). Ver el diagrama de topología más abajo.

---

### ¿Por qué PostgreSQL y no SQLite/H2?

**Decisión** (decouple-services-postgres, Batch 1, design D1-D3): `postgresql` JDBC + HikariCP + Flyway, reemplazando `sqlite-jdbc`.

**Historia**: el proyecto arrancó con SQLite (un archivo `scraper.db`, cero configuración, visible/transferible) por su simplicidad para un usuario único en Windows. Esa elección tuvo un costo real: SQLite es single-writer, y a medida que se agregaron cronjobs + API + scraping concurrente, apareció `SQLITE_BUSY_SNAPSHOT` (escrituras solapadas pisándose commits) que se parcheó con un lock-dance de aplicación (`writeLock`/`readLock`/`refrescarSnapshot()` + `readConn` dedicada) — una solución cada vez más frágil para un problema que SQLite no está diseñado para resolver.

**Razón del swap**: Postgres da concurrencia real vía MVCC — múltiples escritores/lectores sin locks de aplicación. El write-path (upsert + historial + soft-delete) se movió a funciones `plpgsql` server-side (`sp_upsert_run`/`sp_soft_delete_ausentes`, design D2) para que la decisión "¿cambió el precio?" ocurra DENTRO de una sola sentencia SQL, eliminando la carrera de "leer precio actual → decidir → escribir" entre callers concurrentes. `UNIQUE(url, fecha)` + `ON CONFLICT DO NOTHING` hace el insert en `precio_historico` idempotente incluso con escritores solapados.

**Trade-off**: ya no hay un archivo único portable — Postgres corre como proceso (portátil bajo `_tools/pgsql`, provisionado por el installer, o un Postgres externo vía `DATABASE_URL`). A cambio, las migraciones son versionadas (Flyway `V1__baseline.sql`), no `ALTER TABLE` manual, y el problema de concurrencia queda resuelto estructuralmente en vez de parchado.

---

### ¿Por qué Python como subprocess en lugar de Java ML?

**Decisión**: `ml_pipeline.py` ejecutado como proceso separado desde `PythonRunner.java`.

**Razones**:
1. El ecosistema ML de Python (TF-IDF, clustering, historial) es mucho más expresivo
2. El script no tiene dependencias externas — usa solo stdlib Python (json, math, re, collections)
3. Si Python no está disponible, el scraper sigue funcionando sin ML (degradación elegante)
4. El script vive en `src/main/resources/ml/` y se extrae del JAR al directorio de trabajo en el primer run

**Trade-off**: adds ~5-15 segundos al pipeline. Aceptable dado que el scraping tarda minutos.

---

### ¿Por qué Playwright headless y no requests/BeautifulSoup?

**Decisión**: Playwright (Chromium headless) para todos los scrapers.

**Razón**: los sitios argentinos usan JavaScript intensivamente. Tiendanube en particular actualiza precios dinámicamente. Con `requests` simple obtendrías `$0,00` en todos los precios. Playwright ejecuta el JS completo y expone el DOM final.

**Optimización**: `BaseScraper` bloquea imágenes, CSS, fonts y videos durante el scraping para reducir bandwidth y tiempo.

---

### ¿Por qué JS heurístico para TiendaNube en lugar de la API?

**Decisión**: intentar API REST (`/api/v1/{storeId}/products`) y si falla, usar extractor JS.

**Razón**: la API de TN requiere `Authorization: bearer TOKEN` de OAuth. Sin el token, devuelve array vacío. Implementar OAuth completo requeriría que el usuario registre una app en TN y obtenga credenciales, lo cual es demasiada fricción.

**Fallback JS**: busca `data-price` (atributo TN nativo, siempre presente, entero sin formateo), luego scan manual del texto para precios, luego clase CSS. Funciona en la mayoría de temas pero solo captura la primera página a menos que `nextPageUrl()` encuentre el link de siguiente página.

---

### ¿Por qué upsert en lugar de truncate+insert?

**Decisión**: upsert por URL + soft-delete para productos ausentes.

**Razones**:
1. **Historial de precios**: si truncamos, perdemos la historia. El upsert solo registra en `precio_historico` cuando el precio cambia.
2. **Memoria estable**: la tabla `productos` nunca crece más allá del catálogo real (~5k-10k filas). Sin upsert, cada run duplicaría los datos.
3. **Soft delete**: un producto que desaparece temporalmente (stock agotado, sitio caído) se marca `activo=0` pero mantiene su historial. Si vuelve, se reactiva.

---

### ¿Por qué cada FK a `productos` tiene una política de borrado distinta?

**Decisión** (`normalize-db-schema-fks-1nf`, V4): las cuatro tablas que
referencian `productos(url)` no comparten una política uniforme. Cada una recibe
la que le corresponde según qué tipo de dato guarda.

| Tabla | Política | Razón |
|-------|----------|-------|
| `precio_historico` | `ON DELETE CASCADE` | Dato derivado. No significa nada sin el producto que describe. |
| `precios_externos` | `ON DELETE CASCADE` | Ídem: comparativas de MercadoLibre atadas a un producto del catálogo. |
| `favoritos` | `ON DELETE RESTRICT` | Dato del usuario. Una limpieza de catálogo no tiene autoridad para destruirlo. |
| `agent_reclassify_audit` | **sin FK** | Un audit trail no se ata a datos mutables: si el borrado del producto se lleva puesto el registro de quién lo reclasificó, deja de ser auditoría. |

**Por qué no una política uniforme**: CASCADE en todo es lo cómodo, y borra en
silencio los favoritos del usuario y el rastro de reclasificaciones humanas cada
vez que alguien vacía el catálogo. RESTRICT en todo obliga a `limpiarProductos()`
a borrar tabla por tabla en orden explícito, lo que es correcto pero convierte
datos derivados en ceremonia. La distinción real no es técnica: es qué dato se
puede regenerar con un scrape y cuál no.

**Consecuencia visible**: `DELETE /api/db/productos` devuelve **409** si hay
favoritos vivos, y no borra nada. No hay `?force=` — un override reintroduce
exactamente el borrado silencioso que la política evita. El conteo y el DELETE
comparten transacción: chequear en la capa del endpoint dejaría una ventana
TOCTOU donde un favorito agregado en el medio convierte el 409 en un 500 crudo.

**`favoritos` se crea `NOT VALID`**: la constraint valida desde el primer día
los INSERT y UPDATE nuevos y dispara igual el RESTRICT al borrar el padre; lo
único diferido es el chequeo de backfill sobre filas históricas. Una instalación
vieja con huérfanos no puede quedar bloqueada, y la migración no tiene permiso
para borrar datos de usuario para satisfacerse a sí misma.

---

### ¿Por qué el aggregator está modularizado en collaborators de responsabilidad única?

**Decisión**: `ar.scraper.aggregator` se organiza como orquestadores delgados (`NormalizerService`, `GroupingService`, `ResultAggregator`) que secuencian collaborators de responsabilidad única, agrupados en subpaquetes por tema (`normalize/`, `grouping/`, `text/`), más `FacetCalculator` como utility estática en la raíz del paquete.

**Razón**: antes de esta modularización, `NormalizerService` (categoría, talles, género, marca, pack/combo, subcategoría, rubro, gymrat) y `ResultAggregator` (validación, dedup, pipeline ML, persistencia, facets) eran clases monolíticas: cada regla de negocio nueva crecía el mismo archivo y era imposible testear una regla sin arrastrar todas las demás. La modularización es **behavior-preserving** — cero cambios observables end-to-end, con la suite existente pasando sin editar un solo test. El historial slice por slice se retiró de `docs/` una vez completada; queda en el historial de git.

**Estructura resultante**:

| Paquete | Responsabilidad | Clases |
|---------|------------------|--------|
| `aggregator` (raíz) | Orquestación de la agregación completa + utility de facets | `ResultAggregator` (orquestador: validar → dedup → pipeline ML → persistir → facets), `FacetCalculator` (cálculo puro y estático de facets) |
| `aggregator.normalize` | Normalización de un `Product`, orquestada por `NormalizerService` | `PackQuantityDetector`, `CategoryClassifier`, `BrandExtractor`, `GenderResolver`, `SizeNormalizer`, `SubcategoryResolver`, `RubroResolver`, `GymratTagger` + holders estáticos de datos/predicados: `GarmentTaxonomy`, `CategoryGroups`, `SiteClassification`, `NonTextileGuard` |
| `aggregator.grouping` | Agrupación de productos equivalentes entre sitios, orquestada por `GroupingService` | `ProductIdentity`, `JaccardSimilarity`, `ProductGroup` |
| `aggregator.text` | Utilidades de texto compartidas entre `normalize` y `grouping` | `AccentStripper` |

**Patrones aplicados**:
- **Orquestadores puros**: `NormalizerService.normalizarProducto` y `ResultAggregator.agregar` son el único lugar donde se reconstruye el record `Product` o se arma el `AggregatedResult` — secuencian sus collaborators (inyectados por constructor) y no contienen lógica de negocio propia. Ningún collaborator conoce a los demás.
- **Holders estáticos de datos/predicados**: `GarmentTaxonomy`, `CategoryGroups`, `SiteClassification` y `NonTextileGuard` no tienen estado ni dependencias — se consumen vía static import dentro de los collaborators que los necesitan, en vez de inyectarse como beans adicionales en `NormalizerService`.
- **`FacetCalculator` como utility estática, no bean**: a diferencia de los collaborators de `normalize`/`grouping` (todos `@Component`), `FacetCalculator` es `final` con constructor privado y un único método estático — refleja que el cálculo de facets no tiene estado ni dependencias. `ResultAggregator.calcularFacets` se mantiene como delegate público porque ~10 tests fuera del paquete (`ar.scraper.web`) construyen fixtures de `AggregatedResult` contra esa firma exacta.
- **Test factory para tests de orquestación**: `NormalizerService` requiere 8 collaborators por constructor, así que los tests que ejercitan la normalización end-to-end usan `NormalizerServiceTestFactory.create()` (solo en `src/test/java`) en lugar de instanciar los 8 collaborators a mano en cada test.

---

### ¿Por qué React + Vite en el frontend?

**Decisión**: SPA en React 18 + Vite 5, servida como su **propio servicio** (no más static resource embebido en el JAR).

**Nota histórica**: este documento describía el frontend como "HTML/JS vanilla servido como static resource desde Spring Boot" — eso dejó de ser preciso mucho antes de `decouple-services-postgres` (el frontend ya era React/Vite, buildeando a `scraper/src/main/resources/static/`). El swap de `decouple-services-postgres` (Batch 3, design D6) es un cambio distinto y posterior: dejar de embeber el build de Vite en el JAR — el backend ahora es API-only (`SpaController` removido) y el frontend corre como servicio independiente, hablándole al backend por CORS (`APP_CORS_ALLOWED_ORIGINS`) usando `VITE_API_BASE_URL` como base de sus fetches (`frontend/src/api.js`).

---

### ¿Por qué el LLM vive fuera del backend y no puede escribir solo?

**Decisión**: el modelo corre en un proceso aparte (Ollama por defecto) al que Java le habla por HTTP detrás de la costura `ChatProvider`; el agente tiene tres herramientas, **todas de solo lectura**, y la única escritura real ocurre fuera de su loop, tras confirmación humana explícita y con re-validación server-side.

**Por qué**: embeber un runtime de inferencia en el JAR ataría el proyecto a un modelo y a un backend de hardware. Como proceso externo, el LLM es una dependencia **opcional**: si no responde, solo falla el chat del agente. Y como el loop autónomo no puede escribir, el tool-calling poco confiable de un modelo local de 14B degrada, en el peor caso, a *una propuesta rechazable* — nunca a una escritura corrupta.

📄 **Detalle completo en [`LLM_EMBED.md`](./LLM_EMBED.md)**: topología de la integración, la costura `ChatProvider` y su único adapter, el loop acotado (`MAX_ITERATIONS`), las tres herramientas, y las **ocho reglas** que gobiernan al agente — empezando por la Regla 0, que el system prompt es guía y no un control de seguridad. Para instalar y configurar, ver [`LLM_AGENT_SETUP.md`](./LLM_AGENT_SETUP.md).

---

### ¿Por qué los armadores de outfits puntúan con pesos y nunca con filtros?

**Decisión**: hay dos armadores con objetivos distintos —`OutfitService.armar` (aleatorio ponderado, superficie Gym) y `OutfitBudgetBuilder` (MCKP con branch-and-bound, superficie de presupuesto)— y **toda** señal que incorporan (oportunidad ML, likes del usuario, coherencia visual) entra como multiplicador acotado, nunca como descarte.

**Por qué dos armadores**: son dos preguntas distintas. "Mostrame un outfit" quiere variedad entre recargas, así que muestrea; "armame el mejor outfit con $X" quiere el óptimo global bajo una restricción dura, que es literalmente un Multi-Choice Knapsack. Un solo algoritmo haría mal las dos.

**Por qué pesos y no filtros**: el catálogo argentino es chico y desparejo por categoría. Un filtro convierte "este short combina mal" en un **slot vacío**, y un outfit incompleto es peor producto que uno un poco ruidoso. Un peso degrada el candidato malo y lo deja alcanzable.

**Por qué el neutro se ancla en 1.0**: cada factor se normaliza contra el valor que produce una señal ausente — `mlFactor` divide por `baseMlScore(MlScore.EMPTY)`, la coherencia devuelve 1.0 cuando no hay atributos. Así, un catálogo sin datos de ML o sin clasificación visual produce **exactamente** los pesos previos. Es lo que permite agregar una señal nueva sin invalidar la suite existente como red de regresión.

**Por qué la coherencia visual no rompe el óptimo del MCKP**: la penalización se aplica como **resta de un monto no-negativo** al score del candidato, así que `aporte ≤ score` siempre y la cota superior del branch-and-bound —construida con scores sin penalizar— sigue siendo válida. Ninguna rama óptima se poda. Además cada par de slots se evalúa exactamente una vez, cuando se asigna el segundo de los dos, así que el total no depende del orden en que el solver recorre los slots.

**Por qué la regla de color es una rueda de tonos y no una tabla de pares**: una lista escrita a mano de "estos colores combinan" es un conjunto de opiniones que nadie puede revisar y que crece cada vez que alguien discrepa con una entrada. Un orden circular de tonos con un umbral de distancia es una estructura chequeable y un solo parámetro que tunear. Los neutros no tienen posición en la rueda — por eso combinan con todo, sin necesidad de enumerarlo.

**Por qué un atributo visual vacío no penaliza**: vienen de un clasificador zero-shot que se abstiene cuando duda, así que buena parte del catálogo no los tiene. Una regla que castigara el dato faltante no estaría coordinando outfits: estaría degradando en silencio a todo producto que el clasificador salteó.

---

## Diagrama de capas y topología de servicios

**Topología (decouple-services-postgres, Batch 3, design D6)**: 3 servicios independientes, cada uno arrancable solo con env vars — ninguno requiere que los otros estén corriendo para bootear (spec "Independent Service Startup").

```
┌───────────────────────────┐        ┌───────────────────────────┐
│   Frontend (Vite/React)   │  CORS  │   Backend (Spring Boot)    │
│   VITE_API_BASE_URL ──────┼───────►│   APP_CORS_ALLOWED_ORIGINS │
│   propio proceso/puerto   │  fetch │   API-only (sin SPA)       │
└───────────────────────────┘        └──────────────┬──────────────┘
                                                      │ lanza subprocess
                                      ┌───────────────▼───────────────┐
                                      │  Python ML (subprocess)        │
                                      │  DATABASE_URL (psycopg2 DSN,   │
                                      │  traducido desde el jdbc: de   │
                                      │  Java por toPsycopgDsn)         │
                                      │  SCRAPER_MODELS_ROOT / HF_HOME │
                                      └───────────────┬───────────────┘
                                                      │
                                      ┌───────────────▼───────────────┐
                                      │      PostgreSQL (DATABASE_URL) │
                                      │  Flyway V1__baseline.sql +     │
                                      │  sp_upsert_run/                │
                                      │  sp_soft_delete_ausentes       │
                                      └────────────────────────────────┘
```

Capas internas del backend (sin cambios de forma, solo el datasource):

```
┌───────────────────▼─────────────────────┐
│         ApiController.java              │  Spring MVC (+ CorsConfig)
├─────────────────────────────────────────┤
│         ScraperService.java             │  Orquestación async
├──────────────┬──────────────────────────┤
│  Scrapers    │  ResultAggregator        │  Scraping + merge +
│  *Page.java  │  (aggregator.normalize/  │  normalizar + agrupar
│              │   .grouping/.text)       │
├──────────────┴──────────────────────────┤
│         DatabaseService.java            │  PostgreSQL (HikariCP pool),
│                                          │  write-path via plpgsql
├─────────────────────────────────────────┤
│   PythonRunner → ml_pipeline.py         │  ML subprocess (psycopg2)
└─────────────────────────────────────────┘
```

---

### Launcher: CLI nativo (`native-cli-installer` 2026-07-25 + `cli-command-console` 2026-08-05)

Supersede el launcher `menu.ps1`/`menu.sh` (`interactive-cli-launcher`, PR
#108) — ambos scripts, y sus tests (`tests/menu.Tests.ps1`/
`tests/menu_test.sh`), fueron **retirados** (borrados).

**El seam se movió:** antes, `INSTALAR_Y_CORRER.bat`/`Ejecutar_instalar.sh`
compilaban el proyecto (`npm install`/`npm run build`, `mvn clean package`),
generaban `.env` con un bloque `echo`/`cat` hardcodeado, y en su tail
invocaban `menu.ps1`/`menu.sh`. Ahora:

- Los installers **solo aprovisionan el toolchain**: JDK, Maven, Node, el
  Python 3.11 embeddable + deps ML (torch/scikit-learn/Marqo, sin cambios),
  PostgreSQL portable, y — nuevo — `uv` + un `_tools/cli-venv` dedicado.
- El **CLI nativo** (`cli/`, Python) posee todo lo que antes hacía el
  installer post-toolchain: build (`npm`+`mvn` vía las rutas vendorizadas
  en `_tools/`), generación/reconciliación de `.env` (template-driven desde
  `.env.example` — crea si falta, nunca pisa valores existentes salvo
  `--regenerate`/`--force`), y la orquestación de backend (`:3000`) +
  frontend (`npm run preview` en `:5173`), incluyendo el mismo teardown
  limpio en `Q`/`Ctrl+C` y la carga JVM `-DDATABASE_PASSWORD=<valor, incluso
  vacío>` que evita el bug de Windows con variables de entorno vacías.
- **Invariante (bloqueado por diseño):** el installer nunca compila el
  proyecto; el CLI nunca descarga ni instala un componente del toolchain
  bajo `_tools/`.

**Arquitectura del CLI** (headless core + presentadores, no una app Textual
monolítica):

```
cli/
├── __main__.py        # entry point: detección de capacidad + routing
├── core/               # HEADLESS — cero imports de textual/rich, testeable con pytest
│   ├── config.py        #   repo-root, paths de _tools/, puertos
│   ├── env_file.py       #   .env template-driven (crea/reconcilia/--force)
│   ├── builder.py        #   npm+mvn, ordenamiento de VITE_API_BASE_URL
│   ├── rest.py            #   cliente REST de la API existente (json.dumps, sin shell)
│   ├── processes.py       #   lifecycle backend+frontend + teardown funnel
│   ├── commands.py        #   registro ÚNICO de verbos (autocompletado + help + menú plano)
│   ├── logs.py            #   archivos de log de servicios + tail acotado + scrub de ANSI
│   └── errors.py          #   excepciones tipadas con mensaje accionable
├── tui/                 # PRESENTADOR — Textual App, solo presentación
└── plain/                # FALLBACK — driver de texto plano sobre el MISMO core
```

`__main__.py` decide UNA vez, antes de construir cualquier presentador, si
la terminal soporta la TUI interactiva (`isatty`, `NO_COLOR`, `TERM=dumb`,
`--plain`, `cmd.exe` legacy sin ANSI, o Textual no instalable) — si no,
degrada al runner de texto plano sobre el mismo `core/`. Nunca crashea.

**Consola por comandos, no menú (`cli-command-console`, 2026-08-05):** la TUI
es una franja de health de una línea + consola + prompt — tres filas de chrome,
corre en 60×18 sin maximizar. Las operaciones se tipean; el vocabulario vive
en `core/commands.py`, del que salen el autocompletado, el `help` y el menú del
runner plano, así que no pueden divergir. Se eliminaron los bindings de una
sola letra (se comían caracteres tipeables); quedan `ctrl+c` y `ctrl+l`.

**Por qué el stdio de los hijos está atado (el bug de fondo):** Spring Boot y
Vite escribían ANSI crudo sobre el mismo TTY que Textual estaba pintando, y el
frame quedaba destruido — Textual no se entera, así que ningún repaint lo
arregla. Parecía un problema de layout y era de stdio. Hoy los **tres** streams
de todo hijo están atados: stdout+stderr a `scraper/logs/{backend,frontend}.log`,
stdin a `DEVNULL` (si no, el hijo compite por las teclas del prompt). `PIPE` no
es opción: nadie lo drena y el hijo se cuelga cuando se llena el buffer. La
salida se lee con el comando `logs`, cuyo tail **escapa las secuencias de
control** antes de renderizarlas — una línea de log de un sitio scrapeado es
input no confiable y podría inyectar escapes en la terminal.

**Aislamiento del venv del CLI (`_tools/cli-venv`):** construido por `uv`
sobre un **CPython 3.11.9 administrado por uv** (`uv python install` +
`uv venv --managed-python`), **NO** sobre el Python embeddable de ML
(`_tools/python`). El CLI nunca importa librerías ML, así que este venv no
comparte nada con el pipeline de imágenes y no arriesga el guard de versión
de `torch` que el bloque ML del `.bat` ya protege. Esto también elimina por
construcción el riesgo de bootstrap que tendría reusar el embeddable (su
`python311._pth` congela `sys.path` de una forma que podría filtrarse a un
venv construido encima). `import textual` es el acceptance check del
installer — si falla tras el aprovisionamiento, el install aborta con un
mensaje accionable (Python es ahora load-bearing en Windows, igual que ya
lo era en POSIX).

**Invocación:** `_tools/cli-venv/{Scripts/python.exe,bin/python} -m cli`,
corrido con cwd = raíz del repo — **no** `cli/__main__.py` como ruta de
script directa, que falla con `ModuleNotFoundError: No module named 'cli'`
porque los módulos del CLI usan imports absolutos `cli.*` que solo resuelven
cuando la raíz del repo está en `sys.path` (el caso de `-m cli`, no el de
invocar el archivo directamente).

**Tests:** `tests/cli/` (pytest) — unit tests del `core/` headless, tests
`Pilot` de Textual para el `tui/`, tests de degradación del routing, un
test de **injection-safety** (`json.dumps` con input hostil `a"b;$(x)`
round-tripea como un único campo JSON bien formado, sin invocar shell) que
reemplaza estructuralmente a los viejos `tests/menu.Tests.ps1`/
`tests/menu_test.sh`, y un test con un **subproceso real** que escupe
stdout/stderr y verifica vía `capfd` que ni un byte llega a nuestra terminal.

---

### ¿Por qué Allure declarativo para el reporte de tests?

**Decisión**: reporte declarativo Allure sobre TODA la suite de tests backend (66 clases / 556 `@Test`), como **capa de reporte pura** — sin tocar assertions ni lógica de test. Anotaciones `@Epic`/`@Feature`/`@Story`/`@DisplayName` a nivel clase, `@Step` locales privados extraídos del setup/arrange ya existente, y `Allure.parameter(...)` en tests de boundary.

**Wiring clave (`scraper/pom.xml`)**:
- `allure-bom` 2.29.1 gestiona la versión de `allure-junit5`; `aspectjweaver` 1.9.24 es el javaagent que captura los `@Step` en runtime. Ambas son `scope=test` — NO entran al fat JAR (`spring-boot:repackage` corre en `package`, después de `test`).
- surefire usa `<argLine>@{argLine} -javaagent:"...aspectjweaver..."</argLine>` con **late-binding `@{...}`** (NO `${argLine}`). Esto es lo más frágil de todo el wiring: `jacoco:prepare-agent` (fase `initialize`) escribe su propio javaagent en la property Maven `argLine`; con `${argLine}` (interpolación *eager* en parse-time del POM) esa property todavía está vacía y JaCoCo **deja de recolectar cobertura silenciosamente** mientras los tests siguen pasando. `@{...}` es expansión *tardía*: surefire la resuelve en fase `test`, ya con la property poblada, concatenando el agente de JaCoCo + el de AspectJ.
- `allure-maven` 2.15.2 wirea `mvn allure:serve` / `allure:report`, pero el render HTML es **opcional**: el entregable CI-crítico es solo `target/allure-results/*.json`.

**`@Step` locales, no un god-class compartido**: cada `@Step` se extrae del ARRANGE de su propia clase → cada slice queda auto-contenido y revertible (fue clave para entregarlo como cadena de PRs encadenados sin conflictos cruzados). El único helper compartido, `testsupport/AllureSteps.java`, se reserva para pasos genuinamente cross-cutting (hoy solo `toJson`).

**CLI bundleado en el toolchain**: `INSTALAR_Y_CORRER.bat` baja el Allure CLI a `_tools/allure` y lo agrega al PATH de la sesión (mismo patrón que `jdk21`/`maven`/`node`; descarga no-fatal — si falla, la app igual corre). Flujo de uso:
```
mvn -f scraper/pom.xml test        REM genera target/allure-results/
allure serve scraper/target/allure-results
```

**Trade-off**: la versión del CLI (`allure-commandline` 2.29.0) se versiona **aparte** de las libs Java (`allure-bom` 2.29.1) — no existe un `allure-commandline` 2.29.1; el formato de `allure-results` es estable entre versiones de CLI, así que la diferencia es inocua. La coexistencia del `-javaagent` de AspectJ con el inline-mock-maker de Mockito 5 se verificó explícitamente en PR0.
