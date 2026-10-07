# Project Context — fashion-scraper-new

**Initialized**: 2026-06-15  
**Project**: fashion-scraper-new  
**Topic Key**: sdd-init/fashion-scraper-new  
**Artifact Store**: openspec  
**Type**: SDD project context

---

## Executive Summary

Fashion Scraper Argentina is a headless e-commerce scraper (clothing/indumentaria focus) for the Argentine market. Single `.bat` installer for Windows downloads and compiles everything from scratch, then executes the fat JAR with embedded Tomcat web server on `localhost:3000`. Features a vanilla HTML/CSS/JS SPA dashboard with smart filtering, price history tracking, and ML-enriched product scoring (badges, price percentile ranking, real vs. cosmetic offer detection, TF-IDF clustering).

**Status**: Production-ready with known issues documented in `CLAUDE.md`.  
**Git**: Not a git repo yet (deliveries are `.zip` files with version suffix).

---

## Tech Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| **Backend** | Java 21 + Spring Boot 4.1.1 | Tomcat embedded on localhost:3000 |
| **Web Framework** | Spring Boot Web (Tomcat) | REST API + static file serving |
| **Web Scraping** | Playwright 1.44 | Headless browser; Page Object Model pattern |
| **Database** | SQLite 3.45.3 | `scraper.db` alongside JAR; 4 main tables |
| **ML Pipeline** | Python 3.11 (embedded) | Subprocess from Java; TF-IDF clustering, price ranking |
| **Frontend** | HTML/CSS/JS vanilla | Single Page App (SPA); no build step |
| **Build System** | Maven 3.x + Spring Boot Maven Plugin | Fat JAR generation; parent = spring-boot-starter-parent 4.1.1 |
| **Dependency Management** | Maven | Key deps: playwright, sqlite-jdbc, jackson-databind, opencsv |

---

## Project Structure

```
fashion-scraper-new/
├── CLAUDE.md                          # Session context (always read first)
├── SKILL.md                           # Documentation index
├── INSTALAR_Y_CORRER.bat              # One-click Windows installer + runner
├── docs/
│   ├── ARCHITECTURE.md                # Design decisions & rationale
│   ├── ADD_SCRAPER.md                 # How to add new site (4 files to touch)
│   ├── ML_PIPELINE.md                 # ML enrichment extension points
│   └── API_REFERENCE.md               # All REST endpoints
├── scraper/ (Maven project)
│   ├── pom.xml
│   └── src/main/
│       ├── java/ar/scraper/
│       │   ├── App.java               # Spring Boot entry point
│       │   ├── config/ScraperConfig   # config.properties loader
│       │   ├── model/                 # Product, ScrapeResult records
│       │   ├── pages/                 # Page Object Model (BasePage, ShopifyPage, etc.)
│       │   ├── scrapers/              # ScraperFactory, *Scraper implementations
│       │   ├── aggregator/            # ResultAggregator, NormalizerService
│       │   ├── ml/                    # PythonRunner, MlEnricher
│       │   ├── db/                    # DatabaseService (SQLite)
│       │   └── web/                   # ScraperService, ApiController
│       └── resources/
│           ├── application.properties # Logging, port (3000)
│           ├── logback-spring.xml     # Colored console + rolling file
│           ├── config.properties      # Site config, min/max price thresholds
│           ├── ml/                    # ml_pipeline.py, ml_train.py
│           └── static/                # index.html + CSS/JS (SPA)
├── frontend/ (dependency only, built by npm in installer)
│   └── node_modules/ (temporary)
├── _tools/                            # Pre-built Java, Maven, Python, Node
│   ├── java/
│   ├── maven/
│   ├── python/
│   └── node/
├── .atl/                              # SDD artifacts & registry
│   ├── skill-registry.md              # Skill index
│   ├── sdd/                           # SDD changes (active + archive)
│   └── testing-capabilities.md
└── openspec/                          # NEW: OpenSpec artifact store
    ├── config.yaml
    ├── PROJECT_CONTEXT.md
    ├── TESTING_CAPABILITIES.md
    ├── specs/
    │   ├── README.md
    │   └── {domain}/spec.md (as created)
    └── changes/
        ├── {change-name}/
        │   ├── state.yaml
        │   ├── proposal.md
        │   ├── specs/
        │   ├── design.md
        │   ├── tasks.md
        │   ├── verify-report.md
        │   └── apply-progress.md
        └── archive/
            └── YYYY-MM-DD-{change-name}/
```

---

## Supported E-Commerce Platforms

