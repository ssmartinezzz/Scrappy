# Arquitectura del Fashion Scraper

---

## Base de datos → [`DATABASE.md`](./DATABASE.md)

**Nada sobre la base se documenta acá.** Esquema, migraciones, semántica del
upsert, estado de normalización, el porqué de cada decisión y el SQL de
rollback ejecutable viven todos en [`DATABASE.md`](./DATABASE.md). Esta
sección es sólo el índice, para que buscar "por qué" en este archivo no
termine en una copia desactualizada.

**La regla que gobierna toda tabla nueva, y que no se negocia caso por caso:
1FN y 3FN son precondición, no aspiración.** Una tabla que no las cumple se
rediseña antes de escribir la migración. El desarrollo —incluido el matiz de
1FN que este proyecto aprendió a los golpes: pide valores atómicos **además**
de ausencia de grupos repetitivos— está en
[`DATABASE.md § Regla de admisión`](./DATABASE.md#regla-de-admisión-toda-tabla-nueva-cumple-1fn-y-3fn).

| Qué buscás | Dónde está en `DATABASE.md` |
|---|---|
| Qué tablas hay y qué guarda cada una | § Esquema |
| Qué hizo cada `V1`..`V24` y las dos `R__` | § Esquema → Migraciones |
| Cuándo tabla de lookup y cuándo CHECK · qué lleva FK · cómo se dice "no hay valor" | § Regla de admisión |
| Semántica del upsert, soft-delete y reactivación | § Esquema → Estado normal |
| Por qué Postgres y no SQLite/H2 | § Decisiones y su justificación |
| Cómo revertir una migración aplicada (SQL que los tests ejecutan) | § Decisiones → los bloques `-- >>> rollback:VN` |
| Qué quedó deliberadamente sin normalizar, y por qué | § Non-goals |
| Por qué `/api/data` filtra en SQL y el resto lee el snapshot | § Decisiones y su justificación |

⚠️ Las dos funciones plpgsql (`sp_upsert_run`, `sp_soft_delete_ausentes`) se
editan **en su archivo `R__`** y en ningún otro lado. No agregues una migración
versionada para tocarlas.

---

## API REST → [`openapi.yaml`](./openapi.yaml) + [`API_REFERENCE.md`](./API_REFERENCE.md)

**Nada sobre el contrato de la API se documenta acá.** El contrato mecánico
(path, método, `x-access`, status codes) vive en
[`openapi.yaml`](./openapi.yaml). **Se escribe a mano** —no hay comando que lo
regenere— y lo sostiene `OpenApiRouteCoverageTest` en las dos direcciones:
documentado-pero-denegado y vivo-pero-no-documentado. Nunca la forma de la
respuesta. El "por qué" (semántica 401/403, scoping por dueño, timing-attacks, CSRF/cold-
boot, el guard asimétrico de `DELETE /api/db/productos`) vive en
[`API_REFERENCE.md`](./API_REFERENCE.md). Esta sección es sólo el índice, por
la misma razón que la de arriba: que buscar "por qué" acá no termine en una
copia desactualizada del contrato o de su justificación.

---

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

### ¿Por qué INPRO es su propia plataforma y no `tiendanube`?

**Decisión** (`add-inpro-office-store`, 2026-08-20): `plataforma='inpro'`, con
`InproPage`/`InproScraper` propios, aunque los datos que sirve son objetos
crudos de la API de Tiendanube.

**Razón**: la plataforma que importa es la de la **vidriera**, no la del
backend. INPRO corre un Next.js propio en Vercel y el storefront clásico de
Tiendanube no es alcanzable — `inpro.mitiendanube.com` redirige a otra tienda
(`inproindumentaria.com.ar`) y los slugs candidatos dan 410. Rutearlo a
`TiendanubeScraper` lo mandaría a buscar selectores de un tema que ahí no
existe: **0 productos y ningún error**, que es el mismo modo de falla que `V24`
cerró para Rockethard y Venex.

Leer el payload RSC en vez del DOM además **gana** datos: precio de lista,
precio promocional, precio comparado, stock por variante y SKU, todo lo que la
vidriera no muestra.

Esto generaliza: **"qué API sirve los datos" y "qué scraper hay que usar" son
preguntas distintas**, y confundirlas produce sitios registrados que scrapean
cero en silencio. El procedimiento de detección está en
[`ADD_SCRAPER.md`, Caso 6](./ADD_SCRAPER.md).

---

### ¿Por qué `oficina` es un rubro nuevo y no se reusó uno existente?

**Decisión**: abrir `productos.rubro` a un cuarto valor (`V27`) en vez de
meter sillas y escritorios en `indumentaria`, `tecnologia` o `suplementos`.

**Razón**: `V6` puso `CHECK` en esas columnas justamente para que un valor de
dominio no pueda mentir sobre el producto. Una silla ergonómica clasificada
como `tecnologia` no es un compromiso: es el dato roto que el `CHECK` existe
para impedir, y además contamina las estadísticas por categoría de las que come
el pipeline ML.

El rubro se resuelve por `sitio.rubro_forzado` y **nunca** por la categoría —
una silla la vende una tienda de oficina, pero una silla suelta en una tienda
de ropa no convierte a esa tienda en otra cosa. `suplementos` es la excepción
deliberada y ya existente: ahí la categoría gana sobre el sitio, porque un
suplemento es un suplemento lo venda quien lo venda.

El esquema, las tres ampliaciones de `CHECK` y su rollback están en
[`DATABASE.md`, `V27`](./DATABASE.md).

---

### Bloqueo conocido: Logg queda fuera de `fix-zero-yield-tech-sites`

De los cinco sitios tech que scrapeaban 0 productos en el run del 2026-08-11
(Compragamer, Rockethard, Venex, Maximus, Logg), cuatro se resolvieron en este
cambio. **Logg no.** El diagnóstico heredado lo daba como "typo de URL,
minutos" — medido en vivo contra `logg.com.ar` (2026-08-13) resultó ser el más
caro de los cinco, no el más barato:

- Plataforma custom ASP.NET **ABP** (`abp.min.js`, `abp.jquery.js`,
  `signalr.min.js`), no una de las plataformas ya soportadas.
- El grid de productos es **JS-hydrated**: `/Products?categoryName=…` sirve
  ~119 KB con cero product cards en el HTML crudo — todo el contenido llega
  después, por un mecanismo no identificado (¿JSON endpoint propio? ¿SignalR?
  ¿ambos?).
- La fuente de hidratación real nunca se aisló durante la exploración de este
  cambio (proposal, riesgo R2). Sin eso, ni un `LoggPage`/`LoggScraper` ni una
  fila de seed para `logg` en `sitio.plataforma` pueden escribirse con
  confianza — arrancarían adivinando un contrato que nadie confirmó contra el
  sitio real.

**Decisión** (post-design, explícita del usuario): no ship Logg en este
cambio. Consecuencia directa en el esquema: el dominio de `sitio.plataforma`
crece de 9 a **11** valores (`qloud`, `oscommerce`), no a 12 — no existe
`logg` en el CHECK, no existe `V25`, no existe `LoggPage`/`LoggScraper`, y
`config.properties` no tiene ninguna entrada `sitio.logg.*`. No queda ningún
valor muerto ni código muerto por retirar después: la migración que hubiera
agregado `logg` nunca se escribió, en vez de escribirse y revertirse.

Retomar Logg es trabajo de exploración, no de implementación: hay que capturar
tráfico de red real contra `/Products?...` (DevTools, no `curl`) para encontrar
qué endpoint (o mecanismo SignalR) entrega los datos antes de poder diseñar
`LoggPage`.

---

### ¿Por qué el aggregator está modularizado en collaborators de responsabilidad única?

**Decisión**: `ar.scraper.aggregator` se organiza como orquestadores delgados (`NormalizerService`, `GroupingService`, `ResultAggregator`) que secuencian collaborators de responsabilidad única, agrupados en subpaquetes por tema (`normalize/`, `grouping/`, `text/`), más `FacetCalculator` como utility estática en la raíz del paquete.

**Razón**: antes de esta modularización, `NormalizerService` (categoría, talles, género, marca, pack/combo, subcategoría, rubro, gymrat) y `ResultAggregator` (validación, dedup, pipeline ML, persistencia, facets) eran clases monolíticas: cada regla de negocio nueva crecía el mismo archivo y era imposible testear una regla sin arrastrar todas las demás. La modularización es **behavior-preserving** — cero cambios observables end-to-end, con la suite existente pasando sin editar un solo test. El historial slice por slice se retiró de `docs/` una vez completada; queda en el historial de git.

**Estructura resultante**:

| Paquete | Responsabilidad | Clases |
|---------|------------------|--------|
| `aggregator` (raíz) | Orquestación de la agregación completa + utility de facets | `ResultAggregator` (orquestador: validar → dedup → pipeline ML → persistir → facets), `FacetCalculator` (cálculo puro y estático de facets) |
| `aggregator.normalize` | Normalización de un `Product`, orquestada por `NormalizerService` | `PackQuantityDetector`, `CategoryClassifier`, `GenderResolver`, `SizeNormalizer`, `SubcategoryResolver`, `GymratTagger` + holders estáticos de datos/predicados: `GarmentTaxonomy`, `CategoryAliases`, `NonTextileGuard`. `BrandExtractor`, `RubroResolver`, `SiteRegistry`, `SiteClassification` y `CategoryGroups` vivieron acá hasta `decouple-backend-layers`; hoy están en `ar.scraper.classification` (ver la sección de capas más abajo) y `NormalizerService` los sigue inyectando igual |
| `aggregator.grouping` | Agrupación de productos equivalentes entre sitios, orquestada por `GroupingService` | `ProductIdentity`, `JaccardSimilarity`, `ProductGroup` |
| `aggregator.text` | Utilidades de texto compartidas entre `normalize` y `grouping` | `AccentStripper` |

**Patrones aplicados**:
- **Orquestadores puros**: `NormalizerService.normalizarProducto` y `ResultAggregator.agregar` son el único lugar donde se reconstruye el record `Product` o se arma el `AggregatedResult` — secuencian sus collaborators (inyectados por constructor) y no contienen lógica de negocio propia. Ningún collaborator conoce a los demás.
- **Holders estáticos de datos/predicados**: `GarmentTaxonomy`, `NonTextileGuard` (en `normalize`) y `CategoryGroups`, `SiteClassification` (en `classification`) no tienen estado ni dependencias — se consumen vía static import dentro de los collaborators que los necesitan, en vez de inyectarse como beans adicionales en `NormalizerService`.
- **`FacetCalculator` como utility estática, no bean**: a diferencia de los collaborators de `normalize`/`grouping` (todos `@Component`), `FacetCalculator` es `final` con constructor privado y un único método estático — refleja que el cálculo de facets no tiene estado ni dependencias. `ResultAggregator.calcularFacets` se mantiene como delegate público porque ~10 tests fuera del paquete (`ar.scraper.web`) construyen fixtures de `AggregatedResult` contra esa firma exacta.
- **Test factory para tests de orquestación**: `NormalizerService` requiere 8 collaborators por constructor, así que los tests que ejercitan la normalización end-to-end usan `NormalizerServiceTestFactory.create()` (solo en `src/test/java`) en lugar de instanciar los 8 collaborators a mano en cada test.

---

### ¿Por qué el backend se parte en áreas de negocio, y por qué primero se mueven tipos y recién después se escriben interfaces?

**Decisión**: el estado final del backend es **Spring Modulith**, con un paquete `ar.scraper.<área>` por área de negocio (la detección por defecto de Modulith) y `ar.scraper.model` como kernel compartido. **En ese estado final no hay paquete `db` central**: cada área es dueña de su persistencia, los 13 repositorios se reparten entre áreas y `db/` deja de existir. La dependencia de Modulith **no se agrega** hasta que existan áreas reales; mientras tanto la forma la sostienen reglas ArchUnit escritas a mano. `pages/` y `scrapers/` quedan en Page Object Model, sin tocar.

| Fase | Qué | Estado |
|---|---|---|
| F0 | Baseline ArchUnit: congelar los ciclos de hoy como golden y escribir las reglas que tienen que poder fallar | ✅ |
| F1 | Reubicar tipos de dominio en su paquete final, cero cambio de lógica | ✅ 15 tipos en `catalog`, `classification`, `scrape`, `scheduling` |
| F2 | Un puerto de capacidad por agregado de persistencia | ✅ 13 de 13: `CronPort`, `FavoritosPort`, `PresetPort`, `HistorialPort`, `CatalogQueryPort`, `ProductPort`, `CategoriaStatsPort`, `MlOutputPort`, `ScrapeRunPort`, `SitiosPort`, `FeedbackPort`, `SavedOutfitsPort`, `PreciosExternosPort` |
| F3a | Cerrar los 7 ciclos del grafo; `cron/` se absorbe en `scheduling/`, que es el nombre final | ✅ `CatalogSnapshotPort`, `ScrapeControlPort`, `grafoSinCiclos` descongelada |
| F3b | Recortar el dominio que todavía vive en `web/` (`OutfitService`, `SupplementCombo`, `OutfitBudgetBuilder`, `RecommendationService`, `VisualCoherence`…) | ✅ 12 tipos relocalizados (`outfits` ×8, `catalog` ×3, `identity` ×1) + carve-out de 2 hojas de `aggregator` (ver abajo) |
| F4 | Endpoints sólo transporte | — |
| Cierre | La verificación de Modulith reemplaza las reglas a mano | — |

**Razón: colocación antes que abstracción.** Los ciclos del grafo de paquetes no los causaba la falta de interfaces sino tipos de dominio estacionados en paquetes de infraestructura: `CronJob` vivía en `cron/`, `CorridaInterrumpida` en `db/`, `SiteRegistry` y `BrandExtractor` en `aggregator/normalize/`. Mover el tipo a su paquete final mata el ciclo sin tocar una línea de lógica. Escribir puertos primero habría **modelado la deuda**: una interfaz sobre un tipo mal ubicado fija el lugar equivocado, con más ceremonia.

F1 dejó a `db` afuera de todos los ciclos del grafo. F2 **no cierra ningún ciclo**: los dos ciclos congelados que pasan por `cron` se re-escriben, no desaparecen. F3a los cierra a los siete.

**Qué garantiza ArchUnit y qué no** (`BackendLayeringArchTest`, `archunit-junit5` 1.5.0):

- `grafoSinCiclos` **ya no está congelada**. Lo estuvo desde F0, con `src/test/resources/archunit_store/` guardando los 7 ciclos de entonces como golden versionado. Congelar servía para que ninguno *nuevo* entrara, no para probar que uno salió: `allowStoreUpdate=true` **descarta en silencio** las violaciones resueltas, así que un verde de la regla congelada nunca dijo nada sobre un ciclo cerrado. F3a los cerró a los siete, así que la regla pasó a ser una regla común que puede fallar, y el store y `archunit.properties` se borraron. Un ciclo nuevo hoy rompe el build.
- Las victorias las prueban reglas **sin congelar**, que pueden fallar: `dbNoDependeDeAggregator`, `areasSonSumideros`, `mlNoDependeDeWeb`, `agentNoDependeDeWeb`, `cronFueAbsorbidoEnScheduling`, `webUsaFavoritosPorElPuerto`, `webUsaPresetsPorElPuerto`, `webUsaHistorialPorElPuerto`, `webUsaCatalogQueryPorElPuerto`, `webUsaProductoPorElPuerto` y las siete del resto de F2. Cada una nació como RED intencional en su propio commit, antes del refactor que la pone en verde.
- `areasSonSumideros` prohíbe que `catalog`, `classification`, `scrape`, `scheduling`, `favoritos`, `financiacion`, `feedback` y `outfits` dependan de cualquier paquete de infraestructura del backend (`db`, `aggregator`, `web`, `ml`, `agent`, `security`, `config`, `scrapers`, `pages`, `health`, `identity`). Desde F3a es también quien sostiene la absorción de `cron/`: el runner, ya en `scheduling`, no puede volver a nombrar `web`, `ml` ni `config`. **No prohíbe `java.sql..` a propósito**: un área dueña de su persistencia sostiene JDBC legítimamente, y `FavoritosProtegidosException extends SQLException` vive en `catalog`.
- **F3b le da a `areasSonSumideros` un carve-out de clase, no de paquete**: `AGREGATOR_LEAVES_F3B` (en `BackendLayeringArchTest`) matchea por FQN exacto a `ar.scraper.aggregator.normalize.GarmentTaxonomy` y `ar.scraper.aggregator.text.AccentStripper` dentro del predicado de paquetes prohibidos. Son las dos únicas hojas de `aggregator` que `SupplementCombo` (ahora en `outfits`) sigue necesitando para sus keywords de suplementos, y ninguna se pudo mover en F3b: la taxonomía tiene un solo dueño (`CODE-6`) y `AccentStripper` es hot path con 10 consumidores. El matcheo es por FQN a propósito — una hipotética clase con el mismo simple name en otro paquete sigue prohibida. **Retire path**: cuando los keywords de suplementos encuentren un hogar compartido afuera de `aggregator`, borrar el set y el predicado y volver a prohibir `ar.scraper.aggregator..` entero.
- El análisis excluye el árbol de tests (`DoNotIncludeTests`): ~60 clases de test de `db` importan `aggregator`, y sin esa exclusión `db↔aggregator` habría sobrevivido a F1 como ciclo sólo de tests.

**El puerto de capacidad, tal como quedó en F2.** `ar.scraper.scheduling.CronPort` es la interfaz de 12 métodos del agregado `cron_jobs`/`cron_executions`. La implementa `ar.scraper.db.CronRepository`, un `@Repository` **package-private**: es `javac`, no ArchUnit, quien impide nombrar el tipo concreto fuera de `db`. `CronJobRunner`, `CronJobService` y `CronApiController` dependen del puerto, y `cron` ya no referencia `ar.scraper.db`.

`DatabaseService` conserva todos sus métodos públicos: recibe `CronPort` por su constructor `@Autowired` y delega en él los 12 de cron; la sobrecarga `(DataSource)` mantiene su firma. La extracción tuvo que ser **aditiva** porque 61 archivos de test construyen un `DatabaseService` real contra Postgres — romper la fachada era reescribir esa suite en el mismo PR que cambia la forma. Lo que el patrón **no** reclama: no cierra ciclos, no achica `DatabaseService` y no le quita a `db` su rol de fachada. Sólo mueve la dependencia de `cron` de la clase concreta a una capacidad. El precedente ya existía: `UsuarioRepository`, `PasswordResetRepository` y `RefreshTokenRepository` son `@Repository`s inyectados directo en `security/**`, salteando la fachada.

**`FavoritosPort`, el segundo (extract-favoritos-port).** `ar.scraper.favoritos.FavoritosPort` es la interfaz de 4 métodos del agregado `favoritos`; la implementa `ar.scraper.db.FavoritosRepository`, `@Repository` package-private como `CronRepository`. El consumidor, `FavoritosEndpoints`, **no es un bean**: lo construye a mano `ApiController`, cuyo constructor está embebido 19 veces en el store congelado y lo arman a mano 29 tests. Por eso el puerto no se inyecta en `web`: `DatabaseService` lo expone con `favoritos()`, junto a `siteRegistry()`. `ApiController` se lo pasa al endpoint al construirlo. Esa lectura de `db.favoritos()` en el constructor es una interacción que Mockito registra, y varios tests ajenos a favoritos afirman `verifyNoInteractions(db)` para otros endpoints: esos fixtures hacen `clearInvocations(db)` después de armar el controller, sin tocar ninguna aserción. Es una desviación acotada a consumidores que todavía no son beans; en F4, cuando los endpoints pasen a beans, el accessor sobra y el puerto se inyecta como en F2. `FavoritosEndpoints` sigue recibiendo la fachada para `obtenerProducto`/`esProductoActivo` (lecturas de catálogo): dependencia doble declarada, no un descuido. La regla que lo sostiene es `webUsaFavoritosPorElPuerto` — ninguna clase de `web` llama a los 4 métodos de favoritos de `DatabaseService` — y nació RED con exactamente 3 violaciones. El store no cambió: ni `FavoritosEndpoints` ni el constructor de `DatabaseService` aparecen en ningún ciclo congelado.

**`PresetPort`/`HistorialPort`, el tercero y el cuarto (extract-preset-historial-ports).** A diferencia de `FavoritosPort`, acá el DTO no nació en su paquete final: `Preset` y `HistorialEntry` vivían **anidados** en `DatabaseService` (`DatabaseService.Preset`, `DatabaseService.HistorialEntry`), así que el trabajo se partió en dos commits — primero promoverlos a `ar.scraper.financiacion.Preset` y `ar.scraper.catalog.HistorialEntry` (import-only para todo consumidor), recién después escribir el puerto sobre el tipo ya bien ubicado. Invertir el orden habría fijado el puerto sobre un tipo mal colocado, el mismo motivo por el que F1 corre antes que F2 en general.

`ar.scraper.financiacion.PresetPort` (7 métodos: los 6 del CRUD que vigila ArchUnit más `seedPresetIlustrativoSiVacio`, porque `DatabaseService.init()` siembra a través del puerto) y `ar.scraper.catalog.HistorialPort` (3 métodos de lectura — las escrituras siguen en el upsert de productos) los implementan `PresetRepository`/`HistorialRepository`, `@Repository` package-private como los anteriores. `DatabaseService` los expone con `presets()`/`historial()`, junto a `favoritos()`. A diferencia de `FavoritosEndpoints`, acá SON tres los consumidores hand-built: `FinanciacionEndpoints` (deja de recibir `DatabaseService` por completo — el primer endpoint en no necesitar la fachada), y `CatalogoEndpoints`/`MlEndpoints`, que mantienen `DatabaseService` **además** de los puertos nuevos, porque siguen usando otras columnas de la fachada (`buscarCatalogo`, `cargarCategoriaStats`, etc.) — dependencia doble declarada, mismo posicionamiento que `FavoritosEndpoints` con la fachada. `FinanciacionEnricher`/`SenalEnricher` (en `ml`) también migraron: reciben el puerto respectivo en vez de `DatabaseService`.

Las reglas `webUsaPresetsPorElPuerto`/`webUsaHistorialPorElPuerto` nacieron RED con 9 y 3 violaciones respectivamente. A diferencia de F2's `FavoritosPort`, **esta extracción SÍ refrescó el store**: los cinco constructores que cambiaron de firma (`FinanciacionEnricher`, `SenalEnricher`, `FinanciacionEndpoints`, `MlEndpoints`, `CatalogoEndpoints`) aparecen 15 veces repartidas en 5 de los 7 ciclos congelados — el texto de una violación embeba la firma completa, así que cambiar un tipo de parámetro adentro de un ciclo ya congelado re-escribe esa línea aunque no se haya movido ninguna arista nueva. El conteo de ciclos siguió en 7 y ninguna slice nueva apareció: sólo el texto de esas 15 líneas cambió.

**`CatalogQueryPort`/`ProductPort`, el quinto y el sexto (extract-catalog-query-port).** Dos puertos en el mismo commit porque comparten una sola repository pair: `ar.scraper.catalog.CatalogQueryPort` (búsqueda paginada del catálogo, facets, resumen — 6 firmas) y `ar.scraper.catalog.ProductPort` (el agregado producto completo: write-path del scrape, lecturas, los dos caminos de clasificación y el borrado destructivo — 15 firmas). **Decisión D3, deliberadamente distinta de F3**: `CatalogQueryPort` conserva los nombres que ya tenía `CatalogQueryRepository` (`buscar`/`facetas`/`resumen`), NO los nombres de fachada que expone `DatabaseService` (`buscarCatalogo`/`facetasCatalogo`/`resumenCatalogo`) — así el diff del repositorio es literalmente `implements` + `public`/`@Override`, verbatim, y es `DatabaseService` quien renombra al delegar. `ProductPort` no necesitó esa decisión: sus 15 nombres ya coincidían con la fachada. `UpsertStats` (D1) se promovió de récord anidado en `DatabaseService` a `ar.scraper.catalog.UpsertStats`, mecánicamente forzado por `areasSonSumideros`: `ProductPort` vive en `catalog` y no puede devolver un tipo de `db`. Las dos implementaciones — `CatalogQueryRepository`/`ProductRepository` — pasan a ser `@Repository` package-private, como `CronRepository`/`FavoritosRepository`/`PresetRepository`/`HistorialRepository`; sin esa anotación el `@Autowired` de `DatabaseService`, ahora ensanchado a 8 parámetros, no tiene bean que inyectar en producción. El constructor de 1 argumento pasa a delegar a través de un nuevo constructor privado `(DataSource, SiteRegistry)` para que las dos repositories compartan UNA sola instancia de `SiteRegistry` en vez de resolver cada una la suya.

Las reglas `webUsaCatalogQueryPorElPuerto`/`webUsaProductoPorElPuerto` nacieron RED con exactamente 5 y 14 violaciones (`CatalogoEndpoints` para la primera; `FavoritosEndpoints`, `CatalogoEndpoints`, `MlEndpoints`, `AgentEndpoints`, `DbAdminEndpoints` y `ScraperService` para la segunda). `FavoritosEndpoints` y `CatalogoEndpoints` dejan `DatabaseService` por completo — el segundo y tercer endpoint, después de `FinanciacionEndpoints`, en no necesitar la fachada. `MlEndpoints`, `AgentEndpoints`, `DbAdminEndpoints`, `ScraperService` (bean Spring) y `ResultAggregator` (bean Spring) **mantienen `DatabaseService` a propósito, por diseño, no por descuido**: sus llamadas residuales (`cargarCategoriaStats`/`guardarCategoriaStats`, `guardarMlOutput`/`limpiarMlOutput`, `siteRegistry()`, y las de `scrape_run`/`sitios`) pertenecen a repositorios todavía sin puerto propio — `CategoriaStatsRepository`, `MlOutputRepository`, `ScrapeRunRepository`/`SitiosRepository` — que quedan para la próxima extracción de este mismo backlog. Esa dependencia dual **ya no existe**: `extract-ml-persistence-ports`, descrita abajo, le dio puerto a los cuatro repositorios y las cinco clases dejaron la fachada.

Igual que F3, **esta extracción SÍ refrescó el store**: los seis constructores que ensancharon su firma (`ResultAggregator`, `AgentEndpoints`, `MlEndpoints`, `CatalogoEndpoints`, `DbAdminEndpoints`, `ScraperService`) aparecen repartidos en 5 de los 7 ciclos congelados. El conteo de ciclos siguió en 7 y ninguna slice nueva apareció — clasificado por script, no a ojo: de las 73 líneas que cambiaron, 51 son drift de número de línea puro (`ResultAggregator.java`, `CatalogoEndpoints.java` y compañía crecen con cada `import`/método nuevo) y 22 nombran uno de los seis constructores con `ar.scraper.db.DatabaseService` reemplazado o acompañado por `ar.scraper.catalog.ProductPort`/`CatalogQueryPort`.

**`CategoriaStatsPort`/`MlOutputPort`/`ScrapeRunPort`/`SitiosPort`, del séptimo al décimo (extract-ml-persistence-ports).** Cuatro puertos en un commit porque son un cluster de consumo, no de implementación: son exactamente los que le quedaban a `MlEndpoints`, `AgentEndpoints`, `DbAdminEndpoints`, `ScraperService` y `ResultAggregator`, las cinco clases cuya dependencia dual el párrafo anterior declaraba. Las cinco dejan `DatabaseService` **por completo**. Cada puerto vive en el área dueña del tipo que devuelve: `CategoriaStatsPort` en `catalog` porque `CategoriaStats` ya estaba ahí, `ScrapeRunPort` en `scrape` por `CorridaInterrumpida`, y `SitiosPort` en `classification` por `SiteRegistry`.

`MlOutputPort` es el único que no tuvo un tipo que lo ubicara —su payload es un `JsonNode` pelado— y **no** se le hizo un área `ml` propia: `ar.scraper.ml` ya existe y es infraestructura (el runner del subproceso Python, los enrichers), tanto que `areasSonSumideros` la lista entre los paquetes de los que un área NO puede depender. Un área homónima al lado de un paquete de infraestructura con el mismo nombre habría sido una trampa para el próximo lector. Va a `catalog`, que es de lo que el payload habla.

`SitiosPort` arrastra un contrato que no se ve en la firma: **toda escritura termina en un `SiteRegistry.reload()`**, porque el registry cachea la tabla `sitio` y sin ese reload queda stale detrás de una escritura. Por eso el puerto recibe el `SiteRegistry`, y por eso una implementación que se saltee el reload está mal aunque compile. Lo que **no** subió al puerto es `PLATAFORMAS_VALIDAS`: `PlatformVocabularySyncTest` la alcanza por acceso de paquete para probar que coincide con el CHECK de SQL, y subirla la convertiría en API pública en vez de un invariante chequeado.

**`FeedbackPort`/`SavedOutfitsPort`/`PreciosExternosPort`, del undécimo al
decimotercero (extract-feedback-outfits-ports). F2 cierra acá: 13 de 13.** Los
tres agregados no tienen nada en común entre sí — lo que los agrupa es que sus
consumidores eran los tres últimos endpoints que todavía recibían la fachada
entera, y los tres la dejan por completo: `OutfitsEndpoints` (los dos puertos de
outfits), `RecomendadosEndpoints` y `ComparadorEndpoints`.

`ar.scraper.feedback.FeedbackPort` (7 firmas) cubre `outfit_feedback_item` y
`categoria_dismiss` juntas. Son dos tablas y **una sola señal** —lo que el
usuario aceptó y lo que descartó—, y las dos superficies que la leen (el armador
de outfits y el feed "Para ti") la consultan de a pares en cada request:
partirla en dos puertos habría duplicado el consumidor sin separar ningún ciclo
de vida. `ar.scraper.outfits.SavedOutfitsPort` (4 firmas) es `saved_outfits` y
sus items. `ar.scraper.catalog.PreciosExternosPort` (2 firmas) es
`precios_externos`, y **no** tuvo un área propia: su payload es
`List<Map<String,Object>>`, sin ningún tipo que lo ubique, así que va a `catalog`
porque de eso habla — el mismo criterio con el que `MlOutputPort` quedó ahí.
`cargarPreciosExternos` entra a la regla ArchUnit aunque hoy no tenga un solo
consumidor fuera de `db`: la regla describe el agregado, no el conteo de llamadas
del commit que la escribe.

`OutfitItemRow` se promovió primero, en su propio commit, de récord anidado en
`DatabaseService` a `ar.scraper.feedback.OutfitItemRow` — mismo movimiento y
mismo motivo que `Preset`/`HistorialEntry`: `areasSonSumideros` prohíbe que un
puerto del área devuelva un tipo de `db`. Las áreas nuevas (`feedback`,
`outfits`) entran a esa regla en el commit que las crea; un área sin esa línea no
la vigila nadie.

Las reglas `webUsaFeedbackPorElPuerto`/`webUsaOutfitsGuardadosPorElPuerto`/
`webUsaPreciosExternosPorElPuerto` nacieron RED con 11, 4 y 1 violaciones. El
store se refrescó con **una sola línea**: el constructor de `ComparadorEndpoints`
adentro del ciclo `aggregator -> ml -> web`. Los de `OutfitsEndpoints` y
`RecomendadosEndpoints` no aparecían en ningún ciclo congelado, así que
ensancharlos no re-escribió nada. Siguen siendo 7 ciclos y ninguna slice nueva.

**Lo que costó una vuelta: cuatro fixtures de `web` vivían de las respuestas por
defecto del mock de la fachada.** Pasaban `mock(DatabaseService.class)` al
endpoint, y Mockito devuelve colección vacía para un método que devuelve
`List`/`Set` — así que `db.obtenerOutfitFeedback(...)` "andaba" sin stub. En
cuanto el endpoint recibe un puerto, ese mismo mock devuelve **null** para
`db.feedback()` y el fixture muere con un NPE que no nombra ningún cambio de
comportamiento. La ruta ya estaba escrita por `ApiControllerFavoritosTest`:
mockear el puerto y stubear el accessor. Y `SiteRegistrySingletonWiringTest` arma
su contexto Spring **a mano**, clase por clase: un `@Repository` nuevo no aparece
ahí solo.

`AgentEndpoints` sale de la fachada por una vía distinta a las demás: sólo usaba `db.siteRegistry()`, o sea que siempre quiso el `@Component`, no la fachada. `ApiController` se lo pasa directo. La regla de sitios **no** prohíbe `siteRegistry()` a propósito: es un accessor, de la misma forma que `db.productos()`/`db.presets()`/`db.favoritos()`, y retirar esos accessors es trabajo de F4 —cuando los endpoints pasen a beans— no de esta slice. Prohibirlo acá habría ensanchado el constructor de `ApiController`, embebido 19 veces en el store congelado y armado a mano por 29 tests, sin retirar un solo repositorio.

Las cuatro reglas nuevas son las primeras que **no** se acotan a `ar.scraper.web..`: `ResultAggregator` vive en `aggregator`, así que una regla web-only se habría puesto verde con la mitad de la extracción todavía llamando a la fachada. Nacieron RED con 2, 4, 11 y 1 violaciones. El store se refrescó por tercera vez en la cadena: 7 ciclos antes y después, y de las 69 líneas que cambiaron, 23 son drift puro de número de línea y 12 son cambios reales de firma.

**Un parámetro muerto sobrevive a cualquier suite verde.** Soltar la fachada en `ResultAggregator` destapó que `MlEnricher.enriquecer` recibía un `DatabaseService` y **no lo usaba nunca**: existía incluso una sobrecarga de 2 argumentos que le pasaba `null`, y los tests usaban sólo esa. Las dos se colapsaron en una. El punto para la próxima vez es el método de búsqueda, no el hallazgo: un grep de `db\.` —el que se hace por reflejo— encuentra las llamadas y **no** ve el objeto pasado como argumento, sin punto. El compilador lo encontró recién cuando se borró el campo.

**Trampas operativas** (cada una costó una sesión):

- Refrescar el store tras un cambio que **re-escribe** una violación congelada que sigue abierta exige `-Darchunit.freeze.refreeze=true`; `allowStoreUpdate=true` solo descarta las resueltas. ArchUnit poda el store en **cada** corrida, incluidas las que fallan: restauralo desde `HEAD` antes de refrescar y revisá el diff regenerado antes de commitearlo (F2: exactamente 4 líneas, el conteo de ciclos sigue en 7; extract-preset-historial-ports: 25 líneas, mismo conteo).
- El texto de una violación congelada embebe la firma completa del constructor: cambiar el tipo de un parámetro de una clase adentro de un ciclo congelado re-escribe esa violación aunque no se haya movido ninguna arista.
- Un grep de `import` no ve las llamadas con nombre calificado; buscá el nombre del paquete.
- Ensanchar un constructor de Spring rompe todo test que arme a mano un `AnnotationConfigApplicationContext` con esa clase (`SiteRegistrySingletonWiringTest`); buscá `Foo\.class` en `src/test` antes de tocar la firma.
- Un repositorio nuevo que implementa un puerto **necesita `@Repository`**, no solo `implements XPort`: sin la anotación el `@Autowired` de `DatabaseService` compila pero no arranca — `ApplicationContext` no tiene bean para inyectar en el constructor de producción. La suite Postgres-backed no lo detecta (construye `DatabaseService` a mano con `new`), así que sólo un arranque real de la app lo expone.


**F3a: los 7 ciclos se cierran, y eran dos clases mal ubicadas
(close-backend-package-cycles).** Antes de mover nada se midió el grafo, y el
tamaño del problema no era el que la fase sugería. Las aristas que entran a
`ar.scraper.web` desde adentro del backend son **tres, y nombran dos clases**:

```
ml    → ar.scraper.web.InflacionService   (SenalEnricher, FinanciacionEnricher)
agent → ar.scraper.web.ScraperService     (SearchProducts/ViewProduct/ProposeReclassify)
cron  → ar.scraper.web.ScraperService     (CronJobRunner)
```

`aggregator`, `scrapers`, `pages`, `health` y `security` no nombran una sola
clase de `web`. Como los 7 ciclos congelados pasan **todos** por `web`, matar
esas tres aristas los cierra a los siete — y `ml ↛ web` sola se lleva cinco.

Eso parte la fase en dos trabajos que no son el mismo: **cerrar ciclos** (F3a) y
**recortar el dominio que vive en `web/`** (F3b). El grueso de `web/`
—`OutfitService`, `SupplementCombo`, `OutfitBudgetBuilder`,
`RecommendationService`, `VisualCoherence`— es dominio estacionado en un paquete
de transporte, sí, pero **no participa de ningún ciclo**. Meterlo en el mismo
cambio habría puesto miles de líneas movidas encima del refactor que cierra los
ciclos, sin que una sola de ellas contribuyera al cierre.

`InflacionService` se muda a `ar.scraper.financiacion` **sin puerto de por
medio**: no importaba una sola clase de `ar.scraper` — es HTTP, Jackson y
`@Scheduled` — así que entra al área sin violar `areasSonSumideros`. Es F1 puro,
tres años tarde: colocación antes que abstracción. Escribirle un puerto habría
sido ceremonia sobre una clase que ya podía vivir donde corresponde.

Las otras dos aristas sí necesitaron puertos, y cada uno vive donde vive el tipo
que devuelve, igual que los 13 de F2:

- **`ar.scraper.aggregator.CatalogSnapshotPort`** (1 firma) es la lectura del
  snapshot vivo del catálogo. Vive en `aggregator` y **no** en un área porque
  devuelve `AggregatedResult`, que se declara ahí: un puerto en `catalog` que
  devolviera ese tipo violaría `areasSonSumideros`. Lo implementa
  `ScraperService` sin cambiar una firma —`getLastResult()` ya se llamaba así— y
  los tres tools del agente lo reciben en vez de la clase concreta.

- **`ar.scraper.scrape.ScrapeControlPort`** (6 firmas) es todo lo que el
  scheduler necesita para lanzar y parametrizar una corrida. Obligó a promover
  `ScraperService.ScraperStatus` a `ar.scraper.scrape.ScraperStatus`, por el
  mismo motivo que `UpsertStats`/`Preset`/`HistorialEntry` en F2. Los cuatro
  nombres de constante no cambian, así que el JSON de `/api/status` es idéntico.

**Por qué `ScrapeControlPort` necesitó un adapter y `CatalogSnapshotPort` no.**
`CronJobRunner` tocaba tres clases prohibidas para un área, y las tres capacidades
viven en objetos distintos: estado y disparo en `ScraperService`, banda de precio
en `ScraperConfig`, flag de GPU en `PythonRunner`. `ScraperService` **no tiene**
`PythonRunner` entre sus colaboradores. Hacer que implementara el puerto entero
habría significado inyectarle una dependencia que hoy no tiene, para beneficio
exclusivo del scheduler — ensanchar una clase de 55k por una razón ajena a lo que
hace. `ScrapeControlAdapter` (package-private en `web`, como los `@Repository`
de `db` que implementan los puertos de F2) une las tres y deja a `CronJobRunner`
con tres colaboradores en vez de cinco.

Las seis firmas del puerto no son un grab-bag: son exactamente la receta de
`POST /api/scrape` que el cron job replica — capturar la configuración vigente,
aplicar la del job, disparar, esperar, restaurar en `finally`.

**Dos reglas se retiran con el paquete `cron`.** `cronNoDependeDeDb` queda
subsumida por `areasSonSumideros`, que ya lista `scheduling` como área y `db`
entre los paquetes prohibidos. `dbNoDependeDeCron` **no se puede reapuntar** a
`scheduling`: `db.CronRepository` implementa `scheduling.CronPort`, así que esa
arista es legítima y deseada. Reapuntarla habría prohibido justo el patrón que
F2 construyó.

---

### ¿Por qué el vocabulario de categorías creció de 88 a 103, y por qué el orden del clasificador es dato y no estilo?

**Decisión** (`richer-category-taxonomy`, 2026-08-27): quince categorías nuevas
—trece de tecnología (`Cooler`, `Fuente`, `Motherboard`, `Red`, `Cable`,
`Impresión`, `Mousepad`, `Joystick`, `Micrófono`, `UPS`, `Tablet`, `Cámara`,
`Reloj`) y dos de equipamiento deportivo (`Pelota`, `Paleta`)— entran al canon,
y el orden del bloque tech de `CategoryClassifier` pasa a estar justificado
producto por producto en su propio javadoc.

**Razón**: `Otros` tenía 2.974 de las 16.830 filas activas — el 14% del
catálogo. La lectura fácil era "hay productos raros"; la medición dijo otra
cosa. Adentro había 453 teclados, 302 mouses, 285 fuentes y 231 discos, y no
estaban mal clasificados: **ningún keyword los nombraba**. `KW_TECLADO` no tenía
la palabra `teclado` pelada, sólo `"teclado gamer"` y `"teclado mecanico"`.
`Almacenamiento` llevaba desde `V13` en el canon y en la tabla lookup con cero
keywords que la produjeran: una categoría que existía y estaba vacía.

`Otros` es el "no sé" explícito que dejó `close-category-vocabulary`, y esa
decisión se sostiene: **es medible, y esto es lo que se pudo medir**. Un
vocabulario abierto habría escondido los mismos 2.974 productos detrás de
categorías inventadas por cada tienda.

**El criterio de alta fue ≥20 productos reales** con sustantivo propio y ninguna
categoría existente donde entren sin mentir. El piso no es estético: se eligió
mirando al consumidor. `ml_pipeline.py` usa `MIN_GROUP = 10` para decidir si
calcula estadística sobre la categoría o cae al padre, y `MIN_SAMPLE = 3` para
z-score y cercos de Tukey. Una categoría de 20 entra con margen sobre los dos;
una de 5 habría entrado al vocabulario sólo para producir ruido estadístico.

#### El orden del bloque tech es una decisión, no un accidente de tipeo

`clasificar` es una cadena de `if` donde el primero que matchea gana, así que
**el orden ES la regla de desempate**. La doctrina que lo gobierna es una sola:
**el contenedor gana sobre lo que contiene**. Cada posición tiene productos
reales detrás, contados sobre el catálogo:

| Regla de orden | Cuántos productos la obligan |
|---|---|
| Gabinete antes que Fuente | 23 gabinetes vienen con fuente incluida |
| Gabinete antes que Cooler | 268 gabinetes nombran sus fans |
| Fuente antes que Cooler | 27 fuentes nombran su cooler |
| Cooler antes que CPU | **321 de las 646 filas de `CPU` eran disipadores** |
| Cámara antes que Monitor | "Camara Wifi Ezviz BM1 Baby Call **Monitor**" |
| Mousepad antes que Mouse | "Mouse Pad Fantech MP64" |

`Cable` es la única que **no** se resuelve por aparición sino por sustantivo
LÍDER: "Fuente Segotep 500W ATX **Cables** Largos" nombra los suyos y no es un
cable, mientras que 130 de los 136 cables reales lo tienen como primera palabra.

**Lo que NO cambió, y estuvo cerca**: `Pelota` y `Paleta` viven en su propio set
(`CATEGORIAS_DEPORTE`), **fuera** de `INDUMENTARIA_O_CALZADO_EXTRA`. Meterlas
ahí habría sido lo natural —las vende una tienda de deportes— y habría hecho que
`GymratTagger` las taggeara y que los tres armadores consideraran una pelota una
prenda vestible. Es el mismo criterio por el que `RubroResolver` no deriva el
rubro de la categoría: qué **es** un producto y en qué **slot** entra son dos
preguntas distintas.

#### Las subcategorías nuevas se abstienen; sólo `Gorro` adivina

Las veintitrés reglas tier-1 que se sumaron —quince para las categorías nuevas y
ocho para indumentaria que no tenía— **ninguna trae entrada default
incondicional**. Sólo `Gorro` la tiene, de antes: si nada matchea, devuelve
`invierno`.

La diferencia importa porque `sub_categoria` alimenta filtros. Un `""` dice "el
nombre no lo aclara" y un filtro lo puede excluir; un valor adivinado dice algo
falso con la misma cara que uno leído. Es el mismo criterio con el que
`VisualAttrs.EMPTY` significa "el clasificador se abstuvo" y no "malo".

La cobertura pasó de 750 a 3.460 productos con subcategoría, y el diff viejo
contra nuevo muestra el costo real: gana 2.710, **pierde 9** —todas consecuencia
de productos que dejaron de ser `Conjunto`— y sólo 2 cambian de valor.

**Cómo se verificó que no rompe lo que ya andaba**: no alcanzaba con la suite.
Se corrió el clasificador viejo y el nuevo sobre las mismas 16.830 filas y se
diffearon los resultados. 3.295 productos cambian de categoría, **sólo 15 salen
hacia `Otros`** y las seis clases son correcciones. Ese diff —y no los tests—
encontró las tres regresiones que se arreglaron antes de mergear: "Zapatillas
Footy Mickey Mouse" y "Mochila Adidas Disney Minnie Mouse" entrando como
periférico, y `"core i"` comiéndose "Cloud Stinger **Core I**nalámbrico", un
auricular archivado como procesador.

---

### ¿Por qué React + Vite en el frontend?

**Decisión**: SPA en React 18 + Vite 8, servida como su **propio servicio** (no más static resource embebido en el JAR).

**Nota histórica**: este documento describía el frontend como "HTML/JS vanilla servido como static resource desde Spring Boot" — eso dejó de ser preciso mucho antes de `decouple-services-postgres` (el frontend ya era React/Vite, buildeando a `scraper/src/main/resources/static/`). El swap de `decouple-services-postgres` (Batch 3, design D6) es un cambio distinto y posterior: dejar de embeber el build de Vite en el JAR — el backend ahora es API-only (`SpaController` removido) y el frontend corre como servicio independiente, hablándole al backend por CORS (`APP_CORS_ALLOWED_ORIGINS`) usando `VITE_API_BASE_URL` como base de sus fetches (`frontend/src/api.js`).

---

### ¿Por qué el LLM vive fuera del backend y no puede escribir solo?

**Decisión**: el modelo corre en un proceso aparte (Ollama por defecto) al que Java le habla por HTTP detrás de la costura `ChatProvider`; el agente tiene tres herramientas, **todas de solo lectura**, y la única escritura real ocurre fuera de su loop, tras confirmación humana explícita y con re-validación server-side.

**Por qué**: embeber un runtime de inferencia en el JAR ataría el proyecto a un modelo y a un backend de hardware. Como proceso externo, el LLM es una dependencia **opcional**: si no responde, solo falla el chat del agente. Y como el loop autónomo no puede escribir, el tool-calling poco confiable de un modelo local de 14B degrada, en el peor caso, a *una propuesta rechazable* — nunca a una escritura corrupta.

📄 **Detalle completo en [`LLM_EMBED.md`](./LLM_EMBED.md)**: topología de la integración, la costura `ChatProvider` y su único adapter, el loop acotado (`MAX_ITERATIONS`), las tres herramientas, y las **ocho reglas** que gobiernan al agente — empezando por la Regla 0, que el system prompt es guía y no un control de seguridad. Para instalar y configurar, ver [`LLM_AGENT_SETUP.md`](./LLM_AGENT_SETUP.md).

---

### ¿Por qué los armadores de outfits puntúan con pesos y nunca con filtros?

**Decisión**: hay dos armadores con objetivos distintos —`OutfitService.armar` (aleatorio ponderado, superficie Gym) y `OutfitBudgetBuilder` (MCKP con branch-and-bound, superficie de presupuesto)— y **toda** señal que incorporan (oportunidad ML, likes del usuario, coherencia visual, diversidad de marca) entra como multiplicador acotado, nunca como descarte. La política es **una sola** y vive en `OutfitRules`.

⚠️ **Este párrafo describió durante meses una intención que el código no cumplía.** El budget builder maximizaba `baseMlScore` **crudo** —sin acotar y sin likes— porque los factores vivían privados en `OutfitService` y no los alcanzaba. Vale la pena registrar la forma del error, porque no se ve como un bug: un doc que afirma una política, una clase que la implementa bien, y una segunda clase que no puede leerla. Nada falla, nada se loguea, y el síntoma es "el builder elige cualquier cosa". Por eso los escalares se movieron a `OutfitRules` y `OutfitService` ahora los referencia en vez de declararlos: dos copias de una política es la misma trampa que las dos copias de `MAX_PAGINAS` o del vocabulario de plataformas.

**Por qué dos armadores**: son dos preguntas distintas. "Mostrame un outfit" quiere variedad entre recargas, así que muestrea; "armame el mejor outfit con $X" quiere el óptimo global bajo una restricción dura, que es literalmente un Multi-Choice Knapsack. Un solo algoritmo haría mal las dos.

**Por qué pesos y no filtros**: el catálogo argentino es chico y desparejo por categoría. Un filtro convierte "este short combina mal" en un **slot vacío**, y un outfit incompleto es peor producto que uno un poco ruidoso. Un peso degrada el candidato malo y lo deja alcanzable.

**Por qué el neutro se ancla en 1.0**: cada factor se normaliza contra el valor que produce una señal ausente — `mlFactor` divide por `baseMlScore(MlScore.EMPTY)`, la coherencia devuelve 1.0 cuando no hay atributos. Así, un catálogo sin datos de ML o sin clasificación visual produce **exactamente** los pesos previos. Es lo que permite agregar una señal nueva sin invalidar la suite existente como red de regresión.

**Por qué el builder apunta a `presupuesto / slots` y no a "lo más barato que entre"**: `baseMlScore` es `(100 - scoreP) + bonus`, donde `scoreP` es el **percentil de precio** del producto dentro de su categoría+género y los cuatro bonus (`ofertaReal`, `verified_deal`, `all_time_low`, `below_market`) son también observaciones de precio. Es una señal de **oportunidad**, no de calidad. Maximizarla sin acotar en una superficie de presupuesto invierte el pedido del usuario: cada peso gastado baja el objetivo, así que el techo pasa a ser algo que conviene esquivar, y con $100.000 disponibles el óptimo es el producto de $10.000. El reparto equitativo del presupuesto entre los slots abiertos da lo que el random assembler ya tenía —un **centro** al que acercarse en vez de un techo del que alejarse— y reusa la misma forma (`1/(1+distancia/mitadBanda)`) y el mismo `PRICE_BAND_PCT`, en vez de inventar una cuarta opinión sobre precios.

**Por qué ese término va en el score cacheado y no en `aporte`**: depende sólo del precio del candidato, no de la asignación parcial, así que puede computarse una vez por candidato. Pero la razón de fondo es otra: el pool se arma tomando el **top-60 por score**. Rankeado por ML crudo, ese top-60 era la cola más barata de cada categoría, y ningún término aplicado después puede elegir un producto que nunca llegó a ser candidato. Arreglar sólo la función objetivo y dejar el pool intacto habría dado un cambio que pasa los tests unitarios y no mueve nada en un catálogo real.

**Por qué la diversidad de marca se abstiene con `marca` vacía**: `BrandExtractor` la deja vacía cuando no puede decidir. Tratar `""` como una marca haría que **todo par de prendas sin marca se penalizara a sí mismo** — el mismo error que `CODE-5` prohíbe para los atributos visuales, y en un catálogo donde la extracción de marca falla seguido, sería la regla que más se dispara y la única que nunca acierta.

**Por qué la coherencia visual no rompe el óptimo del MCKP**: la penalización se aplica como **resta de un monto no-negativo** al score del candidato, así que `aporte ≤ score` siempre y la cota superior del branch-and-bound —construida con scores sin penalizar— sigue siendo válida. Ninguna rama óptima se poda. Además cada par de slots se evalúa exactamente una vez, cuando se asigna el segundo de los dos, así que el total no depende del orden en que el solver recorre los slots.

**Por qué la regla de color es una rueda de tonos y no una tabla de pares**: una lista escrita a mano de "estos colores combinan" es un conjunto de opiniones que nadie puede revisar y que crece cada vez que alguien discrepa con una entrada. Un orden circular de tonos con un umbral de distancia es una estructura chequeable y un solo parámetro que tunear. Los neutros no tienen posición en la rueda — por eso combinan con todo, sin necesidad de enumerarlo.

**Por qué un atributo visual vacío no penaliza**: vienen de un clasificador zero-shot que se abstiene cuando duda, así que buena parte del catálogo no los tiene. Una regla que castigara el dato faltante no estaría coordinando outfits: estaría degradando en silencio a todo producto que el clasificador salteó.

### ¿Por qué el combo de suplementos clasifica comida con una regla de ORDEN y no con una lista de sustantivos?

**Decisión**: el builder de `/suplementos` tiene 12 subtipos de comida (pasta de maní, avena, granola, galletas, fideos, snack salado, frutos secos, infusiones, bebida y postre proteicos, mermelada, miel) y cada uno lleva el veto `esElSaborDeUnPolvo`, que es el espejo exacto del que ya protegía a `Proteína en Polvo`.

**Qué había antes**: nada. Todo lo que el clasificador tagea `Alimentos` llegaba al pool —esa categoría siempre estuvo en el whitelist— pero ningún subtipo lo reclamaba, así que era invisible. Peor: los productos que `FORMATO_NO_POLVO` vetaba del bucket de proteína ("Leche con Proteína", "Avena Alta en Proteína", "Pan Proteico") quedaban vetados del único subtipo que podía tenerlos y sin ningún otro donde caer. El veto era correcto y el efecto neto era que el producto desaparecía.

**Por qué el riesgo es simétrico**: un sustantivo culinario también es como un polvo nombra su **sabor**. Agregar "dulce de leche", "chips", "granola" o "avena" como keywords le habría robado a `Proteína en Polvo` justo los sabores más comunes del catálogo — el mismo bug que `FORMATO_NO_POLVO` cerró, reintroducido desde el otro lado.

**Por qué el orden y no una lista**: enumerar sabores pierde para siempre, igual que enumerar conectores. Lo estable es que un polvo se nombra por la cabeza y el sabor va detrás ("Whey Protein sabor Dulce de Leche"), mientras que un alimento arranca por el alimento y menciona la proteína después, como claim ("Leche con Proteína", "Avena Alta en Proteína"). `esProteinaAgregadaAUnAlimento` ya leía ese orden para vetar polvos; `esElSaborDeUnPolvo` lee el mismo orden para vetar comida. Son la misma observación desde los dos lados, así que **no pueden contradecirse**: ningún producto puede quedar vetado de los dos buckets ni aceptado por ambos.

**Por qué el veto se deriva de una bandera y no se lista a mano**: `SubtipoSuplemento.comida(...)` lo aplica sola. Un mapa escrito a mano deja al subtipo nuevo sin veto, y la falla no es visible — el producto no falta, aparece uno de más en el lugar equivocado.

**Por qué la comida no entra al combo del outfit de Gym**: ese combo se armaba con TODOS los subtipos, así que cada tipo nuevo le agregaba una tarjeta a una grilla que ya tenía 21 — el crecimiento de 17 a 21 que figuraba como pendiente nunca fue una decisión, fue un efecto. Ahora `OutfitsEndpoints` pide `TIPOS_COMBO_OUTFIT` explícito: ahí el stack es una sugerencia fija, y elegir es el trabajo de `/suplementos`, que sí los ofrece completos.

### ¿Por qué el armador de PCs vetea por abstención cuando hay una preferencia pedida, arregla el ruido en el clasificador y no en el armador, y rankea el chipset relativo a la gama?

**D2 (fase 7, `pc-builder-deep-taxonomy`) — la abstención vuelve a vetar cuando
el usuario pidió algo.** Es la misma inversión que ya regía para `gama` desde
fase 6 (`pc-builder-gama`): en el resto del armador "no sé" no filtra nada
—los vetos de socket/DDR/form factor, `VisualCoherence`— porque vetar sobre
una abstención vacía el pool sin necesidad. Pedir una preferencia técnica
cambia esa cuenta: si el usuario pide DDR5 y una mother no dice su DDR (ni se
puede derivar del socket), no hay forma honesta de afirmar que la cumple, así
que se descarta igual que si dijera la DDR equivocada. **La excepción es
`ramDual`/`wifi`, y está escrita a propósito**: en el resto de las
preferencias un candidato puede genuinamente no decir nada; `wifi` y
`ramDual` no tienen ese tercer estado — el nombre de una mother o afirma
"wifi" o no lo afirma, y esa ausencia YA es la respuesta completa ("no tiene",
no "no sé"), así que no hay abstención de más que vetear. Tratarlas como el
resto habría inventado una incertidumbre que el dato no tiene.

**D5 — el ruido de clasificación se arregla en el clasificador, nunca en el
armador.** El slot Gabinete elegía un service de armado porque `KW_GABINETE`
matchea `"para gabinete"` sin mirar el sustantivo líder del nombre — el mismo
patrón que ya protegía a `Cable` (`"Fuente ... Cables Largos"` no es un
cable, ver Taxonomía y clasificación en `CLAUDE.md`). Corregirlo en
`PcBuilder` con un veto adicional del slot habría escondido el síntoma sin
tocar la causa: el producto seguiría mal categorizado para `/catalogo`, para
el ML, para cualquier otra superficie que lea `categoria` — exactamente el
motivo por el que `richer-category-taxonomy` y `close-1nf-and-3nf-foundation`
ya tratan la categoría como un dato compartido, no una opinión del armador.
Generalizar el sustantivo líder (`bracket|filtro|service|kit|fan|soporte` +
`"para gabinete"` como destino) resolvió Gabinete, y de paso destapó el mismo
patrón faltante en dos lugares más —`KW_CPU_LIDER` corriendo después de
`KW_COOLER`, `KW_PC_LIDER` corriendo después de `KW_GPU`—: tres síntomas con
forma distinta, una sola causa, el orden de los keywords, no su presencia.

**D9 — el tier de chipset se rankea relativo a la gama pedida, no en orden
absoluto.** T3 de fase 7 le dio a mother un eje de tier de chipset (X/Z > B >
A/H) y sin gama pedida eso alcanza: más caro casi siempre es mejor. Pero con
gama pedida, "mejor tier" deja de ser la pregunta correcta — con el ranking
absoluto, una build de gama MEDIA seguía llevándose la `Asrock Z790I
Lightning WIFI` de **$284.037** (tier X/Z, la misma que gana sin ninguna gama
pedida) contra el pedido explícito del usuario. D9 rankea por **distancia**
al tier objetivo de la gama (ALTA→X/Z, MEDIA→B, BAJA→A/H) en vez de por tier
absoluto; sin gama pedida cae al orden de T3. Medido contra el catálogo real:
la misma preferencia MEDIA baja el pick de mother de $284k (tier X/Z) a una
`B850M` OUTLET de $83.300 (tier B) — el tier B gana porque está más cerca del
objetivo, no porque sea "peor" en abstracto. Sin esto, pedir una gama barata
seguía comprando la mother más cara del catálogo.

**Por qué `socketsSoportados` (el veto cooler↔mother, D6 de fase 7) no se
persiste.** `producto_tech_specs` guarda `socket_id` **singular** con FK a
`socket` (D10 de fase 6: abstención = NULL, nunca una fila de lookup) — el
mismo molde que ya usan `gama`, `ddr`, `tipo_almacenamiento`. Un cooler real
puede listar varios sockets compatibles (`"AM5 y AM4"`, `"115x y 1200"`), y
eso es una lista, no un escalar: forzarla en una columna FK singular perdería
sockets o exigiría una tabla `producto_tech_specs_socket` N:M aparte que
ninguna superficie pide todavía. La compatibilidad se sigue calculando **al
armar**, desde el snapshot en memoria — igual que el resto de `TechSpecs`
(D3d de fase 6: la tabla existe, el armador no la lee) — así que no persistir
la lista no le saca nada al usuario hoy; queda diferido, no descartado.

### ¿Por qué el armador de PCs compara la generación como año, usa dos órdenes de eje distintos para CPU y GPU, y reparte el presupuesto por cuotas?

**D1/D2 (fase 8, `pc-builder-top-tier`) — `generacion` no era una magnitud,
eran dos.** El eje de CPU y GPU era `gama → generación desc`, y ese número
significa cosas distintas según la marca: en Intel es la generación Core real
(`i7 14700F` → 14), en AMD y Nvidia es el dígito de los **miles del modelo**
(`Ryzen 9 9950X3D` → 9, `RTX 5080` → 5). Comparados crudos, `14 > 9 > 5`
significaba que **Intel le ganaba a AMD en CPU y AMD a Nvidia en GPU,
siempre**, por aritmética y no por potencia. Es la misma clase de bug que las
RX 9000 de fase 7 (*"Radeon numera de DOS maneras"*), y la respuesta es la
misma: ramificar por marca antes de comparar. La tabla de años es **por
slot, no global**, porque `AMD`+`9` es Ryzen 9000 (2024) en CPU y RX 9000
(2025) en GPU — un mapa único afirmaría que son lo mismo. Sin marca legible el
valor abstiene y va último: un número sin escala no puede rankear contra uno
que sí la tiene, y meterlo a la fuerza es exactamente el bug que se está
cerrando.

**Por qué hizo falta `nivel` además del año.** `Gama` es una escala de tres
peldaños, y `ALTA` mete en la misma bolsa a un `i7` y a un `i9`, y a una `RTX
5090` y una `RX 9070`. El dígito de familia (CPU) y la decena del modelo (GPU)
son el escalón de adentro, y a diferencia de la generación **sí** son
comparables entre marcas: un `i9` y un `Ryzen 9` son pares, una `RTX 5080` y
una `RX 9080` también. Cobertura medida: 88% en CPU, 89% en GPU.

**Por qué el orden de los dos ejes difiere entre CPU y GPU.** Es la parte
menos obvia, y es medida, no estética. En CPU el nivel va **antes** que el
año: el dígito de familia es un escalón estable y de vida larga —un `i9` es el
tope de su generación, siempre— así que un `i9` de 2023 vale más que un `Ryzen
7` de 2024. En GPU el año va **antes** que el nivel: el escalón de modelo no
sobrevive a cinco años de proceso, y con el nivel primero una `RX 6900 XT`
(x90 de 2020) le ganaba a una `RTX 5080` (x80 de 2025). Un solo orden para los
dos ejes se equivoca en uno de los dos casos; la salvaguarda es que `gama`
corre antes que ambos, así que una x50 nueva nunca le gana a una x90 vieja
—están en gamas distintas— y el orden por año sólo desempata dentro del mismo
tier.

**D3 — por qué el presupuesto se reparte por cuotas y no se gasta greedy.**
`PcBuilder` le daba a cada slot **todo** el restante, y como el precio es sólo
desempate, cada slot se llevaba el mejor candidato que entrara. Medido con
$2.000.000: la RAM se llevaba $1.102.200 —el 55% de la caja— y cuando llegaba
el turno del slot `fuente` no quedaba nada asequible, así que caía al fallback
*"gastá lo mínimo"* y elegía la fuente más barata del catálogo, sin certificar.
El síntoma que reportó el usuario fue "las fuentes no están certificadas", pero
**el ranking de fuente ya era correcto** (certificación desc; sin presupuesto
elige la MSI 1600W Titanium): nunca llegaba a ejercerse. Arreglar la regla de
certificación —vetar `NINGUNA` cuando hay gama pedida— habría atacado el
síntoma: el slot habría salido vacío con un mensaje en vez de traer una fuente
mala, que no es lo que el usuario pidió. La causa es la asignación, no el
filtro, y por eso `ReglaCertificacion` no se tocó.

**Por qué las shares se normalizan sobre los slots presentes.** Una tabla por
combinación (con GPU, sin GPU, con cooler de gama ALTA, sin cooler) son cuatro
listas que tienen que sumar 1.0 cada una y que hay que tocar juntas cada vez
que aparece un slot. Normalizar `share_i / Σ shares presentes` es una sola
tabla que ya cubre las cuatro y cualquier slot futuro. Un slot fuera de la
tabla toma la share media en vez de cero: un slot que nadie agregó debe recibir
algo de plata, no quedar condenado al fallback del más barato en todo armado
con presupuesto. Las proporciones en sí son **supuestas, no medidas**, igual
que el piso de watts de `EstimadorDeConsumo`, y están documentadas como tales.

**Por qué el fallback "el más barato" se conservó.** Cuando ni con el arrastre
entra nada en la cuota, el slot podría salir vacío con un motivo, como hace
`sinCompatible`. Se eligió lo contrario: un armado incompleto es peor que uno
con un componente flojo, y `sinCompatible` significa "ningún candidato pasó un
veto de compatibilidad" —una afirmación técnica— mientras que "no te alcanza"
es una afirmación sobre la plata. Mezclarlas haría que el mensaje del slot
dejara de significar una sola cosa.

**Por qué presupuesto vacío es un modo, no un caso borde.** Sin presupuesto no
hay cuotas ni filtro de precio: gana el mejor de cada slot por eje técnico,
cueste lo que cueste. Es la respuesta a "si quiero ir a lo top top", y D1/D2
son justamente lo que la hacen cierta — antes de ellos ese modo devolvía un
`i7 14700F` y una `RX 9070` teniendo un `Ryzen 9 9950X3D` y una `RTX 5080` en
el catálogo.

**Lo que esta fase NO cerró, y por qué.** El `Ryzen 9 9950X3D` sigue sin salir
en el modo top-top, y no es el ranking: la mother se elige primero (`Asrock
Z790I`, `LGA1700`) y `ReglaSocket` veta todo AM5 después, así que el `i9
14900K` es el tope real de **esa** plataforma. La mother es el ancla por
diseño (fase 2) y no se prueba una segunda; elegir plataforma en vez de mother
es un cambio de otro tamaño, y se deja anotado en vez de resuelto a medias.

### ¿Por qué Morashop tiene page y plataforma propias si es un Tiendanube común?

Porque el valor de `plataforma` no describe la tienda, **rutea el scraper**. Desde `V20` `ScraperFactory` elige la clase leyendo `sitio.plataforma` vía `SiteRegistry`, y los name-sets en código se borraron (`CODE-6`). Morashop necesita una page propia, así que necesita un valor propio; rutearla por nombre de sitio reintroduciría exactamente lo que `V20` sacó. `monkyforce` ya había sentado el precedente. El costo aceptado es que `plataforma` sigue derivando hacia "discriminador de ruteo" más que hacia "qué software corre la tienda" — una deriva que ya existía con `vaypol` y `qloud`.

La page propia **no toca la extracción**. El extractor compartido lee las cards de Morashop sin un solo cambio; se verificó corriendo `buildExtractorJs()` verbatim contra `/suplementos/proteinas/` en un Chrome real, con 50 productos limpios. Lo que cambia es la navegación, y por dos motivos distintos que conviene no mezclar:

**Uno: la tienda no tiene URL de catálogo.** `/productos/` es una landing del tema con cero productos y `/suplementos/` es un índice de subcategorías, también cero. El catálogo vive un nivel más abajo. La convención de Tiendanube dice que `/productos/` lista todo; el tema puede pisarla, y cuando la pisa el fallo es de los caros: cero productos sin error y sin página vacía. Se evaluó y **descartó** el sitemap como fuente de enumeración: `/sitemap.xml` trae 1724 URLs, todas `/productos/{slug}/` planas y sin señal de categoría, así que acotar a suplementos exigiría visitar 1724 páginas de producto contra 12 listados.

**Por qué se descubren las hojas en runtime y no se hardcodean las 12**: una lista fija es correcta el día que se escribe y se pudre en silencio el día que la tienda agrega la categoría 13. La alternativa barata —hardcodear más un test que pegue al landing y compare— habría metido el primer test con dependencia de red del repo, que se pone rojo cuando el sitio se cae y no cuando nosotros nos equivocamos. El descubrimiento se parte en un helper estático puro sobre hrefs (testeado con fixtures, sin browser, igual que `resolveNextPageFromHrefs`) más un borde de browser de una línea que no decide nada. Una sola regla hace todo el trabajo de alcance —una hoja es un path del mismo host exactamente un segmento debajo de la sección— y eso solo excluye el índice, las sub-subcategorías y las secciones hermanas, sin lista negra que mantener.

**Dos: la API está apagada por correctitud, no por velocidad.** Hoy `/api/v1/{storeId}/products` da 404 en Morashop, igual que en Entreno, así que intentarla sólo desperdicia dos navegaciones. Pero Morashop además vende supermercado, electro-hogar y bodega, y esa API devuelve la tienda **entera** sin filtro por sección. Si el endpoint se habilitara del lado del servidor, una page que siguiera intentándolo importaría tres rubros que no tienen valor en el dominio de `rubro` — y meterlos a la fuerza haría justo lo que `V6` existe para impedir. Depender de que un endpoint ajeno siga roto no es un diseño; `usaApi()` lo apaga.

**Por qué el descubrimiento vacío tira excepción en vez de devolver una lista vacía**: `SiteYieldGuard` detecta colapso comparando contra la corrida anterior, así que sólo ve **caídas**. Un sitio que rinde cero en su primera corrida —o que ya venía en cero— nunca lo despierta. Sin el throw, "cambió el markup del landing" es indistinguible de "la tienda está vacía". Mismo criterio y misma forma que `MaximusPayloadException`.

### ¿Por qué el tope de páginas de Tiendanube pasó a ser configurable?

Porque era un número sin dueño. El loop paraba en 25 páginas, un valor compartido por los trece sitios TN que nadie eligió pensando en ninguno de ellos. El catálogo real de Entreno son 53 páginas de 12 productos —la 54 devuelve cero— así que ese techo descartaba cerca de la mitad, ~313 de ~636, sin error y sin nada que un operador pudiera ver.

**Por qué se subió el default global y no se le puso un caso especial a Entreno**: un override por sitio habría arreglado Entreno y dejado la bomba armada para el próximo catálogo que crezca. El tope nunca fue el mecanismo de corte real —quien corta es el chequeo de dos páginas vacías seguidas, que en Tiendanube funciona porque pasado el final sirve una página vacía en vez de repetir la última como hace osCommerce—, así que subirlo a 60 no cambia dónde termina ninguna corrida sana: sólo deja de truncar las que el techo cortaba. El override por sitio queda como escape, no como el arreglo.

**El default vive en `TiendanubePage`, no en `ScraperConfig`**: `ar.scraper.pages` no importa `ar.scraper.config` y esa frontera valía la pena conservarla, pero tener el número dos veces valía menos. La solución es que la page sea dueña de la constante, que config sólo parsee el override, y que el scraper —el único que ya depende de las dos capas— les pase el fallback. Una definición sola (`CODE-6`) sin invertir la dependencia.

### ¿Por qué un lector no ve la corrida que está en curso?

Porque durante un scrape el catálogo no es un estado, es una transición. El
soft-delete desactiva lo ausente, el upsert re-toca lo presente y
`fromDBParcial` rearma el snapshot en memoria una vez por sitio terminado: entre
el sitio 1 y el 26 el catálogo pasa por veintiséis formas intermedias, y ninguna
es una foto de nada. Servir eso hace que un producto desaparezca de la búsqueda
y reaparezca dos minutos después sin que nadie haya tocado nada.

El aislamiento tiene **dos mitades** porque hay dos familias de lectores:

| | Qué lee | Cómo se aísla |
|---|---|---|
| `/api/data`, `/api/facets` | SQL contra `productos` | Cota `touched_at < started_at` en los cinco predicados `activo` |
| `/api/mejores`, `/api/grupos`, outfits, agente | El snapshot en memoria | `servedResult`: la referencia a `lastResult` tal como estaba al arrancar |

**Por qué en memoria es una referencia y no una cota**: acotar el snapshot
significa re-filtrar ~20k productos en cada request, y `/api/grupos` ya reagrupa
el catálogo filtrado entero por request a través de `AccentStripper`, un hot path
documentado. `AggregatedResult` ya es copy-on-write, así que retener la
referencia vieja cuesta ~15 MB retenidos y **cero** en el pico — la corrida ya
sostiene dos o tres catálogos profundos por sitio terminado. O(n) por request
para ahorrar una referencia es el trade equivocado.

**El lector se aísla del SCRAPE, no de sí mismo.** Los cuatro caminos por los que
un usuario cambia el catálogo a mano —soft-delete manual, reclasificación del
agente, activar un preset de financiación, y `DELETE /api/db/productos`— parchean
**las dos** fotos bajo `catalogLock`. Olvidarse de uno no rompe nada visible: da
un defecto que sólo existe mientras hay una corrida abierta, que es exactamente
la clase de bug que ningún test de una sola foto puede ver.

**Por qué la cota SQL se suprime hasta que haya una corrida `COMPLETED`**, y no
"hasta que haya alguna corrida": en una instalación nueva la primera corrida *es*
una corrida. Con la regla floja la cota se aplicaría, ninguna fila cumpliría
`touched_at < started_at`, y el dashboard serviría una pantalla vacía durante todo
el primer scrape — justo lo que la supresión existe para evitar. Los otros estados
terminales (`CANCELLED`, `INTERRUPTED`, `ERROR`) tampoco cuentan: dejan el catálogo
a medio barrer, o sea sin un estado previo limpio en el que sostener al lector.
Ausencia de cota significa **servir todo**, nunca "cota = epoch, no servir nada".

La foto en memoria no necesita ese guard porque degrada sola: sin catálogo previo
`servedResult` queda null y el lector cae al vivo, que es precisamente ver el
progreso. Queda una ventana angosta y aceptada —una instalación que ya tenía
productos pero ninguna corrida registrada, o sea el primer scrape después de
`V29`— en la que las superficies SQL muestran el movimiento y las de memoria no.
Dura una corrida y se cierra sola.

**`/api/producto/{key}` queda exento**, por el mismo motivo por el que ya está
exento de `activo`: una ficha no puede tirar 404 a mitad de una corrida. No es una
excepción escrita a mano — entra por `obtenerProductoPorKey`, que nunca pasa por
`CatalogQueryRepository`, así que no hay nada de qué eximirlo. Hay un test que lo
fija para que agregarle la cota rompa el build.

**Adoptar la corrida y aislar al lector son una sola operación** (`adoptarCorrida`),
no dos que haya que acordarse de llamar juntas. Hay **tres** caminos que ponen una
corrida en curso —la normal, la retomada, y la retoma que sólo debe el barrido
final— y sólo el primero pasa por `abrirRun`. Con el aislamiento colgado de
`abrirRun`, los otros dos servían el catálogo a medio rearmar durante todo el
scrape, justo en el escenario donde más importa: una retoma corre sobre un
catálogo que ya quedó a medias. Ninguna de las dos ramas podía verlo con sus
propios tests —una no tenía aislamiento, la otra no tenía retoma— y las dos
mitades de `ScraperService` auto-mergean limpio, así que git tampoco avisa.

**La cota es `<` estricto, deliberadamente al revés que el `>=` de la unión del
soft-delete.** Es la misma columna en direcciones opuestas y las dos son
correctas: el barrido tiene que **proteger** filas de ser borradas, así que
incluye el segundo del arranque; el lector puede **ocultar** una fila fresca de
más, que no le cuesta nada a nadie. Una fila tocada en el primer segundo de la
corrida queda oculta, no visible temprano.

### ¿Por qué la oferta de retomar vive en el layout y no en `/splash`?

Porque `/splash` es la pantalla que un ADMIN **no** ve después de una caída.

Una corrida interrumpida commiteó los sitios que alcanzó a terminar, así que
`GET /api/status` reporta `tieneData: true`, y `RootGate` —que rutea por
exactamente ese campo— manda la primera visita a `/catalogo`. Un banner montado
dentro de `SplashPanel` sólo aparecería si el operador navegara a `/splash` a
mano, adivinando que hay algo ahí que mirar. Sería invisible justo en el único
escenario para el que existe.

Montarlo a nivel `AppLayout` lo pone en toda ruta de la app, sobre el read único
de `isAdmin` que ese archivo ya hace. **Es una oferta, no un secuestro**: la ruta
debajo no se toca y nada redirige, que es lo que pide la regla de que un ADMIN
pueda estar donde quiera durante una corrida. Retomar es lo que navega a
`/splash`, porque ahí es donde está el progreso.

**El gate de rol es sobre la request, no sobre el render.** `GET /api/scrape/interrupted`
y `POST /api/scrape/resume` son ADMIN en `ApiRoutePolicy.TABLE` —medido: VIEWER
403 en las dos, anónimo 401— así que preguntar y esconder la respuesta compraría
un 403 por una pregunta que no hay que hacer. Un VIEWER no emite la llamada.

**No hay endpoint para descartar, y la UI lo dice en vez de disimularlo.**
`ScraperService.interrumpida` se limpia únicamente dentro de `reanudar()`. El
botón secundario dice **"Ocultar por ahora"**, nunca "Descartar": esconde el
aviso en esta sesión y un reload lo trae de vuelta, porque la corrida sigue
interrumpida. La alternativa —un "Descartar" que en realidad sólo oculta— sería
un botón mintiendo sobre estado que el cliente no posee.

**El poller no se arma solo, y eso era la mitad faltante.** Sólo el botón de
lanzar armaba el intervalo, así que aterrizar en `/splash` con una corrida ya
`RUNNING` —que es exactamente lo que pasa al retomar, y también tras un reload
a mitad de corrida— escribía `RUNNING` en pantalla y se quedaba ahí: status
congelado, sin progreso y sin completar, mientras la pestaña siguiera abierta.
La bandera que lo dispara la levanta la lectura de montaje **una sola vez**; si
espejara el status vivo, el efecto que la observa re-armaría el intervalo en
cada render que viera una corrida en curso.

### ¿Por qué `/picks` abre en un carrusel de rubros y no en la galería entera?

Porque la galería completa nunca fue una pantalla de entrada: es el destino.

`/api/mejores` devuelve hasta 40 categorías, y sin filtro de rubro eso son
40 cards ordenadas por cantidad de productos —no por nada que el usuario esté
buscando— sobre las que se aterrizaba sin haber elegido todavía qué mirar. La
barra de rubros existía para acotarlas, pero es chrome de dashboard: dice qué
filtros hay, no qué hay adentro de cada uno. Un rubro con 12 categorías y otro
con 3 se veían exactamente igual antes de entrar.

El carrusel de entrada muestra los **cuatro rubros reales** —el vocabulario de
`lib/rubros.js` menos su neutro `''`, que es "Todos" y sigue estando en la barra
de la galería— cada uno con su portada, su cantidad de categorías y su cantidad
de productos. Elegir uno recién entonces abre la galería. `RubroCard`
(`ui/rubro-card.jsx`) es un `role="button"`, no un `<a>`: un rubro es un paso del
flujo local del panel, no una ruta —el mismo criterio que `CategoryCard`—.

**Las portadas salen del catálogo, no de un banco de imágenes.** Cada card usa
el `imgCat` del primer pick de su propio rubro. Una URL de stock hardcodeada
sería una dependencia de red externa para decorar datos que ya tenemos, y
mostraría lo mismo con el catálogo lleno que con el catálogo vacío.

**El costo es cuatro requests en el montaje donde antes había una**, porque la
pantalla de entrada necesita los cuatro rubros a la vez para poder dibujar sus
contadores. Se paga una sola vez: los `cats` quedan cacheados por rubro, así que
ir y volver entre el carrusel y una galería —y cambiar de solapa dentro de la
galería— no emite ninguna request más. `/api/mejores` lee el snapshot en memoria,
la misma clase de trabajo que `/api/grupos` hace por request.

### ¿Por qué el backend no sirve TLS y sólo le cree al proxy de loopback?

Porque el que termina TLS es otro proceso, y decidir *a quién* creerle es la
única parte que no se puede delegar.

El backend escucha HTTP en claro y siempre lo hizo. Con un terminador adelante
—`tailscale serve`, nginx, un LB— el request que llega a Tomcat es HTTP, así
que sin configuración `request.isSecure()` es `false` para siempre y
`getRemoteAddr()` devuelve la IP del proxy en vez de la del cliente. Eso último
no es cosmético: es lo que colapsa la ventana per-IP de `ResetRateLimiter` en un
único balde compartido por todos, en silencio y sin fallar. `LoginRateLimiter`
no tiene el problema porque deliberadamente no usa IP.

`server.forward-headers-strategy=NATIVE` hace que Tomcat lea
`X-Forwarded-Proto/For/Host`. Pero un header es una afirmación del cliente, no
un hecho, así que la pregunta real es a quién se le cree.

**El default de Tomcat confía en todo rango privado, y esa es exactamente la
forma equivocada acá.** En una LAN el propio cliente vive adentro de
`192.168/16`: cualquiera en la wifi manda un `X-Forwarded-For` inventado y se
mueve de balde en el rate limit a voluntad. Por eso
`server.tomcat.remoteip.internal-proxies` queda restringido a **loopback** —
sólo un proxy corriendo en esta misma máquina—, que es la allowlist concreta que
el javadoc de `LoginRateLimiter` venía pidiendo. Se abre con
`APP_TRUSTED_PROXIES` el día que el proxy viva en otro host, y ese día el valor
es la IP de ese proxy, no un rango.

**Medido contra un proceso real** (jar levantado en un puerto aparte, 2026-08-28),
usando `Strict-Transport-Security` como sonda —Spring Security sólo lo emite
cuando el request es seguro—:

| Peer | `X-Forwarded-Proto: https` | HSTS |
|---|---|---|
| `127.0.0.1` | sí | **presente** — el header se creyó |
| `192.168.100.200` | sí, el mismo | **ausente** — se ignoró |

No hay test automatizado de esto: `@WebMvcTest` usa MockMvc y no levanta Tomcat,
así que el valve no corre, y un test que afirme la string de la property se
pondría verde sin ejercitar el mecanismo. Vale la regla que este repo ya
aprendió con auth: la verificación es contra un proceso real.

### ¿Por qué el CLI levanta el terminador TLS y no un script aparte?

Porque un modo que depende de que alguien haya corrido otra cosa antes no es un
modo: es una convención, y una convención que nadie recuerda falla en silencio.

`start lan` necesitaba tres pasos previos —generar el certificado, levantar el
proxy, exportar dos variables— y si faltaba alguno el resultado no era un error
sino una app que carga y no anda **en el otro dispositivo**, que es donde menos
se puede diagnosticar. Ahora `start lan` detecta la IP, genera el certificado,
levanta el proxy y deriva los orígenes; `stop` lo baja.

**El backend sigue sin servir TLS, y ahí está el punto.** La alternativa era que
Vite preview y Spring Boot sirvieran HTTPS ellos mismos: menos piezas, cero
Docker, y ningún proxy que administrar. Se descartó porque agrega **otra**
divergencia entre desarrollo y producción, en un repo donde eso ya escondió dos
bugs de auth —`vite dev` es same-origin y ninguna instalación real lo es—. Con
el proxy, la topología de `lan` es la misma que la de un deploy: TLS afuera,
HTTP adentro, `X-Forwarded-*` en el medio. Lo que se prueba en el celular es lo
que se va a instalar.

El costo, explícito: **el modo `lan` necesita Docker**. `local` no, y es el
default. Sin Docker el modo falla nombrando la causa en vez de arrancar a medias.

**La IP se detecta abriendo un socket UDP que no envía nada.** Enumerar
interfaces obligaría a adivinar entre `docker0`, `lxcbr0` y `virbr0`, ninguna
alcanzable desde un celular; dejar que el kernel elija la ruta hacia afuera da
la única que sirve. `SCRAPPY_LAN_IP` la pisa, y `SCRAPPY_*_ORIGIN` siguen
ganando sobre todo, para un túnel o un deploy cuyo nombre esta máquina no puede
deducir.

### ¿Por qué el origen del backend se resuelve en runtime y no en el build?

Porque "a qué backend le habla el frontend" es una propiedad del **arranque**,
no del artefacto, y tratarla como propiedad del artefacto congelaba una decisión
de arquitectura en el momento más temprano y menos informado posible.

Vite hornea `VITE_API_BASE_URL` en el bundle. Con eso solo, elegir entre
"localhost" y "alcanzable desde otro dispositivo" vivía en dos archivos
persistentes —`.env` y `frontend/.env`— y cambiar de idea costaba editar los dos
y reconstruir. Peor: el `.env` quedaba apuntando a una infraestructura que podía
no estar levantada, y entonces la app local arrancaba rota **sin decir por qué**
—el bundle llamando a un proxy apagado— que es exactamente cómo se descubrió
esto.

Ahora `frontend/src/api.js` lee `window.__API_BASE__` primero y cae al valor de
build sólo si está vacío. Ese global lo setea `dist/config.js`, que
`cli/core/runtime_config.py` reescribe en cada `start`. **Un build sirve
loopback, un origen de LAN detrás de TLS y un deploy**: `start local` y
`start lan` producen el mismo `dist/` byte a byte y sólo cambian ese archivo
—verificado comparando el md5 del bundle entre modos—.

**El modo no se persiste en ningún lado, y es a propósito.** `apply_mode` muta el
dict del `.env` ya parseado, nunca el archivo. Un modo guardado en disco es la
misma trampa de nuevo: un estado que sobrevive al proceso que lo justificaba y
que después nadie recuerda haber elegido.

**Tres cosas viajan juntas o el modo miente**: el origen que el bundle llama, la
URL que el navegador abre (`APP_OPEN_URL`) y el allow-list de CORS. Las tres las
fija `apply_mode`, y la de CORS **suma en vez de reemplazar** — pisarla dejaría
afuera a la máquina que está corriendo todo esto.

**`lan` sin `SCRAPPY_*_ORIGIN` falla ruidosamente** en vez de caer a localhost. Un
bundle que desde un celular llama a `localhost:3000` está llamando al celular:
la app carga, no anda, y nada en pantalla lo explica. El fallback silencioso
sería la falla que este mecanismo existe para evitar.

El valor de build sigue siendo el fallback, así que un `npm run build` corrido a
mano desde `frontend/` se comporta igual que antes, y `frontend/public/config.js`
es un archivo inerte que Vite copia a `dist/` para que un build no gestionado
sirva algo válido en vez de un 404.

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

### Why no springdoc for the interactive API console (`swagger-ui-admin-gated`)

**Decision**: `GET /api/openapi.yaml` streams the hand-written `docs/openapi.yaml`
from a classpath resource via one small `ar.scraper.web` controller. No
springdoc, no `@Schema`/`@ApiResponse`, no `OpenAPI` bean.

**Why not springdoc.** ADMIN-gating cannot be enforced on a static asset in
`dist/`, so a backend endpoint is needed either way — at which point
springdoc's only job left is vendoring assets `swagger-ui-react` (the
frontend dependency this change adds) already ships. The cost would have
been a new dependency, extra `ApiRoutePolicy` rows, and a permanent blind
spot in `RouteCoverageTest`/`OpenApiRouteCoverageTest`: springdoc registers
under `org.springdoc.*`, outside the `ar.scraper` scan both guards rely on.

