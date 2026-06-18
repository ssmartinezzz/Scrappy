# Fashion Scraper Argentina — Contexto del Proyecto

> Este archivo existe para que Claude pueda leer el estado completo del proyecto en una nueva sesión sin necesidad de que el usuario lo explique desde cero. Leelo siempre antes de sugerir cambios.

---

## Qué es

Scraper headless de ropa/indumentaria argentina con dashboard web inteligente. Un solo `.bat` instala todo y ejecuta desde cero en Windows. El usuario configura parámetros de búsqueda, lanza el scraping, y navega los resultados con filtros + panel de tendencias ML.

---

## Stack técnico

| Capa | Tecnología |
|------|-----------|
| Backend/Scraper | Java 21 + Spring Boot 3.2 + Playwright 1.44 |
| Servidor web | Tomcat embebido en localhost:3000 |
| Frontend | React 18 + Vite 5 (SPA en `frontend/`, buildea a `scraper/src/main/resources/static/`) |
| Base de datos | SQLite (archivo `scraper.db` junto al .jar) |
| ML Pipeline | Python 3.11 embeddable (subprocess desde Java) |
| Build | Maven + Spring Boot Maven Plugin (fat JAR) |

---

## Estructura de archivos clave

```
fashion-scraper-new/
├── CLAUDE.md                          ← Este archivo
├── SKILL.md                           ← Índice de documentación técnica
├── INSTALAR_Y_CORRER.bat              ← Instala Java + Maven + Python + compila + ejecuta
├── docs/
│   ├── ARCHITECTURE.md                ← Decisiones de arquitectura
│   ├── ADD_SCRAPER.md                 ← Cómo agregar un sitio nuevo
│   ├── ML_PIPELINE.md                 ← Cómo extender el pipeline ML
│   └── API_REFERENCE.md               ← Endpoints REST completos
└── scraper/
    ├── pom.xml                        ← sqlite-jdbc, playwright, opencsv, jackson
    └── src/main/
        ├── java/ar/scraper/
        │   ├── App.java               ← Entry point Spring Boot
        │   ├── config/ScraperConfig   ← Lee config.properties, precio min/max
        │   ├── model/
        │   │   ├── Product.java       ← Record con sitio, nombre, precio, ml, marca, talles
        │   │   └── ScrapeResult.java  ← Record: sitio, productos, error, duracionMs
        │   ├── pages/                 ← Page Object Model
        │   │   ├── BasePage.java      ← navigateTo, parsePrecio, absoluteUrl
        │   │   ├── ShopifyPage.java   ← API /products.json paginada
        │   │   ├── TiendanubePage.java← API TN (requiere auth, cae a JS heurístico)
        │   │   ├── VtexPage.java      ← VTEX Legacy API + IO Intelligent Search fallback
        │   │   └── VaypolPage.java    ← Plataforma custom Rails SSR (Vaypol + City)
        │   ├── scrapers/
        │   │   ├── BaseScraper.java   ← Lanza Playwright, bloquea recursos pesados
        │   │   ├── ScraperFactory.java← Detecta plataforma por nombre/URL
        │   │   └── *Scraper.java      ← Shopify / TiendaNube / Vtex / Vaypol
        │   ├── aggregator/
        │   │   ├── ResultAggregator.java ← Merge, dedup, normalizar, ML, upsert DB
        │   │   └── NormalizerService.java← Categorías canónicas, talles, géneros, marcas
        │   ├── ml/
        │   │   ├── PythonRunner.java  ← Subprocess Python, extrae script del JAR
        │   │   └── MlEnricher.java    ← Aplica scores Python → Product.MlScore
        │   ├── db/
        │   │   └── DatabaseService.java← SQLite: upsert, historial, ML output, sitios
        │   └── web/
        │       ├── ScraperService.java ← Orquesta scraping async, carga DB al arrancar
        │       └── ApiController.java  ← REST endpoints
        └── resources/
            ├── application.properties ← port=3000, logging
            ├── logback-spring.xml     ← Colores en consola, rolling file
            ├── config.properties      ← Sitios, precios, threads
            ├── ml/ml_pipeline.py      ← Pipeline Python (se extrae al dir del .jar)
            └── static/                ← Build output de Vite (NO editar directamente)
```

---

## Sitios configurados

| Sitio | Plataforma | URL Config | Estado |
|-------|-----------|-----------|--------|
| Freres | Shopify | freres.ar | ✅ ~136 productos |
| VCP | Shopify | vcp.com.ar | ✅ ~878 productos |
| Midway | TN (JS heurístico) | midway.com.ar | ⚠️ ~12 (solo p1) |
| Batuk | TN (JS heurístico) | batuk.com.ar | ⚠️ ~12 (Batuk+Huoky misma empresa) |
| Tussy | TN (JS heurístico) | tussy.com.ar | ✅ ~48 |
| Bulks | TN (JS heurístico) | bulkblanks.com.ar | ✅ ~48 |
| Bullbenny | TN (JS heurístico) | bullbenny.com.ar | ⚠️ ~12 |
| Vans | TN (JS heurístico) | vans.com.ar | ⚠️ 0 (plataforma Grimoldi custom — pendiente) |
| DC Shoes | WooCommerce | dcshoesargentina.com | 🆕 nuevo |
| Sporting | VTEX Legacy | sporting.com.ar | ✅ ~2400 productos |
| Vaypol | Custom Rails SSR | vaypol.com.ar | ✅ ~600-1000 (fotos incompletas) |
| City | Custom Rails SSR | somoscity.com.ar | ✅ ~655 productos (fotos incompletas) |

---

## Plataformas y cómo detectarlas

