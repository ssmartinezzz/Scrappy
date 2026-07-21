# API Reference

Base URL: `http://localhost:3000/api`

> `menu.ps1` (Windows) / `menu.sh` (POSIX) are pure REST clients of this API
> (interactive-cli-launcher) — they own the lifecycle of the backend and the
> frontend `npm run preview` process but add no new endpoints. Each script
> carries a `# API CONTRACT` header block mirroring the six endpoints below
> and an optional `--selftest` action that checks live responses against
> this document for drift.

---

## GET /status

Estado actual del scraper.

**Response:**
```json
{
  "status": "IDLE | RUNNING | DONE | ERROR",
  "mensaje": "Completado: 3034 productos",
  "tieneData": true,
  "total": 3034
}
```

---

## POST /scrape

Lanza scraping async. Retorna inmediatamente.

**Query params:**
| Param | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `precioMin` | double | config | Precio mínimo |
| `precioMax` | double | config | Precio máximo |
| `sitios` | string[] | todos | Nombres de sitios a scrapear |

**Ejemplo:** `POST /api/scrape?precioMin=0&precioMax=200000&sitios=Freres&sitios=Sporting`

**Response:** `{"iniciado": true, "mensaje": "Scraping iniciado"}`

---

## GET /data

Productos con filtros y paginación server-side.

**Query params:**
| Param | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `page` | int | 1 | Página |
| `size` | int | 24 | Productos por página |
| `q` | string | - | Búsqueda full-text en nombre |
| `sitio` | string | - | Filtrar por tienda (exacto) |
| `marca` | string | - | Filtrar por marca normalizada |
| `badge` | string | - | Filtrar por badge ML — pertenencia al set (`badges`), no igualdad exacta con el principal. Keys: `all_time_low`, `below_market`, `verified_deal`, `trending`, `price_dropping`, `above_market`, `fake_discount` |
| `genero` | string | - | `hombre` / `mujer` / `unisex` |
| `categoria` | string[] | - | Multi-select categoría |
| `talle` | string[] | - | Multi-select talle |
| `fit` | string | - | Atributo visual: fit de la prenda (ej. `oversize`, `slim`) |
| `estampado` | string | - | Atributo visual: estampado (ej. `liso`, `rayado`) |
| `escote` | string | - | Atributo visual: escote (ej. `redondo`, `en v`) |
| `colorDominante` | string | - | Atributo visual: color dominante de la foto |
| `orden` | string | `precio_asc` | `precio_asc` / `precio_desc` / `nombre` |

Los cuatro filtros de atributos visuales son single-select y provienen del índice
visual (embeddings de imagen); un producto sin backfill de embeddings no matchea.

**Response:**
```json
{
  "meta": {
    "moneda": "ARS",
    "precioMin": 0,
    "precioMax": 300000,
    "rangMin": 2999,
    "rangMax": 299999,
    "total": 1156,
    "pagina": 1,
    "pageSize": 24,
    "totalPaginas": 49,
    "facets": {
      "talles": {"S": 45, "M": 67},
      "generos": {"hombre": 120},
      "categorias": {"Zapatillas": 500},
      "marcas": {"Nike": 342, "Adidas": 280},
      "badges": {"below_market": 89, "verified_deal": 45},
      "fits": {"oversize": 120, "slim": 85},
      "estampados": {"liso": 900, "rayado": 40},
      "escotes": {"redondo": 300, "en v": 55},
      "colorDominantes": {"negro": 800, "blanco": 420}
    },
    "marcas": {"Freres": 136, "Sporting": 2444}
  },
  "productos": [
    {
      "sitio": "Sporting",
      "nombre": "Zapatillas Nike Vomero 17",
      "precio": 247999,
      "precioOrig": "$309.999",
      "descuento": true,
      "url": "https://...",
      "img": "https://...",
      "categoria": "Zapatillas",
      "genero": "hombre",
      "marca": "Nike",
      "talles": ["39","40","41","42","43"],
      "ml": {
        "badge": "verified_deal",
        "badges": ["verified_deal", "trending"],
        "scoreP": 28,
        "ofertaReal": true,
        "tendencia": "bajando",
        "pctil": 28
      }
    }
  ]
}
```