**Classpath bundling, not a runtime `docs/` walk.** A path relative to
`user.dir` only works because Maven's test JVM sits one hop below the repo
root; it breaks in Docker, where no `docs/` exists. `pom.xml` gained a
`copy-resources` execution bundling the file at `contract/openapi.yaml` — a
neutral prefix, never `static/`/`public/`/`resources/`/`META-INF/resources/`,
which Boot serves directly and would bypass `ApiRoutePolicy`. `Dockerfile`
gained a matching `COPY docs/openapi.yaml` before `mvn package`, since
`copy-resources` over a missing source dir only warns and still succeeds.
`OpenApiRouteCoverageTest` gained one additive byte-identity test closing
that silent-failure path.

### ¿Por qué `ar.scraper.indices` nace con puertos, cuando `InflacionService` se movió sin ninguno en F3a?

**Decisión** (`indices-service`): retirar `InflacionService` (vivía en
`financiacion/`) y reemplazarla por un área nueva, `ar.scraper.indices`,
dominio puro + dos puertos (`FuenteIndicePort` hacia las fuentes HTTP,
`IndicePort` hacia la base) detrás de un único entry point, `IndiceService`.

**Por qué no alcanzaba con repetir el movimiento de F3a.**
`InflacionService` mezclaba cinco responsabilidades en una sola clase: HTTP
contra dos fuentes, parseo, estado en memoria, el `@Scheduled` y la
matemática del ajuste — y sus tres consumidores (`SenalEnricher`,
`FinanciacionEnricher`, `FinanciacionEndpoints`) dependían de la clase
concreta, no de una interfaz. De hecho fue la única de las áreas que la
cadena de puertos (F2, 13 puertos) y el cierre de ciclos (F3a, arriba en este
documento) dejaron sin uno: en F3a se la reubicó *sin* puerto a propósito,
porque no importaba una sola clase de `ar.scraper` y podía colocarse donde
correspondía sin ceremonia — colocación antes que abstracción, la misma regla
que gobernó esa fase. `indices-service` es la primera vez que se le pide
comportamiento nuevo (deflactar por rango de fechas, por rubro, con la
confianza marcada), y ahí sí aplica el patrón de F2: separar el caso de uso de
sus dos bordes para que ningún consumidor dependa de un detalle de HTTP ni de
SQL.