```java
// ScraperFactory.java
SHOPIFY  → nombres: {"freres", "vcp"} o url contiene "myshopify.com"
VTEX     → nombres: {"sporting"} o url contiene "vtexcommercestable"
VAYPOL   → nombres: {"vaypol", "city"} — misma plataforma Rails
TN       → todo lo demás → TiendanubeScraper (JS heurístico)
```

---

## API REST

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| GET | /api/status | Estado: IDLE/RUNNING/DONE/ERROR |
| POST | /api/scrape?precioMin=N&precioMax=N&sitios=X | Lanza scraping async |
| GET | /api/data?page=1&size=24&q=X&marca=X&badge=X&genero=X&categoria=X&sitio=X&orden=X | Productos paginados con filtros |
| GET | /api/facets | Facets: talles, géneros, categorías, marcas, badges |
| GET | /api/tendencias | Output ML: categorías, clusters, top productos |
| GET | /api/historial?url=X | Historial de precios de un producto |
| GET | /api/sitios | Lista sitios config + dinámicos |
| POST | /api/sitios | Agrega sitio dinámico {nombre, url, plataforma} |
| DELETE | /api/sitios/{nombre} | Elimina sitio dinámico |
| PUT | /api/config | Actualiza {precioMinimo, precioMaximo} |
| GET | /api/csv | Descarga CSV completo |

---

## Base de datos SQLite (`scraper.db`)

```sql
productos          -- Último estado del catálogo (upsert por URL)
precio_historico   -- Cambios de precio por fecha (max 90 días)
ml_output          -- Último output del pipeline Python (JSON)
sitios_dinamicos   -- Sitios agregados desde el dashboard
```

**Lógica de upsert:**
- URL nueva → INSERT + registro en historial
- Precio igual → solo actualiza `touched_at`
- Precio cambió → UPDATE + INSERT en historial
- No apareció en run → soft-delete (`activo=0`)

---

## Pipeline ML (`ml_pipeline.py`)

**Input:** `ml_productos.json` (productos normalizados con marca, precio, categoría)
**Output:** `ml_output.json` con scores por URL + tendencias globales

**Modelos (sin dependencias externas):**
1. **Percentil de precio** por categoría+género → badge `precio_bajo` / `precio_alto`
2. **Oferta real vs cosmética**: ratio orig/actual ≥ 1.25 Y percentil ≤ 50 → `oferta_real`
3. **TF-IDF clustering** con bigrams → badges `tendencia`, clusters para el panel

**Historial:** `precio_historico.json` (parallelo al JSON de la DB, para el script Python)

---

## Model `Product`

```java
record Product(
    String sitio,           // "Sporting", "Vaypol", etc.
    String nombre,          // "Zapatillas Nike Vomero 17"
    double precio,          // 247999.0
    String precioOriginal,  // "$309.999" o null
    String url,             // URL canónica del producto
    String imagenUrl,       // URL CDN de la imagen
    String categoria,       // Normalizado: "Zapatillas", "Remeras"...
    String genero,          // "hombre" | "mujer" | "unisex" | ""
    List<String> talles,    // ["S","M","L","XL"] o []
    MlScore ml,             // scoreP, badge, ofertaReal, tendencia
    String marca            // Extraída del nombre: "Nike", "Adidas"...
)
```

---

## Flujo completo de un run

```
1. Usuario configura (rango precio, sitios) en el splash panel
2. POST /api/scrape → ScraperService.iniciarScraping()
3. Para cada sitio: ScraperFactory.crear() → BaseScraper.ejecutar()
   - Shopify: /products.json paginada
   - VTEX: /api/catalog_system/pub/products/search → fallback IO
   - Vaypol: /productos/p/N con JS extractor de links slug-{id}
   - TN: API REST (falla sin auth) → JS heurístico con data-price
4. ResultAggregator.agregar():
   a. Dedup por sitio+nombre
   b. NormalizerService.normalizar() → categoría/género/talle/marca canónicos
   c. PythonRunner.ejecutar() → ml_pipeline.py → scores
   d. MlEnricher.enriquecer() → Product.MlScore populated
   e. DatabaseService.upsertProductos() → merge inteligente en SQLite
   f. DatabaseService.guardarMlOutput()
5. Frontend polling /api/status → DONE
6. Dashboard carga /api/data con filtros server-side
```

---

## Problemas conocidos / pendientes

| Problema | Causa | Estado |
|---------|-------|--------|
| Vaypol/City fotos incompletas | `<img>` a veces fuera del `<a>`, index-based fix parcial | Pendiente fix definitivo |
| TN stores solo 12 productos | `nextPageUrl()` no encuentra link de siguiente página en ciertos temas | Parcialmente resuelto (URL fallback) |
| Vans 0 productos | Plataforma Grimoldi (custom, CDN mmgrim2) | Pendiente investigación API |
| DC Shoes (nuevo) | WooCommerce, precios "ARS209 175" | Implementado v25d |
| Harvey Willys 0 productos | Precio > $300k | Desactivado por diseño |
| Clasificación oferta_real inconsistente | `safe_price` puede parsear mal ciertos formatos | Pendiente fix ML |
| Panel Tendencias badges repetidos en clusters | Bigrams mejoraron pero aún hay duplicados | Parcialmente resuelto |

---

## Cómo continuar en nueva sesión

1. Leé este archivo (`CLAUDE.md`) completo
2. Revisá `SKILL.md` para el índice de documentación técnica
3. Pedile al usuario el último log (`scraper.log`) si hay problemas
4. Toda la lógica de plataformas está en `docs/ADD_SCRAPER.md`
5. Para cambios al ML: `docs/ML_PIPELINE.md`

**El código fuente completo está siempre en el zip más reciente entregado al usuario.**