---

## GET /facets

Solo facets, sin productos (para cargar filtros rápido).

**Response:** igual al objeto `meta.facets` de `/data`. `badges` cuenta un producto
una vez POR CADA badge que tiene en su set (multi-badge) — la suma de todos los
conteos puede superar el total de productos.

---

## GET /tendencias

Output del pipeline ML para el panel de Tendencias.

**Response:**
```json
{
  "categoriaStats": [
    {"categoria": "Zapatillas", "count": 1173, "avgPrecio": 129837}
  ],
  "topProductos": [
    {"url": "...", "nombre": "...", "precio": 2999, "img": "...", "sitio": "...", "marca": "..."}
  ],
  "trendingClusters": [
    {"cluster": 5, "label": "Remera Nike", "size": 183}
  ],
  "totalProductos": 3034,
  "fecha": "2026-05-29"
}
```

---

## GET /historial?url=URL

Historial de precios de un producto.

**Query params:** `url` (required) — URL canónica del producto

**Response:**
```json
[
  {"fecha": "2026-05-20", "precio": 15990},
  {"fecha": "2026-05-28", "precio": 14990}
]
```

---

## GET /sitios

Lista de sitios configurados + dinámicos.

**Response:**
```json
{
  "base": [{"nombre": "Freres", "url": "https://..."}],
  "extras": [{"nombre": "MiMarca", "url": "https://...", "plataforma": "shopify"}],
  "precioMinimo": 0,
  "precioMaximo": 300000,
  "moneda": "ARS"
}
```

---

## POST /sitios

Agrega sitio dinámico (persiste en DB).

**Body:** `{"nombre": "MiMarca", "url": "https://...", "plataforma": "tiendanube"}`

---

## DELETE /sitios/{nombre}

Elimina sitio dinámico de DB y memoria.

---

## PUT /config

Actualiza configuración en runtime.

**Body:** `{"precioMinimo": 0, "precioMaximo": 200000}`

---

## GET /ml/estado

Estado de los modelos ML y del índice visual. Pensado para polling desde el panel.

**Response:**
```json
{
  "hasTextModel": true,
  "hasImageModel": false,
  "textMeta": {"...": "contenido de _models/text_meta.json si existe"},
  "training": {
    "running": true,
    "phase": "training | embedding | idle | timeout | error",
    "pct": 40,
    "msg": "...",
    "startedAt": "2026-07-12T16:00:00Z"
  },
  "embeddingsCount": 2100,
  "totalProductos": 3034,
  "coveragePct": 69.2
}
```

`embeddingsCount` / `totalProductos` / `coveragePct` reportan la cobertura del
índice visual (tabla `image_embeddings` vs catálogo en memoria). Son campos
aditivos: clientes anteriores pueden ignorarlos.

---

## POST /ml/entrenar

Lanza en background (un solo thread, secuencial): re-entrenamiento del
clasificador de texto y luego backfill del índice visual (embeddings).
Retorna inmediatamente.

**Query params:**
| Param | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `images` | boolean | `false` | Incluye entrenamiento del modelo de imagen |
| `epochs` | int | 8 | Epochs del modelo de imagen |

**Responses:**
- `200` `{"status": "started"}` — secuencia iniciada
- `400` `{"error": "Entrenamiento ya en curso"}` — pre-check: ya hay un entrenamiento corriendo
- `409` `{"error": "Entrenamiento ya en curso"}` — carrera entre dos POST simultáneos: este request perdió el CAS y NO inició nada

Progreso via polling de `GET /ml/estado` (`training.phase` pasa por
`training` → `embedding` → `idle`/`error`).

---

## GET /ml/resultado

Snapshot corto del estado de entrenamiento: `{running, phase, pct, msg, done}`.

---

## GET /csv

Descarga CSV completo (sin filtrar) con BOM para Excel.

**Headers:** `Content-Disposition: attachment; filename=ofertas.csv`

**Columnas:** Sitio, Nombre, Precio, Precio Original, Categoria, Genero, Talles, URL, Imagen
