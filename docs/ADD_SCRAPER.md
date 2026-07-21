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
> (Tiendanube). Además de las plataformas genéricas de arriba, el proyecto ya
> tiene scrapers propios por sitio: **Maximus, FullH4rd, CompraGamer** (hardware/
> PC — el proyecto ya no es solo moda) y **Monkyforce** (gym). Esos son el "Caso 5"
> (Page/Scraper custom) ya resueltos; agregá el nombre a su name-set si aparece
> otra tienda de la misma plataforma.

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

### 3. `ScraperFactory.java`
```java
private static final Set<String> NOMBRE_NOMBRES = Set.of("nombre");

// En crear():
if (NOMBRE_NOMBRES.contains(n)) return new NombreScraper(config, display, site.url());
```

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
