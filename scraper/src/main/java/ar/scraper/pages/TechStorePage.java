package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Scraper genérico para tiendas de tecnología argentinas con plataformas custom.
 *
 *  FULLH4RD  — PHP custom, URL: /cat/supra/{ID}/{name}/{page}
 *  COMPRAGAMER — Angular SPA, catalog read from its own static JSON feed
 *                (static.compragamer.com/productos), not scraped from the DOM
 *  MAXIMUS   — ASP.NET custom, URL: /Productos/{category}.aspx
 */
public class TechStorePage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(TechStorePage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;
    private final TechStoreType tipo;

    public enum TechStoreType { MAXIMUS, FULLH4RD, COMPRAGAMER, GENERIC }

    /** FullH4rd category map: ID → name slug */
    private static final Map<Integer, String> FH_CATS = new LinkedHashMap<>();
    static {
        FH_CATS.put(3,  "placas-de-video");
        FH_CATS.put(5,  "equipos");        // PCs armadas
        FH_CATS.put(4,  "memorias");
        FH_CATS.put(12, "almacenamiento");
        FH_CATS.put(6,  "gabinetes");
        FH_CATS.put(8,  "teclados");
        FH_CATS.put(18, "monitores");
        FH_CATS.put(32, "notebooks");
        FH_CATS.put(54, "accesorios");
        FH_CATS.put(20, "impresoras");
        FH_CATS.put(62, "promociones");
    }

    public TechStorePage(Page page, int timeoutMs, String sitio, String baseUrl,
                         double precioMin, double precioMax, TechStoreType tipo) {
        super(page, timeoutMs);
        this.sitio     = sitio;
        this.baseUrl   = baseUrl.replaceAll("/+$", "");
        this.precioMin = precioMin;
        this.precioMax = precioMax;
        this.tipo      = tipo;
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public List<Product> scrapeAll() {
        return switch (tipo) {
            case FULLH4RD    -> scrapeFullH4rd();
            case COMPRAGAMER -> scrapeCompraGamer();
            case MAXIMUS     -> scrapeMaximus();
            default          -> List.of();
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FULLH4RD — /cat/supra/{ID}/{name}/{page}
    // ═══════════════════════════════════════════════════════════════════════

    private List<Product> scrapeFullH4rd() {
        List<Product> result = new ArrayList<>();
        Set<String> vistas   = new HashSet<>();

        for (Map.Entry<Integer, String> entry : FH_CATS.entrySet()) {
            int    id   = entry.getKey();
            String name = entry.getValue();
            log.info("[{}] categoría {}/{}", sitio, id, name);

            for (int p = 1; p <= 30; p++) {
                // URL: /cat/supra/{ID}/{name}/{page}
                String url = baseUrl + "/cat/supra/" + id + "/" + name + "/" + p;
                try {
                    navigateTo(url);
                    // Esperar que carguen los productos
                    page.waitForTimeout(800);

                    var prods = extractFH4rd(vistas);
                    if (prods.isEmpty()) {
                        log.debug("[{}] {}/{} p{}: fin", sitio, id, name, p);
                        break;
                    }
                    result.addAll(prods);
                    log.debug("[{}] {}/{} p{}: +{}", sitio, id, name, p, prods.size());
                } catch (Exception e) {
                    log.debug("[{}] error {}/{} p{}: {}", sitio, id, name, p, e.getMessage());
                    break;
                }
            }
        }
        log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
        return result;
    }

    /**
     * FullH4rd product card structure (PHP custom):
     *
     * <div class="item-prod">
     *   <a href="/prod/{slug}"><img ...></a>
     *   <h4 class="nombre-prod">GPU RTX 5070 Ti</h4>
     *   <div class="precio-prod">
     *     <span class="tachado">$999.999,99</span>   ← precio original
     *     $849.999,99                                 ← precio actual
     *   </div>
     * </div>
     */
    private List<Product> extractFH4rd(Set<String> vistas) {
        try {
            String json = (String) page.evaluate(
                "(function() {" +
                "  var results = [];" +
                "  var seen = new Set();" +
                "  var base = location.origin;" +
                "  var cards = document.querySelectorAll(" +
                "    '.item-prod, .card-prod, .prod-item, [class*=item-prod], [class*=prod]');" +
                "  if (!cards.length) return '[]';" +
                "  cards.forEach(function(card) {" +
                "    try {" +
                "      var a = card.querySelector('a[href]');" +
                "      if (!a) return;" +
                "      var href = a.getAttribute('href') || '';" +
                "      var url  = href.startsWith('http') ? href : base + href;" +
                "      if (!url || seen.has(url)) return;" +
                "      seen.add(url);" +
                // Nombre
                "      var nameEl = card.querySelector('.nombre-prod, h4, h3, h2, .name, .title');" +
                "      var nombre = nameEl ? nameEl.textContent.trim() : '';" +
                "      if (!nombre) nombre = a.textContent.trim();" +
                "      if (!nombre || nombre.length < 3) return;" +
                // Imagen
                "      var img = '';" +
                "      var imgEl = card.querySelector('img');" +
                "      if (imgEl) {" +
                "        img = imgEl.getAttribute('data-src') || imgEl.getAttribute('src') || '';" +
                "      }" +
                // Precio original (tachado)
                "      var priceOrig = '';" +
                "      var taEl = card.querySelector('.tachado, del, s, .antes, .prev');" +
                "      if (taEl) priceOrig = taEl.textContent.trim();" +
                // Precio actual
                "      var priceEl = card.querySelector('.precio-prod, .precio, .price, .monto, .current-price');" +
                "      var precio = '';" +
                "      if (priceEl) {" +
                "        var txt = priceEl.innerText || priceEl.textContent;" +
                // Quitar el texto tachado del precio actual
                "        if (taEl && taEl.parentNode === priceEl) {" +
                "          txt = txt.replace(taEl.textContent, '');" +
                "        }" +
                "        var m = txt.match(/\\$[\\s]?[\\d.,]+/);" +
                "        if (m) precio = m[0].trim();" +
                "      }" +
                // Fallback: regex en el texto completo de la card
                "      if (!precio) {" +
                "        var allText = (card.innerText || card.textContent);" +
                "        var prices = allText.match(/\\$[\\s]?[\\d][\\d., ]{3,}/gm);" +
                "        if (prices && prices.length) {" +
                "          var sorted = prices.map(function(p) {" +
                "            return { raw: p, val: parseFloat(p.replace(/[^0-9]/g,'')) };" +
                "          }).filter(function(p){return p.val>0;}).sort(function(a,b){return a.val-b.val;});" +
                "          if (sorted.length) precio = sorted[0].raw;" +
                "        }" +
                "      }" +
                "      if (!precio) return;" +
                "      results.push({ nombre:nombre, precio:precio, precioOrig:priceOrig, url:url, img:img });" +
                "    } catch(e) {}" +
                "  });" +
                "  return JSON.stringify(results);" +
                "})()"
            );
            return parseProductNodes(json, vistas);
        } catch (Exception e) {
            log.debug("[{}] extractFH4rd error: {}", sitio, e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPRAGAMER — static JSON feed (design D3). Replaces the old React-SPA
    // HTML scrape (discoverCompraGamerCates/extractCompraGamer, deleted here):
    // the SPA loads its whole catalog from an unauthenticated, unpaginated
    // static endpoint, so reading it directly is strictly more robust than
    // scraping the rendered DOM ever was.
    // ═══════════════════════════════════════════════════════════════════════

    private static final String CG_FEED_HOST = "https://static.compragamer.com";

    private List<Product> scrapeCompraGamer() {
        try {
            // Navegar al origin de la tienda primero: mismo UA/cookies/CORS
            // que el resto de los scrapers, y consistente con cómo la propia
            // SPA hace estos tres GETs.
            navigateTo(baseUrl + "/");

            String productos = fetchJson(CG_FEED_HOST + "/productos");
            String categoriasSub = fetchJson(CG_FEED_HOST + "/categorias_sub");
            String marcas = fetchJson(CG_FEED_HOST + "/marcas");

            List<Product> result = parseCompraGamerFeed(
                    productos, categoriasSub, marcas, sitio, baseUrl, precioMin, precioMax);
            log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[{}] scrapeCompraGamer error: {}", sitio, e.getMessage());
            return List.of();
        }
    }

    /** GET vía fetch dentro de la página ya navegada (mismo UA/cookies que el resto del scrape). */
    private String fetchJson(String url) {
        return (String) page.evaluate("(u) => fetch(u).then(r => r.text())", url);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAXIMUS — custom ASP-like CMS
    // URL: /Productos/CAT={id}/SCAT=-1/M=-1/OR=1/maximus.aspx/PAGE={n}/
    // Producto: /Producto/{Slug}/ITEM={id}/maximus.aspx
    // ═══════════════════════════════════════════════════════════════════════

    // MAXIMUS — ASP.NET custom
    // ═══════════════════════════════════════════════════════════════════════

    private List<Product> scrapeMaximus() {
        List<Product> result = new ArrayList<>();
        Set<String> vistas   = new HashSet<>();

        // Descubrir IDs de categoría desde el nav de la homepage
        Set<Integer> catIds = discoverMaximusCategories();
        log.info("[{}] Categorías Maximus: {}", sitio, catIds);

        for (int catId : catIds) {
            for (int p = 1; p <= 25; p++) {
                // URL real: /Productos/CAT={id}/SCAT=-1/M=-1/OR=1/maximus.aspx/PAGE={n}/
                String url = baseUrl + "/Productos/CAT=" + catId
                    + "/SCAT=-1/M=-1/OR=1/maximus.aspx/PAGE=" + p + "/";
                try {
                    navigateTo(url);
                    // Esperar que los productos carguen (JavaScript renderiza precios)
                    try {
                        page.waitForSelector(
                            "a[href*='/Producto/'],a[href*='ITEM='],a[href*='/producto/']",
                            new Page.WaitForSelectorOptions().setTimeout(10000)
                        );
                    } catch (Exception waitEx) {
                        log.debug("[{}] CAT={} p={}: sin productos", sitio, catId, p);
                        break;
                    }
                    page.waitForTimeout(500); // precios se renderizan con JS

                    var prods = extractMaximus(vistas);
                    if (prods.isEmpty()) break;
                    result.addAll(prods);
                    log.debug("[{}] CAT={} p={}: +{}", sitio, catId, p, prods.size());
                } catch (Exception e) {
                    log.debug("[{}] CAT={} p={}: {}", sitio, catId, p, e.getMessage());
                    break;
                }
            }
        }
        log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
        return result;
    }

    /** Descubre IDs de categoría desde el nav de Maximus */
    private Set<Integer> discoverMaximusCategories() {
        try {
            navigateTo(baseUrl + "/");
            page.waitForTimeout(2000);
            String json = (String) page.evaluate(
                "(function(){" +
                "  var ids=new Set();" +
                "  var links=document.querySelectorAll('a[href]');" +
                "  for(var i=0;i<links.length;i++){" +
                "    var h=links[i].getAttribute('href')||'';" +
                "    var m=h.match(/CAT[_=](\\d+)/i);" +
                "    if(m){var v=parseInt(m[1]);if(v>0)ids.add(v);}" +
                "  }" +
                "  return JSON.stringify([...ids].sort(function(a,b){return a-b;}));" +
                "})()"
            );
            var arr = MAPPER.readTree(json);
            var ids = new java.util.LinkedHashSet<Integer>();
            for (var n : arr) { int v = n.asInt(); if (v > 0) ids.add(v); }
            if (!ids.isEmpty()) return ids;
        } catch (Exception e) {
            log.debug("[{}] discover maximus cats error: {}", sitio, e.getMessage());
        }
        // Fallback: categorías conocidas de Maximus (hardware gaming)
        var fb = new java.util.LinkedHashSet<Integer>();
        for (int id : new int[]{48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60}) fb.add(id);
        return fb;
    }

    private List<Product> extractMaximus(Set<String> vistas) {
        try {
            String json = (String) page.evaluate(
                "(function(){" +
                "  var results=[];" +
                "  var seen=new Set();" +
                "  var base=location.origin;" +
                // Buscar links a productos Maximus: /Producto/ con ITEM=
                "  var allA=Array.from(document.querySelectorAll('a[href]'));" +
                "  var links=allA.filter(function(a){" +
                "    var h=a.getAttribute('href')||'';" +
                "    return h.indexOf('/Producto/')>-1||h.indexOf('ITEM=')>-1;" +
                "  });" +
                "  links.forEach(function(a){" +
                "    try{" +
                "      var href=a.getAttribute('href')||'';" +
                "      var url=href.startsWith('http')?href:base+href;" +
                "      url=url.split('?')[0];" +
                "      if(!url||seen.has(url)||url.endsWith('/Productos/')||url.endsWith('/maximus.aspx'))return;" +
                "      seen.add(url);" +
                "      var container=a.closest('.pCard,.iCard,.card,[class*=Card]')||a;" +
                "      var txt=container.innerText||a.innerText||'';" +
                // Nombre: primer texto significativo
                "      var lines=txt.split('\\n').map(function(l){return l.trim();}).filter(function(l){return l.length>4;});" +
                "      var nombre='';" +
                "      for(var i=0;i<lines.length;i++){" +
                "        if(!lines[i].startsWith('$')&&!/^\\d/.test(lines[i])&&lines[i].length>5){nombre=lines[i];break;}" +
                "      }" +
                "      if(!nombre)nombre=a.getAttribute('title')||'';" +
                "      if(!nombre)return;" +
                // Precio: formato argentino $ 1.234.567
                "      var pm=txt.match(/\\$\\s?[\\d][\\d.,]{4,}/);" +
                "      if(!pm)return;" +
                "      var img='';" +
                "      var imgEl=container.querySelector('img')||a.querySelector('img');" +
                "      if(imgEl)img=imgEl.getAttribute('src')||'';" +
                "      results.push({nombre:nombre,precio:pm[0],precioOrig:'',url:url,img:img});" +
                "    }catch(e){}" +
                "  });" +
                "  return JSON.stringify(results);" +
                "})()"
            );
            return parseProductNodes(json, vistas);
        } catch (Exception e) {
            log.debug("[{}] extractMaximus error: {}", sitio, e.getMessage());
            return List.of();
        }
    }


    // ─── Generic link extractor ───────────────────────────────────────────

    private List<Product> extractGenericWithLinks(Set<String> vistas, String linkSelector) {
        try {
            String json = (String) page.evaluate(
                "(function() {" +
                "  var results = [];" +
                "  var seen = new Set();" +
                "  var base = location.origin;" +
                "  var links = Array.from(document.querySelectorAll('" + linkSelector + "'));" +
                "  links.forEach(function(a) {" +
                "    try {" +
                "      var href = a.getAttribute('href') || '';" +
                "      var url  = href.startsWith('http') ? href : base + href;" +
                "      url = url.split('?')[0];" +
                "      if (!url || seen.has(url)) return; seen.add(url);" +
                "      var txt = a.textContent.trim();" +
                "      if (!txt || txt.length < 3) return;" +
                "      var m = txt.match(/\\$[\\s]?[\\d][\\d.,]{3,}/);" +
                "      if (!m) return;" +
                "      var img = '';" +
                "      var imgEl = a.querySelector('img');" +
                "      if (imgEl) img = imgEl.getAttribute('src') || '';" +
                "      results.push({nombre:txt.replace(m[0],'').trim(), precio:m[0], precioOrig:'', url:url, img:img});" +
                "    } catch(e) {}" +
                "  });" +
                "  return JSON.stringify(results);" +
                "})()"
            );
            return parseProductNodes(json, vistas);
        } catch (Exception e) { return List.of(); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPRAGAMER — static JSON feed parsing (design D3, package-private pure)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Deviation from design's illustrative interface (D6): {@code baseUrl} was
     * added as a parameter. The design's contract listed only
     * ({@code productos, categoriasSub, marcas, sitio, min, max}), but the
     * feed has no per-item URL field — only {@code id_producto} — so a real
     * absolute product URL cannot be built without the store's own base URL.
     */
    static List<Product> parseCompraGamerFeed(String productos, String categoriasSub,
                                                String marcas, String sitio, String baseUrl,
                                                double precioMin, double precioMax) {
        Map<Integer, String> categorias = compraGamerLookup(categoriasSub);
        // marcas is parsed for logging visibility only (design D3) — id_marca is
        // NEVER resolved into Product.marca: V21's fk_productos_marca throws on a
        // brand absent from the marca table, ProductRepository swallows that
        // SQLException, and the run silently reports "0 nuevos". BrandExtractor
        // owns marca (CODE-6).
        Map<Integer, String> marcasPorId = compraGamerLookup(marcas);

        List<Product> result = new ArrayList<>();
        int totalItems = 0;
        try {
            JsonNode arr = MAPPER.readTree(productos);
            if (!arr.isArray()) return List.of();
            totalItems = arr.size();
            for (JsonNode item : arr) {
                compraGamerItemToProduct(item, categorias, sitio, baseUrl, precioMin, precioMax)
                        .ifPresent(result::add);
            }
        } catch (Exception e) {
            log.warn("[{}] parseCompraGamerFeed error: {}", sitio, e.getMessage());
            return List.of();
        }
        log.debug("[{}] feed CompraGamer: {} items -> {} productos ({} marcas resueltas)",
                sitio, totalItems, result.size(), marcasPorId.size());
        return result;
    }

    /** Parses a flat `[{id, nombre}, ...]` lookup array into an id -> trimmed name map. */
    private static Map<Integer, String> compraGamerLookup(String json) {
        Map<Integer, String> map = new HashMap<>();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return map;
            for (JsonNode n : arr) {
                int id = n.path("id").asInt(-1);
                String nombre = n.path("nombre").asText("").trim();
                if (id >= 0 && !nombre.isBlank()) map.put(id, nombre);
            }
        } catch (Exception e) {
            log.debug("compraGamerLookup error: {}", e.getMessage());
        }
        return map;
    }

    private static Optional<Product> compraGamerItemToProduct(
            JsonNode item, Map<Integer, String> categorias, String sitio, String baseUrl,
            double precioMin, double precioMax) {

        boolean vendible = item.path("vendible").asInt(0) != 0;
        int stock = item.path("stock").asInt(0);
        if (!vendible || stock <= 0) return Optional.empty();

        String nombre = item.path("nombre").asText("").trim();
        if (nombre.isBlank()) return Optional.empty();

        int idProducto = item.path("id_producto").asInt(-1);
        if (idProducto < 0) return Optional.empty();

        double precioLista = item.path("precioLista").asDouble(0);
        double precioEspecial = item.path("precioEspecial").asDouble(0);
        double precio = precioEspecial > 0 ? precioEspecial : precioLista;
        if (precio <= 0 || precio < precioMin || precio > precioMax) return Optional.empty();

        Double precioOriginal = null;
        if (precioLista > precio) {
            precioOriginal = precioLista;
        } else if (item.hasNonNull("precioListaAnterior")) {
            double anterior = item.path("precioListaAnterior").asDouble(0);
            if (anterior > precio) precioOriginal = anterior;
        }

        String categoria;
        int idSubcategoria = item.path("id_subcategoria").asInt(-1);
        if (categorias.containsKey(idSubcategoria)) {
            categoria = categorias.get(idSubcategoria);
        } else {
            int idCategoria = item.path("id_categoria").asInt(-1);
            categoria = categorias.getOrDefault(idCategoria, "");
        }

        String img = "";
        JsonNode imagenes = item.path("imagenes");
        if (imagenes.isArray() && !imagenes.isEmpty()) {
            JsonNode primera = null;
            int minOrden = Integer.MAX_VALUE;
            for (JsonNode im : imagenes) {
                int orden = im.path("orden").asInt(Integer.MAX_VALUE);
                if (orden < minOrden) { minOrden = orden; primera = im; }
            }
            if (primera != null) {
                String imgNombre = primera.path("nombre").asText("");
                if (!imgNombre.isBlank()) img = "https://imagenes.compragamer.com/" + imgNombre;
            }
        }

        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String url = base + "/producto/" + idProducto;

        return Optional.of(new Product(
                sitio, nombre, precio, precioOriginal,
                url, img, categoria, "",
                List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
    }

    // ─── Shared: parse JSON array → List<Product> ─────────────────────────

    private List<Product> parseProductNodes(String json, Set<String> vistas) {
        if (json == null || json.equals("[]")) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();
            List<Product> result = new ArrayList<>();
            for (JsonNode n : arr) {
                String url = n.path("url").asText("");
                if (url.isBlank() || vistas.contains(url)) continue;
                vistas.add(url);
                fromNode(n).ifPresent(result::add);
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    private Optional<Product> fromNode(JsonNode n) {
        String nombre = n.path("nombre").asText("").trim();
        if (nombre.isBlank() || nombre.length() < 3) return Optional.empty();

        Optional<Double> precio = parsePrecioTech(n.path("precio").asText(""));
        if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax)
            return Optional.empty();

        String url  = n.path("url").asText("");
        String img  = n.path("img").asText("");
        if (img.startsWith("//")) img = "https:" + img;

        Double precioOrig = null;
        Optional<Double> po = parsePrecioTech(n.path("precioOrig").asText(""));
        if (po.isPresent() && po.get() > precio.get()) precioOrig = po.get();

        String cat = normalizarCat(nombre);

        return Optional.of(new Product(
                sitio, nombre, precio.get(), precioOrig,
                url, img, cat, "",
                List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
    }

    // ─── Price parser ─────────────────────────────────────────────────────

    static Optional<Double> parsePrecioTech(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        // Quitar todo excepto dígitos, puntos, comas
        String s = raw.replaceAll("[^0-9.,]", "").trim();
        if (s.isBlank()) return Optional.empty();

        long nPuntos = s.chars().filter(c -> c == '.').count();
        long nComas  = s.chars().filter(c -> c == ',').count();

        if (nComas == 1) {
            // Coma como decimal: 849.999,99 → 849999.99
            s = s.replace(".", "").replace(",", ".");
        } else if (nPuntos >= 2) {
            // Múltiples puntos = separador de miles: 1.249.999
            s = s.replace(".", "").replace(",", "");
        } else {
            s = s.replace(".", "").replace(",", "");
        }

        try {
            double v = Double.parseDouble(s.trim());
            return (v > 0 && v < 200_000_000) ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException e) { return Optional.empty(); }
    }

    private String normalizarCat(String nombre) {
        String n = nombre.toLowerCase();
        if (n.contains("rtx") || n.contains("rx ") || n.contains("geforce") ||
            n.contains("radeon") || n.contains("placa de video") || n.contains("gpu")) return "GPU";
        if (n.contains("ryzen") || n.contains("intel core") || n.contains("procesador") ||
            n.contains("cpu") || n.contains("i5") || n.contains("i7") || n.contains("i9")) return "CPU";
        if (n.contains("ddr") || n.contains("memoria ram") || n.contains("ram")) return "RAM";
        if (n.contains("nvme") || n.contains("ssd") || n.contains("disco") || n.contains("hdd")) return "Almacenamiento";
        if (n.contains("notebook") || n.contains("laptop")) return "Notebook";
        if (n.contains("monitor")) return "Monitor";
        if (n.contains("teclado")) return "Teclado";
        if (n.contains("mouse")) return "Mouse";
        if (n.contains("auricular") || n.contains("headset")) return "Auricular";
        if (n.contains("gabinete")) return "Gabinete";
        if (n.contains("fuente")) return "Fuente";
        if (n.contains("motherboard") || n.contains("placa madre")) return "Motherboard";
        if (n.contains("cooler") || n.contains("refriger")) return "Cooling";
        if (n.contains("silla")) return "Silla Gamer";
        return "PC & Tech";
    }
}
