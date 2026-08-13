# Cómo agregar un sitio nuevo

## Flujo de decisión

Antes de codear, determinar la plataforma del sitio:

```
¿Tiene /products.json?          → Shopify
¿Tiene /api/catalog_system/pub? → VTEX Legacy
¿Es WooCommerce (wp-json/wc)?   → WooCommerce  {dcshoes}
¿URL termina en /productos/p/N? → Vaypol/City platform
¿Es tiendanube.com?             → Tiendanube (JS heurístico)
¿Otro?                          → Necesita Page/Scraper custom
```

> **Detección real** (`ScraperFactory.crear`, en orden): WooCommerce → Maximus →
> FullH4rd → CompraGamer → Vaypol → VTEX → Shopify → Monkyforce → default
> (Tiendanube). Desde `V20` esto **no** se resuelve con name-sets en Java: cada
> `if` de `crear()` lee `siteRegistry.plataforma(sitioKey)`, y esa columna sale
> de la tabla `sitio` (sembrada por migración). Los 8 `Set.of(...)` que existían
> antes fueron **borrados**, no reemplazados por otra copia — agregar un sitio a
> una plataforma ya soportada es una fila de seed, no una edición de código.
> Además de las plataformas genéricas de arriba, el proyecto ya tiene scrapers
> propios por sitio/plataforma: **Maximus, FullH4rd, CompraGamer** (hardware/PC
> — el proyecto ya no es solo moda), **Monkyforce** (gym), **Qloud** (Rockethard)
> y **osCommerce** (Venex). Esos son el "Caso 5" (Page/Scraper custom) ya
> resueltos; agregá una fila de seed con el `plataforma` correspondiente si
> aparece otra tienda sobre la misma plataforma — ver el patrón abajo.

**Cómo detectar la plataforma**:
1. Ver el HTML fuente: buscar `meta-shopify`, `cdn/shop/`, `LS.store`, `vtex`
2. Probar `https://DOMINIO/products.json` — si devuelve JSON con `"products":[]` → Shopify
3. Ver URL pattern de productos en la tienda

---

## Caso 1: Sitio Shopify

Solo tocar **2 archivos**:

### `config.properties`
```properties
sitio.NOMBRE.url=https://DOMINIO.com
sitio.NOMBRE.activo=true
```

### `ScraperFactory.java`
```java
private static final Set<String> SHOPIFY_NOMBRES = Set.of("freres", "vcp", "NOMBRE");
```

Listo. `ShopifyPage` llamará `/products.json?limit=250&page=N` automáticamente.

---

## Caso 2: Sitio Tiendanube

Solo tocar **2 archivos**:

### `config.properties`
```properties
sitio.NOMBRE.url=https://DOMINIO.com.ar/productos/
sitio.NOMBRE.activo=true
```

### `ScraperFactory.java`
No es necesario cambiar nada — todo lo que no sea Shopify/VTEX/Vaypol va a `TiendanubeScraper`.

**Nota**: si el sitio usa `/coleccion/`, `/indumentaria/` u otras rutas en lugar de `/productos/`, agregarlo al array `paths` en `TiendanubePage.buildExtractorJs()`.

---

## Caso 3: Sitio VTEX

### `config.properties`
```properties
sitio.NOMBRE.url=https://DOMINIO.com.ar
sitio.NOMBRE.activo=true
```

### `ScraperFactory.java`
```java
private static final Set<String> VTEX_NOMBRES = Set.of("sporting", "NOMBRE");
```

`VtexPage` intenta primero la API Legacy (`/api/catalog_system/pub/products/search`), y si devuelve vacío, prueba la API IO (`/api/io/_v/api/intelligent-search/product_search/trade-policy/1`).

---

## Caso 4: Plataforma Vaypol/City (Rails SSR)

### `config.properties`
```properties
sitio.NOMBRE.url=https://DOMINIO.com.ar
sitio.NOMBRE.activo=true
```