**Por qué el deflactor se elige por RUBRO y no es un único índice global
(D1).** Un producto de `tecnologia` (una GPU, una notebook) no sube o baja con
la canasta del IPC — sube o baja con el dólar, porque son productos
importados o dolarizados en origen. Deflactarlo por IPC contesta una pregunta
distinta de la que el usuario está haciendo. `DeflactorPorRubro.resolver` es
la política mínima que separa las dos preguntas: `tecnologia → USD_OFICIAL`,
todo el resto → `IPC`.

**Por qué el dólar OFICIAL del BNA y no un blend con el "blue" o el MEP
(D2).** Es la referencia que usan los importadores formales para poner
precio — el canal que este proyecto scrapea — y es una serie única, sin
opinión sobre qué brecha aplicar. Mezclar cotizaciones habría sido tomar
posición sobre la brecha cambiaria para responder una pregunta que no
necesita esa posición: si algo está más caro o más barato que antes.

**Por qué extrapolar y marcar en vez de abstenerse cuando falta el último
punto (D3).** INDEC publica el IPC con ~15 días de rezago: el mes corriente
nunca tiene un punto observado en el momento en que alguien mira el catálogo.
Abstenerse habría dejado toda señal de compra reciente vacía la mayor parte
del mes, que es justo cuando más se scrapea. `Extrapolador` proyecta desde el
último punto —variación mensual compuesta para IPC, carry-forward para USD,
porque un peg que fija el BCRA no deriva entre dos valores como sí deriva un
índice de precios— y el resultado viaja con `Confianza.EXTRAPOLADO` más los
días proyectados, nunca como si fuera una observación real. Por la misma
lógica, una serie vacía (sin red y sin nada persistido todavía) no inventa una
tasa: cae en `Deflactor.NEUTRO` (`factor=1.0`, `Confianza.SIN_DATOS`), la
posición neutral, no una constante hardcodeada.

