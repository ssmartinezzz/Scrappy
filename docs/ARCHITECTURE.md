# Arquitectura del Fashion Scraper

## Decisiones principales y su justificación

---

### ¿Por qué un fat JAR con todo incluido?

**Decisión**: Spring Boot fat JAR con Tomcat embebido, backend **API-only** (sin servir la SPA).

**Razón**: es una herramienta local mono-usuario en Windows, no un servicio desplegado. Para ese escenario, un `.bat` que descarga Java + Postgres portable + Node + Python y ejecuta `java -jar scraper.jar` es la UX más simple posible: cero-setup, sin infraestructura previa. No hay Dockerfile, no hay instalaciones previas, no hay conflictos de versiones.

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

### ¿Por qué el aggregator está modularizado en collaborators de responsabilidad única?

**Decisión**: `ar.scraper.aggregator` se organiza como orquestadores delgados (`NormalizerService`, `GroupingService`, `ResultAggregator`) que secuencian collaborators de responsabilidad única, agrupados en subpaquetes por tema (`normalize/`, `grouping/`, `text/`), más `FacetCalculator` como utility estática en la raíz del paquete.

**Razón**: antes de esta modularización, `NormalizerService` (categoría, talles, género, marca, pack/combo, subcategoría, rubro, gymrat) y `ResultAggregator` (validación, dedup, pipeline ML, persistencia, facets) eran clases monolíticas: cada regla de negocio nueva crecía el mismo archivo y era imposible testear una regla sin arrastrar todas las demás. La modularización es **behavior-preserving** — cero cambios observables end-to-end; el historial slice por slice, con los riesgos mitigados y las decisiones "diseño vs. código real", vive en [`docs/migration/aggregator-solid-modularization.md`](./migration/aggregator-solid-modularization.md).

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
