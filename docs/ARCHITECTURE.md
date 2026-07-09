# Arquitectura del Fashion Scraper

## Decisiones principales y su justificación

---

### ¿Por qué un fat JAR con todo incluido?

**Decisión**: Spring Boot fat JAR con Tomcat embebido.

**Razón**: el usuario objetivo es Santiago en Windows, sin conocimientos de deployment. Un `.bat` que descarga Java y ejecuta `java -jar scraper.jar` es la UX más simple posible. No hay Dockerfile, no hay instalaciones previas, no hay conflictos de versiones.

---

### ¿Por qué SQLite y no H2?

**Decisión**: `sqlite-jdbc` en lugar de H2 embebida de Spring Boot.

**Razón**: H2 por defecto es in-memory (se pierde al reiniciar) o requiere configuración de archivo. SQLite genera un único archivo `scraper.db` en el directorio de trabajo, visible y transferible. El usuario puede abrirlo con DB Browser for SQLite si quiere inspeccionar datos. No requiere Spring Data JPA ni configuración de datasource.

**Trade-off**: no hay migraciones automáticas. Si el schema cambia, hay que manejar ALTER TABLE manualmente o borrar `scraper.db`.

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

### ¿Por qué el frontend es HTML/JS vanilla?

**Decisión**: SPA vanilla sin React/Vue/Angular.

**Razón**: el frontend es servido como static resource desde Spring Boot. No hay build step, no hay `npm install`, no hay webpack. El usuario descarga el JAR y funciona. Un framework JavaScript agregaría complejidad de build sin beneficio real dado el tamaño del proyecto.

**Trade-off**: el código JS en `index.html` es más verbose. Aceptable para una app de uso personal.

---

## Diagrama de capas

```
┌─────────────────────────────────────────┐
│           index.html (SPA)              │  Frontend vanilla
└───────────────────┬─────────────────────┘
                    │ REST API
┌───────────────────▼─────────────────────┐
│         ApiController.java              │  Spring MVC
├─────────────────────────────────────────┤
│         ScraperService.java             │  Orquestación async
├──────────────┬──────────────────────────┤
│  Scrapers    │  ResultAggregator        │  Scraping + merge +
│  *Page.java  │  (aggregator.normalize/  │  normalizar + agrupar
│              │   .grouping/.text)       │
├──────────────┴──────────────────────────┤
│         DatabaseService.java            │  SQLite persistence
├─────────────────────────────────────────┤
│   PythonRunner → ml_pipeline.py         │  ML subprocess
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