**Por qué el factor se resuelve por FECHAS y no por cantidad de puntos.** El
bug que motivó el cambio: `SenalEnricher` calculaba
`mesesAtras = historial.size()/4`, y `SenalCalculator` trataba `size()-13`
como "hace 12 meses" — pero `precio_historico` registra CAMBIOS de precio, no
muestras mensuales (ver [`DATABASE.md`](./DATABASE.md)). Un producto con 4
cambios en una semana y uno con 1 cambio en 8 meses no pueden compartir esa
cuenta. Ahora `desde`/`hasta` son las fechas reales del primer y del último
punto que el cálculo mira, y `IndiceService.deflactor(indice, desde, hasta)`
resuelve `valorEn(hasta)/valorEn(desde)` contra esas fechas, nunca contra una
posición dentro de una lista.

**Por qué `SenalCalculator.compute(historial, factor)` no cambió de firma
(D4).** El contrato de las 6 señales de compra (`comprar_ahora`…`caro`) seguía
siendo correcto; lo que estaba mal era el factor que se le pasaba. Cambiar la
firma habría obligado a reescribir tests que ya verificaban el comportamiento
correcto de la clasificación en sí — separar "el cálculo de señales es
correcto" de "el factor que lo alimenta es correcto" deja cada cosa medible
por separado, antes y después del cambio.
