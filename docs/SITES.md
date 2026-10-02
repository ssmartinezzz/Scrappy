# Sitios configurados

> Tabla de sitios, plataformas, rubros y detección de plataforma. Movido desde `CLAUDE.md` (2026-09-28), que pasó a ser sólo índice.

## Sitios configurados (`config.properties`)

| Sitio | Plataforma | Rubro | Notas |
|-------|-----------|-------|-------|
| freres, vcp, forever | Shopify | moda | `forever` está en el name-set SHOPIFY desde 2026-07-14 (antes caía a TN y daba 0 productos) |
| foreverbstrd | Tiendanube | moda | `/collections/all` (URL estilo Shopify) pasó a dar 404; catálogo real en `/productos/`. Es TN real — **NO** agregarlo al name-set |
| harvey | Tiendanube | moda | Única con `urls_extra` (outlet `otras-temporadas1`, pagina con `?page=N`; `?mpage=N` no pagina — el server ignora el parámetro y sirve siempre la misma página) |
| midway, batuk, tussy, bulks, bullbenny, barnes, eldon | Tiendanube | moda | Batuk+Huoky misma tienda (huoky comentado) |
| fuark, fursten | Tiendanube | gym | Fursten pagina solo vía fallback `?page=N`. No existe flag `GYM_SITIOS` |
| monkyforce | Monkyforce (propio) | gym | |
| entreno | Tiendanube | suplementos | **~636 productos**: 53 páginas de 12, la 54 devuelve 0 (medido 2026-08-20). Hasta el tope configurable rendía ~313 — el techo de 25 páginas cortaba a la mitad, en silencio. El scroll infinito corre **sólo** en `/productos/` pelado y se **apaga** con `?page=N` en la URL, así que lo que pagina de verdad es `?page=N`; `?mpage=N` es marcador client-side y no pagina nada en el HTML crudo. No tiene links de pager en el DOM: llega a la página 2 por el fallback que construye la URL |
| morashop | Morashop (Tiendanube, page propia) | suplementos | Competidor directo de entreno, ~510 productos crudos en 12 categorías hoja. **NO es plataforma `tiendanube`** aunque la tienda lo sea: el extractor compartido le sirve tal cual, pero necesita page propia porque **no tiene URL de catálogo**. `/productos/` es una landing del tema con CERO productos y `/suplementos/` es un índice, también cero — apuntar a cualquiera de las dos da 0 en silencio (la clase de bug que cerró `V24`). `MorashopPage` descubre las hojas del landing en runtime y **tira `MorashopDiscoveryException`** si no encuentra ninguna. La API REST de TN da 404 acá, pero además está **apagada a propósito** (`usaApi()=false`): devuelve la tienda entera sin filtro por sección, y morashop además vende supermercado, electro-hogar y bodega, rubros que no tienen valor en el dominio. Sólo se crawlea `/suplementos/` |
| sporting | VTEX | deportes | ~7150 productos (2026-09-28); la API Legacy corta en ~2550 por consulta, se parte por categoría (ver `ADD_SCRAPER.md` → Caso 3) |
| vaypol, city | Vaypol (Rails SSR custom) | deportes | |
| dcshoes | WooCommerce | moda | |
| fullh4rd | Scraper propio (`FullH4rdPage`) | tecnologia | Hardware/PC. Rediseño del sitio (2026-09-28): `/productos?page=N` lista el catálogo ENTERO como `article.results-card` — **1918 productos**, 12/página, p160 trae 10, p161 trae 0. `price-current` es el precio, `price-list` el de lista (precioOriginal sólo si es mayor). `meta` es la categoría. `curl` da 403 acá; Playwright navega sin problema. Sirve el `src` del listado **root-relative** (`/img/productos/{cat}/{slug}-0.jpg`) — se absolutiza con `ImageUrl` como cualquier otro. Reemplaza el viejo árbol `/cat/supra/{id}/{name}/{page}` con `FH_CATS` hardcodeado (perdía CPUs, motherboards, fuentes) que ya no matcheaba el markup en vivo |
| maximus | Scraper propio (API session-gated) | tecnologia | URL: `/Productos/{Slug}/maximus.aspx?/CAT={id}/SCAT=-1/M=-1/OR=1/PAGE={p}/` (el `{Slug}` es cosmético, sólo `CAT=` rutea — confirmado en vivo). Productos vía `POST /wfmWebSite2.aspx/wsNRW_Script` **desde adentro de la página ya navegada** — un cookie-less call responde HTTP 200 con `{"d":"-2, Módulo GlobalBluePoint© GBPScripts NO ADQUIRIDO."}` (el gate no se detecta por status code). `parseMaximusPayload` **lanza** `MaximusPayloadException` ante esa forma en vez de devolver una categoría vacía silenciosa — se propaga sin atrapar hasta `BaseScraper.ejecutar`. La API no trae un campo de imagen, pero sí `item_code4web`: la imagen es `{base}/Temp/App_WebSite/App_PictureFiles/Items/{item_code4web}_600.jpg` (HEAD 200 en 121/121, CAT 48/56/68/3/10, 2026-08-15). Sin código → abstención. **745 productos** en un run real de sitio completo (2026-08-13): 73 categorías descubiertas del nav, 1122 únicos, 745 dentro de `precio.maximo` |
| compragamer | Scraper propio (feed JSON) | tecnologia | Lee `static.compragamer.com/productos` directo (1389 items, sin auth, sin paginar) — no scrapea el DOM de la SPA Angular. **650 productos** en un run real tras filtrar por stock/vendible y bandas de precio (2026-08-13). Dos claves del feed hay que reconstruirlas, no usarlas crudas: la imagen es `imagenes.compragamer.com/productos/compragamer_Imganen_general_{imagenes[].nombre}.jpg` (el typo `Imganen` es de ellos; sin el prefijo, el bucket S3 da `403 AccessDenied`), y la URL de producto es `/producto/{slug}_{id}` — el router de la SPA rutea por el sufijo `_{id}` y manda `/producto/{id}` pelado al home |
| rockethard | Qloud (propio, multi-tienda) | tecnologia | Server-rendered, `?page=N`. **503 productos** en un run real de sitio completo con las bandas de precio de producción (2026-08-13) tras registrarlo — nunca había tenido fila en `sitio` ni entrada en `config.properties`. `/productos` es 404 confirmado, nunca usar esa ruta |
| venex | osCommerce (propio) | tecnologia | Descubrimiento en dos niveles: categoría top → sub-categorías leaf en su landing (la landing muestra 12 productos no representativos, nunca se cuentan). `?page=N`, se detiene en página vacía **o** repetida — pasado el final real, Venex repite la última página en vez de devolver vacío. `page.content()` sirve el DOM re-serializado por Chromium (comillas dobles + entidad `&quot;`), no el HTML crudo del servidor (comillas simples) — el parser normaliza antes de matchear. El argumento de `enhancedClick` se lee **con Jackson**, no campo por campo con regex: los nombres traen la pulgada escapada (`15.6\"`) y un `"name":"([^"]*)"` se corta ahí y tira la card entera en silencio — medido en `/notebooks/`, eso costaba 20 de 47 productos únicos (2026-08-15). **1294 productos** en un run real de sitio completo (las 19 categorías top, 2026-08-13), sub-contado por esa pérdida |
| inpro | Inpro (Tiendanube headless) | oficina | Sillas ergonómicas, standing desks, brazos de monitor, iluminación. **NO es plataforma `tiendanube`**: sirve los objetos crudos de la API de Tiendanube pero la vidriera es un Next.js propio en Vercel, y el storefront clásico no es alcanzable (`inpro.mitiendanube.com` redirige a *otra* tienda, `inproindumentaria.com.ar`; los slugs candidatos dan 410). El catálogo se lee del payload RSC (`self.__next_f`), no del DOM. Enumera por `/server-sitemap.xml` (106 productos, 16 categorías) → páginas de categoría (100 productos en 16 fetches) → los 6 handles que ninguna categoría mostró, de a uno. **101 productos** en una corrida real (2026-08-20); los 5 `pod-*` restantes son cabinas con `price: null`, se venden a consultar. El orden de las claves del JSON **no** es estable: en categoría el objeto abre con `id`, en producto con `name` — anclar en `{"id":` da 0 en la mitad de las superficies, en silencio |
| zentra | Tiendanube | oficina | Sillas ergonómicas y standing desks — mismo catálogo que INPRO, pero Tiendanube **clásico**, no headless: `[data-product-id]` en el DOM y el extractor compartido lo lee sin tocar nada. **44 productos, todos en UNA página** (medido 2026-08-26): `?page=2` sirve una página vacía, así que corta el chequeo de dos vacías seguidas. La imagen viene SÓLO en `data-srcset` — el `src` es un GIF base64 de lazy-load en 44/44 cards; el extractor ya prueba `data-srcset` primero y descarta base64/placeholder |
| mmartinez | Tiendanube | moda | Calzado. **37 productos de a 12 por página** (medido 2026-08-26); pagina con `?page=N` y `?mpage=N` devuelve la página 1 (marcador client-side, igual que entreno). Sus cards traen **seis** elementos de precio: el real, dos de descuento por transferencia, uno de cuota, un contenedor con todo concatenado y un `js-compare-price-display` **oculto que dice `$0`**. El extractor toma la primera HOJA que parsea a > 0, así que saltea el `$0` y agarra bien (12/12); ese `$0` además llega a `compare`, pero `PrecioParser` excluye el cero y devuelve `empty`, así que `precioOriginal` queda NULL y no fabrica un descuento contra cero |
| vans | — | — | Comentado: plataforma Grimoldi custom, sin scraper |

### Detección de plataforma (`ScraperFactory.crear`, en orden)

Desde `V20` esto lee `sitio.plataforma` vía `SiteRegistry`, no name-sets en
código (ver [`docs/ADD_SCRAPER.md`](./ADD_SCRAPER.md)). La lista de abajo
es qué sitio hoy tiene sembrado cada valor, no un `Set.of(...)` a editar:

```
WOOCOMMERCE → dcshoes
MAXIMUS → maximus   FULLH4RD → fullh4rd   COMPRAGAMER → compragamer
VAYPOL  → vaypol, city
QLOUD   → rockethard
OSCOMMERCE → venex
INPRO   → inpro
VTEX    → sporting, o url contiene vtexcommercestable.com.br / vteximg.com.br
SHOPIFY → freres, vcp, forever, o url contiene myshopify.com
MONKYFORCE → monkyforce
MORASHOP → morashop
default → TiendanubePage (JS heurístico)
```

`plataformaDeFavorito`/`crearParaFavorito` resuelven favoritos solo a SHOPIFY/VTEX.

---
