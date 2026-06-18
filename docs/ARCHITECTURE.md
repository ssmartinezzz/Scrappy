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
│  Scrapers    │  ResultAggregator        │  Scraping + merge
│  *Page.java  │  NormalizerService       │
├──────────────┴──────────────────────────┤
│         DatabaseService.java            │  SQLite persistence
├─────────────────────────────────────────┤
│   PythonRunner → ml_pipeline.py         │  ML subprocess
└─────────────────────────────────────────┘
```