| Site | Platform | URL | Status | Product Count | Notes |
|------|----------|-----|--------|---|---------|
| Freres | Shopify | freres.ar | ✅ | ~136 | API `/products.json` paginada |
| VCP | Shopify | vcp.com.ar | ✅ | ~878 | API paginada |
| Tussy | TiendaNube (JS heuristic) | tussy.com.ar | ✅ | ~48 | No auth; CSS/JS extraction |
| Bulks | TiendaNube (JS heuristic) | bulkblanks.com.ar | ✅ | ~48 | CSS/JS extraction |
| Sporting | VTEX Legacy | sporting.com.ar | ✅ | ~2400 | `/api/catalog_system/pub/products/search` or IO fallback |
| Vaypol | Custom Rails SSR | vaypol.com.ar | ✅ | ~600-1000 | `/productos/p/N` + JS slug extractor; fotos incompletas |
| City | Custom Rails SSR | somoscity.com.ar | ✅ | ~655 | Same Rails; fotos incompletas |
| Midway | TiendaNube (JS heuristic) | midway.com.ar | ⚠️ | ~12 | Only page 1 (nextPageUrl issue) |
| Batuk | TiendaNube (JS heuristic) | batuk.com.ar | ⚠️ | ~12 | Same company as Huoky; partial |
| Bullbenny | TiendaNube (JS heuristic) | bullbenny.com.ar | ⚠️ | ~12 | Page 1 only |
| Vans | TiendaNube (Grimoldi custom) | vans.com.ar | ❌ | 0 | Undocumented CDN; API unknown |
| DC Shoes | WooCommerce | dcshoesargentina.com | 🆕 | TBD | WooCommerce API; price parsing under test |
| Harvey Willys | TN (JS) | harveywillys.com.ar | 🚫 | 0 | Disabled (price > $300k) |

---

## Database Schema

**File**: `scraper.db` (SQLite, auto-created alongside JAR)

### `productos` (Main Catalog)

