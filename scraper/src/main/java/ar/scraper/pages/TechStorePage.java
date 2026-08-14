package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    // MAXIMUS — ASP.NET custom, page-method JSON API behind a Playwright session
    // URL: /Productos/{Slug}/maximus.aspx?/CAT={id}/SCAT=-1/M=-1/OR=1/PAGE={n}/
    // API:  POST /wfmWebSite2.aspx/wsNRW_Script  (needs the session cookies a
    //       category-page navigation mints — a cookie-less call is REJECTED,
    //       see MaximusPayloadException/parseMaximusPayload)
    // Producto: /Producto/{Slug}/ITEM={id}/maximus.aspx
    // ═══════════════════════════════════════════════════════════════════════

    /** Cosmetic slug segment — confirmed live that only `CAT=` routes, any text works. */
    private static final String MAXIMUS_SLUG_PLACEHOLDER = "categoria";
    private static final String MAXIMUS_GUID = "a632009a-7686-4fcb-a0b4-24b18caf5234";
    private static final String MAXIMUS_SCRIPT_LABEL = "web.MAX.GetItemList4Search_v3_V6";
    private static final int MAXIMUS_MAX_PAGES = 30;

    /** Fallback IDs measured live 2026-08-13 when nav discovery finds none — not exhaustive. */
    private static final List<Integer> MAXIMUS_FALLBACK_CATS =
            List.of(48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60);

    record MaximusPage(int page, int pagesTotal, int itemsTotal, List<JsonNode> items) {}

    private List<Product> scrapeMaximus() {
        List<Product> result = new ArrayList<>();
        Set<String> vistas   = new HashSet<>();

        List<Integer> catIds = discoverMaximusCategoryIds();
        log.info("[{}] Categorías Maximus: {}", sitio, catIds);

        for (int catId : catIds) {
            List<Product> prods = crawlMaximusCategory(catId, vistas);
            result.addAll(prods);
        }
        log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
        return result;
    }

    /**
     * Package-private: pagination loop for one category, mocked-Page
     * testable (design D6). A {@link MaximusPayloadException} from
     * {@link #parseMaximusPayload} is NEVER caught here — it propagates
     * through {@code scrapeMaximus}/{@code scrapeAll} into
     * {@code BaseScraper.ejecutar}'s catch, landing in
     * {@code ScrapeResult.error} instead of a silently empty result.
     */
    List<Product> crawlMaximusCategory(int catId, Set<String> vistas) {
        List<Product> result = new ArrayList<>();
        for (int p = 1; p <= MAXIMUS_MAX_PAGES; p++) {
            String navUrl = baseUrl + "/Productos/" + MAXIMUS_SLUG_PLACEHOLDER + "/maximus.aspx?/CAT="
                    + catId + "/SCAT=-1/M=-1/OR=1/PAGE=" + p + "/";
            navigateTo(navUrl); // mints ASP.NET_GBP_SessionId_*/GBP_<guid> cookies

            String responseText = fetchMaximusApiPage(catId, p);
            String d = extractD(responseText);
            MaximusPage maximusPage;
            try {
                maximusPage = parseMaximusPayload(d);
            } catch (MaximusPayloadException e) {
                // WARN (not debug) — this is the R1 loud-failure contract: visible in
                // error.log, not swallowed into an indistinguishable empty category.
                // Rethrown immediately, never caught-and-return-empty.
                log.warn("[{}] CAT={} p={}: Maximus payload gate/forma inesperada — {}", sitio, catId, p, e.getMessage());
                throw e;
            }

            if (maximusPage.items().isEmpty()) {
                log.info("[{}] CAT={} p={}: categoría vacía (itemsTotal={})", sitio, catId, p, maximusPage.itemsTotal());
                break;
            }
            for (JsonNode item : maximusPage.items()) {
                maximusItemToProduct(item).ifPresent(prod -> {
                    if (vistas.add(prod.url())) result.add(prod);
                });
            }
            log.debug("[{}] CAT={} p={}/{}: +{}", sitio, catId, p, maximusPage.pagesTotal(), maximusPage.items().size());
            if (p >= maximusPage.pagesTotal()) break;
        }
        return result;
    }

    /** POST body built in Java/Jackson — never a JSON literal concatenated into JS source (CODE-7). */
    private String fetchMaximusApiPage(int catId, int pageNum) {
        try {
            ObjectNode inner = MAPPER.createObjectNode();
            inner.put("ws_id", MAXIMUS_GUID);
            inner.put("comp_id", 1);
            inner.put("prli_id", 17);
            inner.put("cust_id", -1);
            inner.put("page", pageNum);
            inner.put("cat_id", catId);
            inner.put("subcat_id", -1);
            inner.put("brand_id", -1);
            inner.put("local", 0);
            inner.put("search", "");
            inner.put("order", 1);
            inner.put("price_min", "");
            inner.put("price_max", "");
            inner.putArray("wco_tV");

            ObjectNode outer = MAPPER.createObjectNode();
            outer.put("guidWS_Id", MAXIMUS_GUID);
            outer.put("strScriptLabel", MAXIMUS_SCRIPT_LABEL);
            outer.put("JSonParameters", MAPPER.writeValueAsString(inner));
            String body = MAPPER.writeValueAsString(outer);

            // Two-arg evaluate: `body` travels as a real JS argument, never
            // interpolated into the script source (CODE-7 by elimination —
            // this removes two of the four quoting levels the raw string
            // concatenation would otherwise need).
            return (String) page.evaluate(
                    "(body) => fetch('/wfmWebSite2.aspx/wsNRW_Script', {"
                            + "method:'POST',"
                            + "headers:{'Content-Type':'application/json; charset=utf-8'},"
                            + "credentials:'same-origin',"
                            + "body"
                            + "}).then(r => r.text())",
                    body);
        } catch (Exception e) {
            // Un fallo de red/serialización acá NO es el session-gate — es una
            // falla de transporte genérica, se trata como cualquier otro error
            // de página (log + página vacía), no como MaximusPayloadException.
            log.debug("[{}] fetchMaximusApiPage error CAT={} p={}: {}", sitio, catId, pageNum, e.getMessage());
            return "";
        }
    }

    /** Extrae el campo `d` del envelope `{"d": "..."}`. Cadena vacía -> "" (no null), tratado como gate por parseMaximusPayload. */
    private static String extractD(String outerJson) {
        try {
            JsonNode outer = MAPPER.readTree(outerJson);
            return outer.path("d").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Pure, package-private (design D6, D2). Discriminator:
     * <ul>
     *   <li>{@code d} parses to a JSON object whose {@code data.items} is a
     *       (possibly empty) array -> real payload, never throws.</li>
     *   <li>Anything else — does not parse as JSON, parses to a non-object
     *       (bare scalar), or an object missing {@code data.items} — is the
     *       session/module gate shape or an unrecognized contract change,
     *       and MUST fail loudly rather than look like an empty category.</li>
     * </ul>
     */
    static MaximusPage parseMaximusPayload(String d) {
        if (d == null || d.isBlank()) throw new MaximusPayloadException(prefixOf(d));

        JsonNode node;
        try {
            node = MAPPER.readTree(d);
        } catch (Exception e) {
            throw new MaximusPayloadException(prefixOf(d));
        }
        if (!node.isObject()) throw new MaximusPayloadException(prefixOf(d));

        JsonNode data = node.path("data");
        if (!data.isObject() || !data.has("items") || !data.path("items").isArray()) {
            throw new MaximusPayloadException(prefixOf(d));
        }

        int page = data.path("page").asInt(0);
        int pagesTotal = data.path("pagesTotal").asInt(0);
        int itemsTotal = data.path("itemsTotal").asInt(0);
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : data.path("items")) items.add(item);
        return new MaximusPage(page, pagesTotal, itemsTotal, items);
    }

    private static String prefixOf(String d) {
        if (d == null) return "";
        return d.length() > 120 ? d.substring(0, 120) : d;
    }

    private Optional<Product> maximusItemToProduct(JsonNode item) {
        String nombre = item.path("item_desc").asText("").trim();
        if (nombre.isBlank()) return Optional.empty();

        long itemId = item.path("item_id").asLong(-1);
        if (itemId < 0) return Optional.empty();

        double precio = item.path("prli_price_original").asDouble(0);
        if (precio <= 0) {
            // Fallback: prli_price_original ausente -> parsear el string AR-format.
            Optional<Double> parsed = parsePrecioTech(item.path("prli_price").asText(""));
            if (parsed.isEmpty()) return Optional.empty();
            precio = parsed.get();
        }
        if (precio <= 0 || precio < precioMin || precio > precioMax) return Optional.empty();

        Double precioOriginal = null;
        if (item.hasNonNull("strikeThroughPrice_original")) {
            double strike = item.path("strikeThroughPrice_original").asDouble(0);
            if (strike > precio) precioOriginal = strike;
        }

        String desc4link = item.path("item_desc4link").asText("");
        String url = baseUrl + "/Producto/" + desc4link + "/ITEM=" + itemId + "/maximus.aspx";

        // Maximus no expone ningún campo de imagen en la API (confirmado:
        // ninguna key del item la trae) — abstención (CODE-5), no inventada.
        return Optional.of(new Product(
                sitio, nombre, precio, precioOriginal,
                url, "", "", "",
                List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
    }

    /** Navega a la home y descubre IDs de categoría vía nav; frozen list como fallback si la descubierta da 0. */
    private List<Integer> discoverMaximusCategoryIds() {
        try {
            navigateTo(baseUrl + "/");
            String html = page.content();
            List<Integer> discovered = extractMaximusCategoryIds(html);
            if (!discovered.isEmpty()) return discovered;
        } catch (Exception e) {
            log.debug("[{}] discover maximus cats error: {}", sitio, e.getMessage());
        }
        log.warn("[{}] nav discovery returned 0 category ids, falling back to the frozen list of {}",
                sitio, MAXIMUS_FALLBACK_CATS.size());
        return MAXIMUS_FALLBACK_CATS;
    }

    private static final java.util.regex.Pattern MAXIMUS_CAT_LINK = java.util.regex.Pattern.compile(
            "CAT=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    static List<Integer> extractMaximusCategoryIds(String navHtml) {
        if (navHtml == null || navHtml.isBlank()) return List.of();
        Set<Integer> ids = new LinkedHashSet<>();
        var m = MAXIMUS_CAT_LINK.matcher(navHtml);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group(1));
                if (id > 0) ids.add(id);
            } catch (NumberFormatException ignored) { /* not a real id */ }
        }
        return List.copyOf(ids);
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
