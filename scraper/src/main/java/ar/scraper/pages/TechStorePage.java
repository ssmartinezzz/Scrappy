package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import org.apache.commons.lang3.StringUtils;

/**
 * Scraper genérico para tiendas de tecnología argentinas con plataformas custom.
 *
 *  COMPRAGAMER — Angular SPA, catalog read from its own static JSON feed
 *                (static.compragamer.com/productos), not scraped from the DOM
 *  MAXIMUS   — ASP.NET custom, URL: /Productos/{category}.aspx
 *
 * <p>FullH4rd used to live here too (URL: {@code /cat/supra/{ID}/{name}/{page}})
 * until the site's redesign (fix-failing-site-scrapers, T5) moved it to its
 * own {@link FullH4rdPage} — its listing markup, category discovery and
 * pagination no longer have anything in common with this class's shape.</p>
 */
public class TechStorePage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(TechStorePage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;
    private final TechStoreType tipo;

    public enum TechStoreType { MAXIMUS, COMPRAGAMER, GENERIC }

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
            case COMPRAGAMER -> scrapeCompraGamer();
            case MAXIMUS     -> scrapeMaximus();
            default          -> List.of();
        };
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
    /** GlobalBluePoint serves item pictures off a fixed path keyed by {@code item_code4web}. */
    private static final String MAXIMUS_IMAGE_PATH = "/Temp/App_WebSite/App_PictureFiles/Items/";
    private static final String MAXIMUS_IMAGE_SUFFIX = "_600.jpg";

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
        if (StringUtils.isBlank(d)) throw new MaximusPayloadException(prefixOf(d));

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

        // La API no trae un campo de imagen, pero sí `item_code4web`, que es la
        // clave con la que el propio sitio arma el <img> del listado. Medido en
        // vivo 2026-08-15: HEAD 200 en 121/121 items de las CAT 48/56/68/3/10.
        // Sin código no hay URL que valga: abstención (CODE-5), no una a medias.
        String itemCode = item.path("item_code4web").asText("").trim();
        String img = itemCode.isBlank()
                ? ""
                : baseUrl + MAXIMUS_IMAGE_PATH + itemCode + MAXIMUS_IMAGE_SUFFIX;

        return Optional.of(new Product(
                sitio, nombre, precio, precioOriginal,
                url, img, "", "",
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
        if (StringUtils.isBlank(navHtml)) return List.of();
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
                String imgNombre = primera.path("nombre").asText("").trim();
                if (!imgNombre.isBlank()) img = CG_IMAGE_PREFIX + imgNombre + CG_IMAGE_SUFFIX;
            }
        }

        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String url = base + "/producto/" + slugCompraGamer(nombre) + "_" + idProducto;

        return Optional.of(new Product(
                sitio, nombre, precio, precioOriginal,
                url, img, categoria, "",
                List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
    }

    /**
     * The feed's {@code imagenes[].nombre} is the image KEY, not its URL — the
     * bucket path has to be rebuilt around it. Confirmed live 2026-08-15 against
     * the {@code <img>} the store itself renders, and with HEAD 200 on the URL
     * built here. The {@code Imganen} typo is theirs and is load-bearing: the
     * bare {@code imagenes.compragamer.com/{nombre}} the previous code built
     * answers {@code 403 AccessDenied} for 100% of the catalog.
     */
    private static final String CG_IMAGE_PREFIX =
            "https://imagenes.compragamer.com/productos/compragamer_Imganen_general_";
    private static final String CG_IMAGE_SUFFIX = ".jpg";

    private static final java.util.regex.Pattern CG_NO_ALFANUMERICO =
            java.util.regex.Pattern.compile("[^A-Za-z0-9]+");

    /**
     * Builds the {@code {slug}} half of Compragamer's {@code /producto/{slug}_{id}}
     * route. The store's router keys on the trailing {@code _{id}} — measured live,
     * {@code /producto/x_20213} renders the right product — but the bare
     * {@code /producto/{id}} the previous code built has no {@code _} at all and
     * the router bounces it to the homepage, so every row held a dead link.
     * Each RUN of non-alphanumerics collapses into one underscore, which is how
     * the store builds its own links ({@code Tp-Link TG-3468} -> {@code Tp_Link_TG_3468}).
     */
    static String slugCompraGamer(String nombre) {
        if (StringUtils.isBlank(nombre)) return "";
        String slug = CG_NO_ALFANUMERICO.matcher(nombre.trim()).replaceAll("_");
        return slug.replaceAll("^_+", "").replaceAll("_+$", "");
    }

    // ─── Shared: parse JSON array → List<Product> ─────────────────────────

    List<Product> parseProductNodes(String json, Set<String> vistas) {
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
        // FullH4rd sirve el src root-relative (/img/productos/{cat}/{slug}-0.jpg):
        // sin absolutizar, la fila guarda un path pelado que no puede fetchear ni
        // el dashboard ni el clasificador visual.
        String img  = ImageUrl.absolutize(n.path("img").asText(""), baseUrl);

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
        if (StringUtils.isBlank(raw)) return Optional.empty();
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
