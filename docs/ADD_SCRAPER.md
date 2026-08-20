# Cómo agregar un sitio nuevo

## Flujo de decisión

Antes de codear, determinar la plataforma del sitio:

```
¿Tiene /products.json?          → Shopify
¿Tiene /api/catalog_system/pub? → VTEX Legacy
¿Es WooCommerce (wp-json/wc)?   → WooCommerce  {dcshoes}
¿URL termina en /productos/p/N? → Vaypol/City platform
¿Es tiendanube.com?             → Tiendanube (JS heurístico)
¿Es un SPA/SSR propio sobre     → HEADLESS: ver abajo, NO es la plataforma
  otra plataforma?                 de atrás  {inpro}
¿Otro?                          → Necesita Page/Scraper custom
```

> ⚠️ **El paso del headless es nuevo y es el que más fácil se saltea.** Antes de
> concluir "es Tiendanube/Shopify" por lo que hay en el payload, mirá qué sirve
> la **vidriera**. Ver [Caso 6](#caso-6--headless-la-plataforma-de-atrás-no-es-la-plataforma) más abajo.

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

## Lo que toda alta comparte: config + fila de seed

Agregar un sitio sobre una plataforma **ya soportada no es una edición de
código**. Desde `V20` la plataforma sale de la columna `sitio.plataforma`, leída
por `SiteRegistry`; los 8 `Set.of(...)` de `ScraperFactory` fueron **borrados**
(`CODE-6`). Son tres piezas, en el **mismo commit**:

### 1. `config.properties`

```properties
sitio.NOMBRE.url=https://DOMINIO.com
sitio.NOMBRE.activo=true
```

### 2. Una migración nueva que siembre la fila en `sitio`

Nunca edites una migración ya aplicada: son byte-frozen y Flyway valida
checksums. Va una `V{N}` nueva. Si la plataforma **ya existe** en el
vocabulario, es sólo el `INSERT` — el `CHECK` no se toca:

```sql
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
VALUES ('Nombre', 'nombre', 'shopify', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;
```

`origen` tiene que ser **`'config'`**: es lo que el test de abajo busca.
`rubro_forzado` va con el mismo valor que `sitio.NOMBRE.rubro` en
`config.properties`, o `NULL` si no lo declarás.

### 3. Agregar esa migración a `SitioSeedSyncTest`

```java
private static final String V25 = "/db/migration/V25__seed_nombre.sql";
...
for (String migration : List.of(V18, V24, V25)) {
```

El test arma el set de sitios sembrados desde una lista **hardcodeada** de
migraciones y exige que todo sitio activo de `config.properties` esté ahí con
`origen='config'`. Sembrar en una migración nueva sin sumarla a esa lista deja
el test rojo aunque la fila exista.

> ⚠️ **Por qué la fila de seed no es opcional, aunque el scraper "ande" sin
> ella.** `SiteRegistry.porKey` se abstiene hacia `"tiendanube"` cuando no
> encuentra la clave, así que un sitio sin sembrar **igual scrapea** — como
> Tiendanube, sea o no Tiendanube. Ese es exactamente el bug de `forever`:
> estaba en `config.properties`, no estaba en el name-set, caía a Tiendanube y
> devolvía 0 productos, y parecía un scraper roto en vez de un sitio sin
> registrar. `SitioSeedSyncTest` existe para atajar esa forma exacta.

---

## Caso 1: Sitio Shopify

Seguí las tres piezas de arriba con `plataforma = 'shopify'`. `ShopifyPage`
llama `/products.json?limit=250&page=N` automáticamente.

---

## Caso 2: Sitio Tiendanube

Las tres piezas de arriba con `plataforma = 'tiendanube'`, y la URL termina en
`/productos/`.

**La fila de seed hace falta igual.** Es tentador saltearla, porque Tiendanube
es justo el destino al que cae un sitio sin registrar: `ScraperFactory` no tiene
un `if` para Tiendanube, es el `default`, y `SiteRegistry` se abstiene hacia
`"tiendanube"`. O sea que el scraper anda sin sembrar nada. Pero
`SitioSeedSyncTest` se pone rojo, y con razón: sin la fila no se puede
distinguir "es Tiendanube" de "nadie lo registró", que es la ambigüedad que
costó los 0 productos de `forever`.

**Nota**: si el sitio usa `/coleccion/`, `/indumentaria/` u otras rutas en lugar de `/productos/`, agregarlo al array `paths` en `TiendanubePage.buildExtractorJs()`.

---

## Caso 3: Sitio VTEX

Las tres piezas de arriba con `plataforma = 'vtex'`.

`VtexPage` intenta primero la API Legacy (`/api/catalog_system/pub/products/search`), y si devuelve vacío, prueba la API IO (`/api/io/_v/api/intelligent-search/product_search/trade-policy/1`).

---

## Caso 4: Plataforma Vaypol/City (Rails SSR)

Las tres piezas de arriba con `plataforma = 'vaypol'`.

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

### 3. Dar de alta la plataforma: migración + `ScraperFactory.java` + los dos tests de sincronía

Desde `V20`, `ScraperFactory.crear` no mantiene un name-set por sitio: lee
`sitio.plataforma` a través de `SiteRegistry`. Un sitio nuevo sobre una
plataforma nueva necesita todas estas piezas en el **mismo commit**
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

**d) `PlatformVocabularySyncTest`** — mové el puntero a la migración más nueva
que redefine el CHECK. El test compara `PLATAFORMAS_VALIDAS` contra el dominio
de **esa** migración, así que dejarlo apuntando a la anterior lo hace comparar
contra un vocabulario viejo.