### `ScraperFactory.java`
```java
private static final Set<String> VAYPOL_NOMBRES = Set.of("vaypol", "city", "NOMBRE");
```

`VaypolPage` busca links con href que terminen en `/-{4-6 dígitos}` (el slug de producto de esta plataforma). Si el nuevo sitio usa un patrón diferente, ajustar el regex en `buildExtractorJs()`.

---

## Caso 5: Plataforma completamente custom (nuevo Page)

Cuando ninguno de los anteriores aplica:

### 1. Crear `src/.../pages/NombrePage.java`
```java
public class NombrePage extends BasePage {
    public NombrePage(Page page, int timeoutMs, String sitio, String baseUrl,
                      double precioMin, double precioMax) { ... }

    public List<Product> scrapeAll() {
        // Lógica específica: navegar, extraer, paginar
    }
}
```

**Métodos útiles en BasePage**:
- `navigateTo(url)` — navega y espera `domcontentloaded`
- `parsePrecio(text)` — maneja formatos argentinos ($12.500, $12.500,00)
- `safeText(locator)` — text sin NPE
- `absoluteUrl(href, base)` — resuelve URLs relativas
- `scrollToBottom()` — hace scroll para activar lazy loading

### 2. Crear `src/.../scrapers/NombreScraper.java`
```java
public class NombreScraper extends BaseScraper {
    public NombreScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }
    @Override
    protected List<Product> scrape(Page page) {
        return new NombrePage(page, config.getTimeoutMs(), sitio, baseUrl,
                config.getPrecioMinimo(), config.getPrecioMaximo()).scrapeAll();
    }
}
```

### 3. Dar de alta la plataforma: migración + `ScraperFactory.java` + `config.properties`

Desde `V20`, `ScraperFactory.crear` no mantiene un name-set por sitio: lee
`sitio.plataforma` a través de `SiteRegistry`. Un sitio nuevo sobre una
plataforma nueva necesita las tres piezas en el **mismo commit**
(`site-platform-vocabulary`/Config-and-Seed-Move-Together — si una se olvida,
`SitioSeedSyncTest` o `PlatformVocabularySyncTest` lo marcan rojo):

**a) Migración nueva** (`V{N}__platform_vocabulary_nombre.sql` — nunca edites
una migración ya aplicada, `sitio_plataforma_check` es CHECK, no tabla, por el
mismo criterio de `V6`):
```sql
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN (..., 'nombre_plataforma'));

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
VALUES ('Nombre', 'nombre', 'nombre_plataforma', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;
```

**b) `ScraperFactory.java`** — un `if` más, mismo estilo que los existentes,
**nunca** un `Set.of(...)` (`CODE-6`, `site-platform-vocabulary`/ScraperFactory
Routes Exclusively Off `sitio.plataforma`):
```java
if ("nombre_plataforma".equals(plataforma))
    return new NombreScraper(config, display, site.url());
```

**c) `PLATAFORMAS_VALIDAS`** en `SitiosRepository.java` — segunda copia
deliberada (valida sitios agregados desde el dashboard, sin tabla `sitio`
todavía). Se amplía junto con el CHECK; `PlatformVocabularySyncTest`
(classpath, sin DB) falla si las dos se desincronizan.

### 4. `config.properties`
```properties
sitio.nombre.url=https://DOMINIO.com
sitio.nombre.activo=true
```

---

## Checklist al agregar cualquier sitio

- [ ] Verificar que la URL responde (no da 404 ni timeout)
- [ ] Confirmar el rango de precios (sitios premium pueden estar sobre $300k)
- [ ] Primer run: revisar log `[SITIO] NOMBRE → X productos` con fotos
- [ ] Si fotos = 0/X: revisar extractor de imágenes
- [ ] Si productos = 0: activar modo `headless=false` en `config.properties` para ver el browser

## Activar modo debug visual

En `config.properties`:
```properties
headless=false
```

Esto abre el browser visible durante el scraping. Útil para ver popups, captchas o estructuras DOM inusuales.