```sql
CREATE TABLE productos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    url TEXT UNIQUE NOT NULL,
    sitio TEXT NOT NULL,
    nombre TEXT,
    precio REAL,
    precio_original TEXT,
    imagen_url TEXT,
    categoria TEXT,
    genero TEXT,
    talles TEXT,              -- JSON array: ["S","M","L"]
    marca TEXT,
    ml_score_badge TEXT,      -- "precio_bajo", "precio_alto", "oferta_real", "tendencia", null
    ml_score_percentil REAL,
    activo INTEGER DEFAULT 1,
    touched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### `precio_historico` (Price History, max 90 days)

```sql
CREATE TABLE precio_historico (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_url TEXT NOT NULL,
    precio REAL,
    fecha DATE NOT NULL,
    FOREIGN KEY (product_url) REFERENCES productos(url)
);
```

### `ml_output` (Latest ML Pipeline Output)

```sql
CREATE TABLE ml_output (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    output_json TEXT,         -- Serialized JSON from ml_pipeline.py
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### `sitios_dinamicos` (User-Added Sites)

```sql
CREATE TABLE sitios_dinamicos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre TEXT UNIQUE NOT NULL,
    url TEXT NOT NULL,
    plataforma TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## REST API Endpoints

**Base URL**: `http://localhost:3000/api`

| Method | Endpoint | Params | Returns | Purpose |
|--------|----------|--------|---------|---------|
| GET | `/status` | — | `{status, timestamp}` | Scraping state (IDLE/RUNNING/DONE/ERROR) |
| POST | `/scrape` | `precioMin`, `precioMax`, `sitios` | `{status, jobId}` | Start async scraping |
| GET | `/data` | `page`, `size`, `q`, `marca`, `badge`, `genero`, `categoria`, `sitio`, `orden` | `{items, total, facets}` | Paginated products with filters |
| GET | `/facets` | — | `{talles, generos, categorias, marcas, badges}` | Filter options |
| GET | `/tendencias` | — | `{clusters, topProductos, badges}` | ML output (clustering, top products) |
| GET | `/historial?url=X` | `url` (encoded) | `[{fecha, precio}]` | Price history for a product URL |
| GET | `/sitios` | — | `[{nombre, url, plataforma, dinamico}]` | All configured + dynamic sites |
| POST | `/sitios` | JSON: `{nombre, url, plataforma}` | `{status}` | Add dynamic site |
| DELETE | `/sitios/{nombre}` | — | `{status}` | Remove dynamic site |
| PUT | `/config` | JSON: `{precioMinimo, precioMaximo}` | `{status}` | Update price thresholds |
| GET | `/csv` | — | CSV download | Full product export |

See `docs/API_REFERENCE.md` for detailed parameter descriptions and response schemas.

---

## ML Pipeline

**Script**: `scraper/src/main/resources/ml/ml_pipeline.py` (Python 3.11)

**Input**: `ml_productos.json` (normalized products + price history)

**Output**: `ml_output.json` with:
1. **Price Ranking**: Percentile of price within category+gender
2. **Real vs Cosmetic Offer Detection**: `oferta_real` badge if `original_price / actual_price >= 1.25` AND percentile <= 50
3. **TF-IDF Clustering**: Bigram-based clustering for "Tendencia" badges

**Executed by**: Java `PythonRunner` (subprocess spawned during aggregation phase)

**Extensible via**: `ML_PIPELINE.md` documentation

---

## Frontend (SPA)

**Framework**: Vanilla HTML/CSS/JS (no build step)

**Entry Point**: `scraper/src/main/resources/static/index.html`

**Screens**:
1. **Splash Panel**: Price range selector, site checkboxes, scrape button
2. **Dashboard**: Product grid with image, nombre, precio, sitio, marca
3. **Filters**: Marca, Gender, Categoría, Badge, Sitio, Ordenamiento
4. **Tendencias Panel**: ML-generated clusters, top products, badge frequency

**No dependencies**: Loads at runtime; changes apply immediately on refresh.

---

## Known Issues & Limitations

| Issue | Root Cause | Workaround | Status |
|-------|-----------|-----------|--------|
| Vaypol/City fotos incompletas | HTML `<img>` sometimes outside `<a>` tag; index-based fix partial | Manual image URL mapping | Pending definitive fix |
| TiendaNube stores return only page 1 | `nextPageUrl()` doesn't find pagination link in some themes | Fallback URL pattern implemented | Partially resolved |
| Vans (vans.com.ar) returns 0 products | Grimoldi custom platform with undocumented API | Need to reverse-engineer CDN endpoints | Blocked (investigation needed) |
| DC Shoes price parsing | WooCommerce format "ARS209 175" requires custom regex | Parsing implemented v25d | Partially resolved |
| Harvey Willys disabled | Most items price > $300k (outlier) | Can re-enable if user requests | Intentional |
| Badges repetidos en clusters | TF-IDF occasionally produces duplicate bigrams | Bigram deduplication added; minor dupes remain | Partially resolved |
| Oferta_real scoring inconsistent | `safe_price` parsing fails on certain formats | Case-by-case ML fixes | Pending |

---

## Build & Installation

### One-Command Installation (Windows)

```batch
INSTALAR_Y_CORRER.bat
```

Downloads/installs:
- JDK 21
- Maven 3.x
- Python 3.11 embeddable
- Node.js (for frontend deps, if needed)
- Compiles `scraper/pom.xml` → `scraper/target/fashion-scraper-1.0.0.jar`
- Launches JAR at `localhost:3000`

### Manual Build

```bash
mvn -f scraper/pom.xml clean package -DskipTests
java -jar scraper/target/fashion-scraper-1.0.0.jar
```

---

## Testing Capabilities

**Current Status**: Strict TDD Mode DISABLED

- **Reason**: No JUnit, Mockito, or test infrastructure in `pom.xml`
- **Can Enable**: Yes; see `openspec/TESTING_CAPABILITIES.md` for setup steps (requires `spring-boot-starter-test` + test directory)
- **Build always works**: `mvn -f scraper/pom.xml clean package -DskipTests`

---

## Artifact Store Mode

**Selected**: openspec  
**Purpose**: File-based, committable specs and artifacts  
**Location**: `openspec/` directory (all SDD changes, specs, designs, tasks)  
**Benefits**:
- Full git history and audit trail
- Shareable with team
- No session dependency (unlike engram)
- Integrates with PR review workflow

---

## Next Steps

1. **Immediate**: Review this document and `CLAUDE.md` to understand current state
2. **For planning changes**: Use `/sdd-new`, `/sdd-explore`, or `/sdd-continue` to start SDD workflow
3. **For adding scrapers**: Follow `docs/ADD_SCRAPER.md` (4 files to touch)
4. **For ML updates**: Reference `docs/ML_PIPELINE.md`
5. **For API changes**: Document in `docs/API_REFERENCE.md` before implementing
6. **For TDD**: Enable test infrastructure per `openspec/TESTING_CAPABILITIES.md`, then re-run `sdd-init`

---

## Project Contacts & Documentation

- **Issue Tracker**: `.atl/sdd/` directory (active changes + archive)
- **Docs**: `docs/` directory (ARCHITECTURE, ADD_SCRAPER, ML_PIPELINE, API_REFERENCE)
- **Session Context**: `CLAUDE.md` (read at every session start)
- **Skills**: `.atl/skill-registry.md` (available skills for SDD workflow)

---

**Archive Date**: 2026-06-15  
**Status**: Ready for SDD workflow  
**Recommendation**: Start with `/sdd-explore <topic>` or `/sdd-new <change>` to plan next improvement