**e) `SitioSeedSyncTest`** — sumá la migración a su `List.of(V18, V24)`. El set
de sitios sembrados se arma desde esa lista hardcodeada, así que una fila de
seed en una migración que la lista no nombra es, para el test, un sitio sin
sembrar.

### 4. `config.properties`
```properties
sitio.nombre.url=https://DOMINIO.com
sitio.nombre.activo=true
```

---

## Checklist al agregar cualquier sitio

- [ ] La fila de seed en `sitio` existe, con `origen='config'` y el `plataforma` correcto
- [ ] La migración que la siembra está en la `List.of(...)` de `SitioSeedSyncTest`
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

---

## Caso 6 — Headless: la plataforma de atrás **no** es la plataforma

Una tienda puede servir los datos de una plataforma y **no ser** esa plataforma.
INPRO (`inpro.ar`) es el caso testigo: su payload trae los objetos crudos de la
API de Tiendanube —`variants[]`, `compare_at_price`, `promotional_price`,
`stock`, `sku`, imágenes en `acdn-us.mitiendanube.com`— pero la vidriera es un
**Next.js propio en Vercel**. No hay DOM de Tiendanube en ningún lado.

**Por qué importa y no es una sutileza.** Sembrarlo como `plataforma='tiendanube'`
lo rutea a `TiendanubeScraper`, que sale a buscar selectores de un tema de
Tiendanube que ahí no existen. Resultado: **0 productos, sin error**. Es
exactamente el bug que `V24` cerró para Rockethard y Venex, y la razón por la que
la plataforma es un dato del sitio y no una heurística sobre la URL.

### Cómo detectarlo

1. **Mirá los headers, no sólo el HTML**: `x-powered-by: Next.js` + `server: Vercel`
   sobre un dominio propio es la señal.
2. **Buscá el storefront clásico antes de darlo por hecho.** En INPRO
   `inpro.mitiendanube.com` existe pero redirige a **otra tienda**
   (`inproindumentaria.com.ar`), y los slugs candidatos dan `410`. Adivinar el
   slug `*.mitiendanube.com` es un callejón sin salida: si no lo encontrás en
   dos intentos, no está.
3. **Confirmá de dónde salen los datos**: en Next.js con App Router, del payload
   RSC embebido en chunks `self.__next_f.push([1,"<json escapado>"])`.

### Cómo leerlo

Leer el payload es **estrictamente mejor** que scrapear el DOM renderizado: trae
precio de lista, precio promocional, precio comparado, stock por variante y SKU,
que es más de lo que la vidriera muestra. Tres trampas, las tres medidas contra
el sitio real y las tres invisibles en un fixture escrito a mano:

| Trampa | Qué pasa |
|---|---|
| **El regex para un string JS escapado** | `"(?:[^"\\]|\\.)*"` es una alternancia bajo cuantificador y `java.util.regex` la implementa **con recursión**: `StackOverflowError` con un chunk de 7,5 KB, y los reales son de cientos de KB. Escaneo lineal, siempre |
| **El orden de las claves del JSON** | No es estable entre superficies. En las páginas de categoría el objeto abre con `"id"`; en las de producto abre con `"name"` y el `"id"` aparece después de `"variants"`. Un ancla `{"id":` anda en una superficie y devuelve **0 en la otra**, en silencio. `InproPage.objetosConVariants` escanea con pila y busca el objeto **dueño de la clave `variants`** |
| **Los chunks vienen partidos** | Un objeto de producto puede empezar en un chunk y terminar en el siguiente. Hay que concatenar **todo** antes de leer, y el fixture de test tiene que venir partido también o no prueba nada |

### Enumeración

Usá el **sitemap**, no la API interna: en INPRO `robots.txt` tiene `/api/*` en
`Disallow` y el sitemap declarado en `Sitemap:`. `/server-sitemap.xml` da 106
productos y 16 categorías.

Y separá **visto** de **aceptado**: un producto que la banda de precios descarta
ya se vio, así que la pasada de fallback no tiene que volver a pedir su página.
Medido en INPRO con `precio.maximo=300000`, esa distinción son 6 fetches en vez
de 38, de ~550 KB cada uno.

### La fila de seed

Igual que cualquier alta, más el `CHECK` de plataforma si el valor es nuevo
(`V27` para `inpro`). Si además el sitio inaugura un **rubro**, se amplían
también `chk_productos_rubro_domain` y `sitio_rubro_forzado_check`, y las
categorías nuevas necesitan su fila en la tabla `categoria` de `V13` — sin eso
la FK rechaza el upsert y, como `ProductRepository` se traga los errores SQL,
el síntoma es **"0 nuevos"** y no un error. Ver `docs/DATABASE.md`.
