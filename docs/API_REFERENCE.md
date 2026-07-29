# API Reference

Base URL: `http://localhost:3000/api`

> The native CLI (`cli/`, `native-cli-installer` 2026-07-25) is a pure REST
> client of this API — it owns the lifecycle of the backend and the frontend
> `npm run preview` process but adds no new endpoints. `cli/core/rest.py` is
> the single source of truth for which endpoints it calls (status, scrape,
> `ml/entrenar`, `sitios` CRUD); `tests/cli/test_rest.py` asserts no other
> endpoint is ever invoked. Supersedes the retired `menu.ps1`/`menu.sh`
> (`interactive-cli-launcher`, PR #108), which carried the same contract.

## CORS e integración externa

Desde `decouple-services-postgres`, el backend es **API-only** (no sirve la SPA).
El frontend es un servicio propio que le habla por **CORS**:

- El backend acepta orígenes de la env var **`APP_CORS_ALLOWED_ORIGINS`**
  (allow-list separada por comas, sin default en el profile por defecto).
- El frontend usa **`VITE_API_BASE_URL`** como base de sus fetches (build-time).
- En Docker, el compose cablea las dos (ver `docs/DOCKER` / `docker.env.example`).
  Cualquier integración externa debe agregar su origen a `APP_CORS_ALLOWED_ORIGINS`.

## Índice de endpoints

Las secciones detalladas de abajo cubren el núcleo. El resto sigue las mismas
convenciones (params server-side, respuestas JSON). Lista completa por grupo:

| Grupo | Endpoints |
|-------|-----------|
| Scraping | `GET /status` · `POST /scrape?precioMin&precioMax&sitios&forceRetrain` |
| Catálogo | `GET /data` · `GET /facets` · `GET /csv` · `DELETE /data?url=` (soft-delete) |
| ML | `GET /tendencias` · `GET /historial?url=` · `POST /ml/aplicar` · `POST /ml/renormalizar` · `GET /ml/estado` · `POST /ml/entrenar` · `GET /ml/resultado` |
| Comparador | `GET /grupos` · `GET /buscar-externo` (MercadoLibre) |
| Financiación | CRUD `/financiacion/presets` · `GET /recomendacion?url=` · `GET /inflacion` (INDEC) |
| Outfits | `GET /outfits` · `GET /outfits/builder` · `GET /suplementos/builder` · `POST /outfits/feedback` · CRUD `/outfits/saved` |
| Para ti | `GET /recomendados` · `POST /recomendados/feedback` · `POST`/`DELETE /recomendados/dismiss-categoria` |
| Favoritos | `GET`/`POST`/`DELETE /favoritos` · `POST /favoritos/rescrape` |
| Picks/Marcas | `GET /mejores?rubro=` · `GET /marcas-browser` |
| Sitios/Config | `GET`/`POST`/`DELETE /sitios` · `PUT /config` |
| Cron | `GET`/`POST /cron` · `GET`/`PUT`/`DELETE /cron/{id}` · `GET /cron/{id}/executions` · `POST /cron/{id}/run-now` |
| DB | `GET /db/export` · `POST /db/import` (ambos **410 Gone** — usar `pg_dump`/`pg_restore` contra `DATABASE_URL`) · `DELETE /db/productos` · `DELETE /db/ml` |
| LLM Agent | `POST /agent/chat` · `POST /agent/apply` · `GET /agent/models` |

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
| `subCategoria` | string[] | - | Multi-select subcategoría |
| `rubro` | string | - | `moda` / `gym` / `suplementos` / `deportes` / `tecnologia` |
| `gymrat` | boolean | - | Solo productos tagueados gymrat |
| `pack` | boolean | - | Solo packs/combos (`cantidadUnidades > 1`) |
| `segment` | string | - | `budget` / `standard` / `premium` / `luxury` |
| `precioMin` / `precioMax` | double | - | Rango de precio |
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

## POST /ml/renormalizar

Re-aplica las reglas actuales de `NormalizerService` sobre el catálogo ya
persistido en la DB (sin re-scrapear). Síncrono — corre antes de cada
entrenamiento de imagen (ver "Pipeline ML" en `CLAUDE.md`).

**Response:**
```json
{
  "totalRevisados": 3034,
  "categoriaCambiada": 12,
  "marcaCambiada": 4,
  "escriturasIntentadas": 14,
  "escriturasAplicadas": 13,
  "escriturasFallidas": 1
}
```
`totalRevisados`/`categoriaCambiada`/`marcaCambiada` describen el diff
**intencional** detectado por `NormalizerService` (significado sin cambios).
`escrituras*` (agent-chat-finetune) son aditivos y describen el resultado
**real** del `UPDATE` en DB: `escriturasIntentadas` = productos con algún
campo cambiado, `escriturasAplicadas` = filas realmente actualizadas,
`escriturasFallidas` = 0 filas afectadas o excepción — un producto que falla
no aborta el resto del batch.

---

## GET /csv

Descarga CSV completo (sin filtrar) con BOM para Excel.

**Headers:** `Content-Disposition: attachment; filename=ofertas.csv`

**Columnas:** Sitio, Nombre, Precio, Precio Original, Categoria, Genero, Talles, URL, Imagen

---

## LLM Catalog Agent (llm-catalog-nlp)

Agente de chat con tool-use, provider-pluggable (env `LLM_PROVIDER`/`LLM_MODEL`/
`LLM_BASE_URL`/`LLM_API_KEY`, ver `.env.example` — todas opcionales, con
defaults locales para Ollama). El agente SOLO tiene herramientas de lectura
(`search_products`, `view_product`, `propose_reclassify`); `propose_reclassify`
NUNCA escribe — valida y devuelve un diff. El único endpoint que escribe es
`POST /agent/apply`, fuera del loop del agente y solo tras confirmación
explícita del usuario en la UI.

`POST /agent/chat` y `POST /agent/apply` están gateados por scraping (igual
que `DELETE /db/productos`): devuelven **409** mientras `GET /status` está
`RUNNING` (evita contención de VRAM entre el LLM local y el modelo visual
Marqo-FashionSigLIP). `GET /agent/models` NO está gateado (metadata de solo
lectura, no toca VRAM).

### POST /agent/chat

**Body:**
```json
{
  "messages": [
    { "role": "user", "text": "mostrame la zapatilla" },
    { "role": "assistant", "text": "Es una Zapatilla Running.",
      "trace": [{ "calls": [{ "name": "view_product", "arguments": {"url": "https://…"} }] }] },
    { "role": "user", "text": "¿y de qué marca es?" }
  ],
  "model": "qwen3:14b"
}
```
`model` es opcional — presente y disponible → se usa para ese request; ausente
→ default de `LLM_MODEL`; presente pero desconocido → `400` (nunca fallback
silencioso).

**`trace` (agent-chat-continuity).** Es la actividad de herramientas de un
turno `assistant` anterior — la lista de *steps* del loop, cada uno con las
llamadas que el modelo emitió en ese step. Devuelto por este mismo endpoint
(campo `trace` de la respuesta) y reenviado tal cual por el cliente en el
siguiente turno.

Contrato explícito: **el cliente manda solo lo que el modelo PIDIÓ (`name` +
`arguments`), nunca lo que el catálogo RESPONDIÓ.** El servidor re-ejecuta cada
llamada contra el snapshot vivo antes de contactar al proveedor, así que:

- ningún resultado de herramienta llega desde el browser (un `trace`
  manipulado no puede inyectarle un "dato del catálogo" al modelo);
- la evidencia replayada está **al día** — después de un `/agent/apply`
  confirmado, un `view_product` replayado devuelve la categoría NUEVA.

Reglas del parseo (todo se descarta por campo, nunca se rechaza el request):
`role` solo puede ser `user` o `assistant` (cualquier otro valor, incluidos
`system` y `tool`, degrada a `user`); `trace` se ignora en turnos `user`; un
nombre de herramienta desconocido se descarta antes de ejecutarse; máximo 8
steps × 6 llamadas por step de transporte, y el servicio aplica encima su
presupuesto `MAX_REPLAY_CALLS = 12`, quedándose con la **cola** más reciente de
la conversación.

Sin `trace`, el historial que vuelve al modelo son respuestas en prosa pelada
sin rastro de que alguna vez se usó una herramienta — el modelo imita ese
transcript, deja de llamar herramientas y el guard de grounding lo rechaza. Ese
era el bug de "funciona una vez y después dice que no puede".

**Responses:**
- `200` `{"assistantText": "...", "outcome": "complete|capability|ungrounded|exhausted", "proposals": [{"url","nombreProducto","categoriaActual","categoriaPropuesta","subCategoriaPropuesta","marcaPropuesta","generoPropuesto"}], "trace": [{"calls":[{"name","arguments"}]}]}`
  — `trace` solo viene poblado en `complete` (las demás outcomes no dejan
  mensaje durable en la conversación, así que no exportan traza)
- `400` — `messages` vacío/ausente, o `model` desconocido
- `409` — scraping en curso
- `502` — proveedor LLM caído (`codigo: proveedor_no_disponible`)

**Grounding (sigue siendo por turno).** El replay reconstruye el contexto pero
**no** otorga grounding: para que la prosa del modelo se entregue, el modelo
tiene que ejecutar una herramienta con resultado válido *en ese turno*. Si
responde sin herramientas, recibe **un** empujón correctivo pidiéndole que la
use y, si insiste, el turno se rechaza (`outcome: ungrounded`) y su texto se
descarta.

### POST /agent/apply

Confirma (fuera del loop del agente) una propuesta de reclasificación devuelta
por `/agent/chat`. Body tipado — acepta el mismo `ReclassifyProposal` que
`/agent/chat` devuelve y `frontend/src/api.js`'s `applyProposal` postea tal
cual (agent-chat-finetune; antes leía un shape de Map distinto y todo click
de confirmación devolvía `400`). Re-valida server-side en 3 pasos
independientes — el cliente nunca se asume validado, ni siquiera con un body
tipado:
1. `url`/`categoriaPropuesta` presentes.
2. `categoriaPropuesta` ∈ taxonomía canónica (`CategoryGroups.canonicalCategories()`).
3. **Staleness guard**: `categoriaActual` del body vs. la categoría real leída
   de la DB (`DatabaseService.obtenerProducto`, no el snapshot en memoria) —
   detecta que el producto cambió entre que se generó la propuesta y que se
   confirmó. Falla cerrado: una lectura vacía (no existe, o error de DB)
   cuenta como conflicto, nunca como "seguro escribir".

Persiste vía `DatabaseService.aplicarReclasificacionAuditada`: UPDATE +
INSERT de auditoría (tabla `agent_reclassify_audit`) en una sola transacción
— si el INSERT de auditoría falla, el UPDATE también se revierte (nunca una
reclasificación sin fila de auditoría, ni una fila de auditoría de algo que
no pasó).

**Body:**
```json
{
  "url": "...",
  "nombreProducto": "...",
  "categoriaActual": "Zapatilla Running",
  "categoriaPropuesta": "Buzo",
  "subCategoriaPropuesta": "...",
  "marcaPropuesta": "...",
  "generoPropuesto": "..."
}
```
(`subCategoriaPropuesta`/`marcaPropuesta`/`generoPropuesto` opcionales — si se
omiten o vienen en blanco se preservan los valores actuales del producto.
Claves desconocidas se ignoran — `@JsonIgnoreProperties(ignoreUnknown = true)`
— así una propuesta reintentada puede seguir cargando las claves de UI
`_applied`/`_mensaje` sin romper el binding.)

**Responses:**
- `200` `{"ok": true, "applied": 1, "mensaje": "..."}`
- `400` `{"ok": false, "mensaje": "..."}` — falta `url`/`categoriaPropuesta`
  (nombra solo lo que falta), categoría inválida, o la url no existe en el
  catálogo
- `422` `{"ok": false, "codigo": "conflicto_stale", "mensaje": "...", "actual": {"categoria", "marca", "genero", "subCategoria"}}`
  — staleness guard: el producto cambió desde que se generó la propuesta;
  `actual` trae los valores reales para que el cliente los muestre sin un
  segundo round-trip
- `500` `{"ok": false, "mensaje": "..."}` — el write falló (0 filas afectadas
  o excepción); nunca se reporta como aplicado
- `409` — scraping en curso

### GET /agent/models

Descubre dinámicamente los modelos disponibles del proveedor activo (ej. los
modelos pulleados en la instancia local de Ollama) — no es una lista
hardcodeada. NO gateado por scraping.

**Response:** `{"available": ["qwen3:14b", "llama3.1:8b"], "default": "qwen3:14b"}`
