package ar.scraper.pages;

import ar.scraper.model.Product;
import ar.scraper.promos.ProductPromoExtractor;
import ar.scraper.promos.VtexPromoExtractor;
import ar.scraper.model.SitioCategoriaNodo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;

import java.net.URI;
import java.time.LocalDate;
import java.util.function.ToIntFunction;
import java.util.*;
import org.apache.commons.lang3.StringUtils;

/** Si no está, se hace heurística sobre nombre + categorías. */
public class VtexPage extends StorePage implements CatalogPage, ArbolDeCategorias {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProductPromoExtractor PROMOS = new VtexPromoExtractor();
    private static final int PAGE_SIZE = 50;
    /** Techo del path IO (Vaypol y similares) — sin cambios en este fix. */
    private static final int MAX_PRODUCTS = 2500;

    /** Techo sólo si el header {@code resources} nunca llega. */
    private static final int PAGINAS_SEGURIDAD_SIN_HEADER = 400;
    static final int VENTANA_LEGACY = 2500;
    private static final int REINTENTOS = 3;
    private static final long ESPERA_REINTENTO_MS = 2_000;
    private static final int PROFUNDIDAD_ARBOL = 3;

    private final CriterioDeServicio criterioDeServicio;
    private List<CategoriaVtex> arbolObservado = List.of();
    private Set<Integer> hojasDeServicio = Set.of();

    private static final Set<String> PALABRAS_HOMBRE = Set.of(
            "hombre","hombres","masculino","masculinos","men","man","male","caballero","caballeros");
    private static final Set<String> PALABRAS_MUJER = Set.of(
            "mujer","mujeres","femenino","women","woman","female","dama","damas");
    private static final Set<String> PALABRAS_UNISEX = Set.of(
            "unisex","unisexo","neutro");

    public VtexPage(Page page, int timeoutMs,
                    String sitio, String baseUrl,
                    double precioMin, double precioMax) {
        this(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, ServicioPorNombreDeCategoria.para(sitio));
    }

    public VtexPage(Page page, int timeoutMs,
                    String sitio, String baseUrl,
                    double precioMin, double precioMax,
                    CriterioDeServicio criterioDeServicio) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax);
        this.criterioDeServicio = criterioDeServicio;
    }

    public List<Product> scrapeAll() {
        String dom = domain(baseUrl);

        prepararSesion(dom);

        List<Product> result = scrapeApiLegacy(dom);

        if (result.isEmpty()) {
            log.debug("[{}] Legacy API vacia, intentando VTEX IO Intelligent Search", sitio);
            limpiarTruncamiento();
            result = scrapeApiIO(dom);
        }

        return result;
    }

    /** VTEX stores con region-based catalog requieren esto para que la API devuelva productos. */
    private void prepararSesion(String dom) {
        try {
            navigateTo(dom);
            page.waitForTimeout(1500);

            String[] closeSels = {
                "button[data-testid='close-button']",
                "button[aria-label='Close']",
                "button[aria-label='Cerrar']",
                "[class*=modal] button[class*=close]",
                "[class*=popup] button[class*=close]",
                "[class*=modal] [class*=close]",
                "button[class*=dismiss]",
                "[data-dismiss='modal']"
            };
            for (String sel : closeSels) {
                try {
                    var el = page.querySelector(sel);
                    if (el != null) { el.click(); page.waitForTimeout(500); break; }
                } catch (Exception ignored) {}
            }

            String[] continueSels = {
                "button:has-text('Continuar')",
                "button:has-text('Aceptar')",
                "button:has-text('Confirmar')",
                "button:has-text('OK')"
            };
            for (String sel : continueSels) {
                try {
                    var el = page.querySelector(sel);
                    if (el != null) { el.click(); page.waitForTimeout(500); break; }
                } catch (Exception ignored) {}
            }

            // Escape como último recurso
            try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
            page.waitForTimeout(500);

        } catch (Exception e) {
            log.debug("[{}] prepararSesion: {}", sitio, e.getMessage());
        }
    }

    /**
     * Por {@code page.request()} (comparte las cookies de {@link #prepararSesion}) y no rindiendo
     * cada página de 7-9 MB en una pestaña.
     */
    private List<Product> scrapeApiLegacy(String dom) {
        List<String> partes = List.of("");
        try {
            List<CategoriaVtex> arbol = parseArbol(MAPPER.readTree(
                    getText(dom + "/api/catalog_system/pub/category/tree/" + PROFUNDIDAD_ARBOL)));
            arbolObservado = arbol;
            hojasDeServicio = idsDeServicio(arbol, criterioDeServicio);
            if (!arbol.isEmpty()) {
                partes = particionar(arbol, path -> totalLegacy(dom, path).orElse(0), VENTANA_LEGACY)
                        .stream().map(path -> "&fq=C:" + path).toList();
            }
        } catch (Exception e) {
            log.warn("[{}] Legacy sin árbol de categorías, se crawlea sin partir (tope {}): {}",
                    sitio, VENTANA_LEGACY, e.getMessage());
        }
        log.debug("[{}] Legacy: {} particiones", sitio, partes.size());

        Set<String> vistas = new HashSet<>();
        List<Product> result = new ArrayList<>();
        for (String fq : partes) {
            List<Product> parte;
            try {
                parte = crawlLegacy(dom, fq);
            } catch (BloqueoDeApi e) {
                if (result.isEmpty()) throw e;
                registrarTruncamiento(vistas.size() / PAGE_SIZE + 1, e);
                break;
            }
            for (Product p : parte) {
                if (vistas.add(p.url())) result.add(p);
            }
        }
        return result;
    }

    private List<Product> crawlLegacy(String dom, String fq) {
        List<Product> result = new ArrayList<>();
        int from = 0;
        OptionalInt total = OptionalInt.empty();
        int reintentos = 0;

        while (from < (total.isPresent() ? total.getAsInt() : PAGE_SIZE * PAGINAS_SEGURIDAD_SIN_HEADER)) {
            int to = from + PAGE_SIZE - 1;
            String apiUrl = dom + "/api/catalog_system/pub/products/search"
                    + "?_from=" + from + "&_to=" + to + fq + "&O=OrderByReleaseDateDESC";
            log.debug("[{}] Legacy API from={}", sitio, from);
            try {
                RespuestaApi resp = get(apiUrl);
                boolean ok = resp.ok();
                int status = resp.status();
                OptionalInt totalDeEstaPagina = resp.total();
                String body = ok ? resp.body() : null;
                if (!ok) {
                    if (reintentos < REINTENTOS) {
                        reintentos++;
                        page.waitForTimeout(ESPERA_REINTENTO_MS * reintentos);
                        log.debug("[{}] Legacy status HTTP {} en from={}, reintento {}",
                                sitio, status, from, reintentos);
                        continue;
                    }
                    registrarTruncamiento(from / PAGE_SIZE + 1, new IllegalStateException("HTTP " + status));
                    break;
                }
                reintentos = 0;
                if (totalDeEstaPagina.isPresent()) total = totalDeEstaPagina;

                if (StringUtils.isBlank(body) || !body.trim().startsWith("[")) {
                    log.warn("[{}] Legacy corta en from={}: body no es un array JSON (empieza con '{}')",
                            sitio, from, StringUtils.left(body == null ? "" : body.trim(), 30));
                    break;
                }
                JsonNode arr = MAPPER.readTree(body);
                if (!arr.isArray() || arr.isEmpty()) {
                    log.debug("[{}] Legacy from={}: página vacía, fin de catálogo", sitio, from);
                    break;
                }
                for (JsonNode prod : arr) fromVtex(prod, dom).ifPresent(result::add);
                log.debug("[{}] Legacy from={}: {} acumulados (total conocido: {})",
                        sitio, from, result.size(), total.isPresent() ? total.getAsInt() : "?");

                if (!continuaPaginando(arr.size(), from, total)) break;
                if (from + PAGE_SIZE >= VENTANA_LEGACY) {
                    log.warn("[{}] Legacy{}: la parte supera la ventana de {} y no tiene subcategorías, quedan afuera {}",
                            sitio, fq, VENTANA_LEGACY, total.isPresent() ? total.getAsInt() - VENTANA_LEGACY : "?");
                    break;
                }
                from += PAGE_SIZE;
            } catch (BloqueoDeApi e) {
                if (result.isEmpty()) throw e;
                registrarTruncamiento(from / PAGE_SIZE + 1, e);
                break;
            } catch (Exception e) {
                if (reintentos < REINTENTOS) {
                    reintentos++;
                    page.waitForTimeout(ESPERA_REINTENTO_MS * reintentos);
                    log.debug("[{}] Legacy error transitorio en from={}, reintento {}: {}",
                            sitio, from, reintentos, e.getMessage());
                    continue;
                }
                registrarTruncamiento(from / PAGE_SIZE + 1, e);
                break;
            }
        }
        return result;
    }

    record CategoriaVtex(int id, String nombre, List<CategoriaVtex> hijos) { }

    @Override
    public List<SitioCategoriaNodo> arbolDeCategorias() {
        List<SitioCategoriaNodo> nodos = new ArrayList<>();
        for (CategoriaVtex c : arbolObservado) aplanar(c, null, nodos);
        return nodos;
    }

    private void aplanar(CategoriaVtex c, Integer padre, List<SitioCategoriaNodo> nodos) {
        nodos.add(new SitioCategoriaNodo(c.id(), padre, c.nombre(), hojasDeServicio.contains(c.id())));
        for (CategoriaVtex h : c.hijos()) aplanar(h, c.id(), nodos);
    }

    static Set<Integer> idsDeServicio(List<CategoriaVtex> arbol, CriterioDeServicio criterio) {
        Set<Integer> ids = new HashSet<>();
        for (CategoriaVtex c : arbol) marcarServicios(c, false, criterio, ids);
        return Set.copyOf(ids);
    }

    private static void marcarServicios(CategoriaVtex c, boolean heredado, CriterioDeServicio criterio,
                                        Set<Integer> ids) {
        boolean servicio = heredado || criterio.esServicio(c.nombre());
        if (servicio) ids.add(c.id());
        for (CategoriaVtex h : c.hijos()) marcarServicios(h, servicio, criterio, ids);
    }

    static List<String> particionar(List<CategoriaVtex> arbol, ToIntFunction<String> totalDe, int ventana) {
        List<String> partes = new ArrayList<>();
        for (CategoriaVtex c : arbol) particionar(c, "/", totalDe, ventana, partes);
        return partes;
    }

    private static void particionar(CategoriaVtex c, String padre, ToIntFunction<String> totalDe,
                                    int ventana, List<String> partes) {
        String path = padre + c.id() + "/";
        int total = totalDe.applyAsInt(path);
        if (total == 0) return;
        if (total <= ventana || c.hijos().isEmpty()) {
            partes.add(path);
            return;
        }
        for (CategoriaVtex h : c.hijos()) particionar(h, path, totalDe, ventana, partes);
    }

    static List<CategoriaVtex> parseArbol(JsonNode arbol) {
        List<CategoriaVtex> out = new ArrayList<>();
        if (arbol == null || !arbol.isArray()) return out;
        for (JsonNode n : arbol) {
            if (n.hasNonNull("id")) out.add(new CategoriaVtex(n.get("id").asInt(), n.path("name").asText("").trim(),
                    parseArbol(n.path("children"))));
        }
        return out;
    }

    private OptionalInt totalLegacy(String dom, String path) {
        RespuestaApi resp = getConReintentoPorLimite(dom + "/api/catalog_system/pub/products/search?_from=0&_to=0&fq=C:" + path);
        return resp.ok() ? resp.total() : OptionalInt.empty();
    }

    private String getText(String url) {
        RespuestaApi resp = getConReintentoPorLimite(url);
        if (!resp.ok()) throw new IllegalStateException("HTTP " + resp.status() + " en " + url);
        return resp.body();
    }

    /** The site 429s back-to-back calls; the tree and the per-category counts must wait it out, not read it as empty. */
    private RespuestaApi getConReintentoPorLimite(String url) {
        RespuestaApi resp = get(url);
        for (int i = 1; i <= REINTENTOS && resp.status() == 429; i++) {
            page.waitForTimeout(ESPERA_REINTENTO_MS * i);
            log.debug("[{}] HTTP 429 en {}, reintento {}", sitio, url, i);
            resp = get(url);
        }
        return resp;
    }

    private record RespuestaApi(int status, String body, OptionalInt total) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    /** The WAF blocks the API through the page too: there is no way left to read this catalog. */
    static final class BloqueoDeApi extends IllegalStateException {
        BloqueoDeApi(String message) { super(message); }
    }

    /** Set on the first 403 from {@code page.request()}; from then on every call goes through the page. */
    private boolean modoFetch;

    /**
     * Single seam for the VTEX API. {@code page.request()} first; a 403 there means a WAF that blocks
     * Playwright's HTTP fingerprint but not the browser, so the rest of the run goes through
     * {@code fetch} inside the page (already on the site's origin after {@link #prepararSesion}).
     */
    private RespuestaApi get(String url) {
        if (!modoFetch) {
            RespuestaApi resp = viaRequest(url);
            if (resp.status() != 403) return resp;
            modoFetch = true;
            log.warn("[{}] HTTP 403 en page.request(): el resto de la corrida lee la API con fetch dentro de la página", sitio);
        }
        RespuestaApi resp = viaFetch(url);
        if (resp.status() == 403) {
            throw new BloqueoDeApi("HTTP 403 en " + url + " también con fetch dentro de la página");
        }
        return resp;
    }

    private RespuestaApi viaRequest(String url) {
        APIResponse resp = page.request().get(url);
        try {
            boolean ok = resp.ok();
            return new RespuestaApi(resp.status(), ok ? resp.text() : null, parseResourcesTotal(resp.headers()));
        } finally {
            resp.dispose();
        }
    }

    private static final String FETCH_JS = """
            async path => {
              const r = await fetch(path, {credentials: 'same-origin'});
              return {status: r.status, body: r.ok ? await r.text() : '', resources: r.headers.get('resources')};
            }""";

    @SuppressWarnings("unchecked")
    private RespuestaApi viaFetch(String url) {
        URI uri = URI.create(url);
        String relativa = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        Map<String, Object> r = (Map<String, Object>) page.evaluate(FETCH_JS, relativa);
        int status = ((Number) r.get("status")).intValue();
        Object body = r.get("body");
        Object resources = r.get("resources");
        Map<String, String> headers = resources == null ? Map.of() : Map.of("resources", resources.toString());
        return new RespuestaApi(status, body == null ? null : body.toString(), parseResourcesTotal(headers));
    }

    static OptionalInt parseResourcesTotal(Map<String, String> headers) {
        if (headers == null) return OptionalInt.empty();
        for (var e : headers.entrySet()) {
            if (!"resources".equalsIgnoreCase(e.getKey())) continue;
            String v = e.getValue();
            if (v == null) continue;
            int slash = v.lastIndexOf('/');
            if (slash < 0 || slash == v.length() - 1) continue;
            try {
                return OptionalInt.of(Integer.parseInt(v.substring(slash + 1).trim()));
            } catch (NumberFormatException ignored) { }
        }
        return OptionalInt.empty();
    }

    static boolean continuaPaginando(int itemsEnPagina, int from, OptionalInt total) {
        if (itemsEnPagina < PAGE_SIZE) return false;
        if (total.isPresent() && (from + PAGE_SIZE) >= total.getAsInt()) return false;
        return true;
    }

    /**
     * Endpoint: /api/io/_v/api/intelligent-search/product_search/trade-policy/1 No requiere
     * autenticacion.
     */
    private List<Product> scrapeApiIO(String dom) {
        List<Product> result = new ArrayList<>();
        int page_num = 1;
        int lastPage  = 1;

        while (page_num <= lastPage && result.size() < MAX_PRODUCTS) {
            String apiUrl = dom
                    + "/api/io/_v/api/intelligent-search/product_search/trade-policy/1"
                    + "?query=&count=" + PAGE_SIZE + "&page=" + page_num
                    + "&sort=release:desc";
            log.debug("[{}] IO API page={}", sitio, page_num);
            try {
                navigateConReintentos(apiUrl);
                String body = (String) page.evaluate("document.body.innerText");
                if (StringUtils.isBlank(body)) {
                    log.warn("[{}] IO corta en page={}: body vacío", sitio, page_num);
                    break;
                }

                String trimmed = body.trim();
                if (!trimmed.startsWith("{")) {
                    log.warn("[{}] IO corta en page={}: body no es un objeto JSON (empieza con '{}')",
                            sitio, page_num, StringUtils.left(trimmed, 30));
                    break;
                }

                JsonNode root = MAPPER.readTree(trimmed);
                JsonNode prods = root.path("products");
                if (!prods.isArray() || prods.isEmpty()) {
                    log.debug("[{}] IO page={}: página vacía, fin de catálogo", sitio, page_num);
                    break;
                }

                for (JsonNode prod : prods) fromVtexIO(prod, dom).ifPresent(result::add);

                JsonNode pagination = root.path("pagination");
                if (!pagination.isMissingNode()) {
                    JsonNode last = pagination.path("last");
                    if (!last.isMissingNode()) {
                        lastPage = last.path("index").asInt(1);
                    }
                }

                log.debug("[{}] IO page={}/{}: {} acumulados", sitio, page_num, lastPage, result.size());
                if (prods.size() < PAGE_SIZE) break;
                page_num++;
            } catch (Exception e) {
                registrarTruncamiento(page_num, e);
                break;
            }
        }
        return result;
    }

    /** La estructura es similar a Legacy pero con algunas diferencias en imágenes y specs. */
    private Optional<Product> fromVtexIO(JsonNode prod, String dom) {
        return toProduct(prod, dom, prod.path("productName").asText("").trim(), true);
    }

    private Optional<Product> fromVtex(JsonNode prod, String dom) {
        String nombre = prod.path("productName").asText("").trim();
        if (nombre.isBlank()) nombre = prod.path("name").asText("").trim();
        return toProduct(prod, dom, nombre, false);
    }

    /** IO also drops the image query string and falls back to categoryTree for the category. */
    private Optional<Product> toProduct(JsonNode prod, String dom, String nombre, boolean io) {
        try {
            if (nombre.isBlank()) return Optional.empty();
            Integer hoja = categoriaHoja(prod);
            if (hoja != null && hojasDeServicio.contains(hoja)) return Optional.empty();

            String linkText = prod.path("linkText").asText("");
            String url = linkText.isBlank() ? "" : dom + "/" + linkText + "/p";

            JsonNode items = prod.path("items");
            String img = primeraImagen(items);
            if (io) img = StringUtils.substringBefore(img, "?");

            Optional<Oferta> oferta = primeraOferta(items);
            if (oferta.isEmpty()) return Optional.empty();
            double p = oferta.get().precio();
            if (fueraDeRango(p)) return Optional.empty();

            String categoria = segmentoDeLaRutaMasProfunda(prod.path("categories"));
            if (io && categoria.isBlank()) {
                JsonNode catTree = prod.path("categoryTree");
                if (catTree.isArray() && !catTree.isEmpty()) {
                    categoria = catTree.get(catTree.size() - 1).path("name").asText("").trim();
                }
            }

            Product producto = producto(nombre, p, oferta.get().original(), url, img, categoria,
                    extraerGeneroVtex(prod, nombre), extraerTallesVtex(prod), hoja, enStock(items));
            return Optional.of(producto.toBuilder()
                    .promos(PROMOS.extraer(oferta.get().oferta(), LocalDate.now()))
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Integer categoriaHoja(JsonNode prod) {
        String id = prod.path("categoryId").asText("");
        if (!id.isBlank()) return parseIdEntero(id);
        JsonNode ids = prod.path("categoriesIds");
        if (!ids.isArray() || ids.isEmpty()) return null;
        String[] partes = ids.get(0).asText("").split("/");
        for (int i = partes.length - 1; i >= 0; i--) {
            if (!partes[i].isBlank()) return parseIdEntero(partes[i]);
        }
        return null;
    }

    private static Integer parseIdEntero(String id) {
        try {
            return Integer.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Oferta(double precio, Double original, JsonNode oferta) {}

    private static String primeraImagen(JsonNode items) {
        if (!items.isArray() || items.isEmpty()) return "";
        JsonNode images = items.get(0).path("images");
        if (!images.isArray() || images.isEmpty()) return "";
        return images.get(0).path("imageUrl").asText("");
    }

    /** The first priced offer in stock; when none is known to be in stock, the first priced one. */
    private static Optional<Oferta> primeraOferta(JsonNode items) {
        if (!items.isArray()) return Optional.empty();
        Optional<Oferta> primera = Optional.empty();
        for (JsonNode item : items) {
            JsonNode sellers = item.path("sellers");
            if (!sellers.isArray()) continue;
            for (JsonNode seller : sellers) {
                JsonNode offer = seller.path("commertialOffer");
                double p = offer.path("Price").asDouble(0);
                double pOrig = offer.path("ListPrice").asDouble(0);
                if (p <= 0) continue;
                Oferta oferta = new Oferta(p, pOrig > p ? pOrig : null, offer);
                if (Boolean.TRUE.equals(ofertaEnStock(offer))) return Optional.of(oferta);
                if (primera.isEmpty()) primera = Optional.of(oferta);
            }
        }
        return primera;
    }

    /** AvailableQuantity (> 0) first, IsAvailable when the quantity is missing; neither is unknown. */
    private static Boolean ofertaEnStock(JsonNode offer) {
        JsonNode cantidad = offer.path("AvailableQuantity");
        if (cantidad.isNumber()) return cantidad.asDouble() > 0;
        JsonNode disponible = offer.path("IsAvailable");
        return disponible.isBoolean() ? disponible.asBoolean() : null;
    }

    /** Any offer in stock: in stock. Every offer explicitly out: sold out (row kept). Otherwise unknown. */
    private static Boolean enStock(JsonNode items) {
        boolean hayOfertas = false;
        boolean todasAgotadas = true;
        if (items.isArray()) {
            for (JsonNode item : items) {
                for (JsonNode seller : item.path("sellers")) {
                    hayOfertas = true;
                    Boolean enStock = ofertaEnStock(seller.path("commertialOffer"));
                    if (Boolean.TRUE.equals(enStock)) return true;
                    if (enStock == null) todasAgotadas = false;
                }
            }
        }
        return hayOfertas && todasAgotadas ? Boolean.FALSE : null;
    }

    private static String segmentoDeLaRutaMasProfunda(JsonNode cats) {
        if (!cats.isArray()) return "";
        String hoja = "";
        int profundidadMaxima = 0;
        for (JsonNode ruta : cats) {
            String[] segmentos = StringUtils.split(ruta.asText(""), '/');
            int profundidad = 0;
            String ultimo = "";
            for (String segmento : segmentos) {
                if (StringUtils.isBlank(segmento)) continue;
                profundidad++;
                ultimo = segmento;
            }
            if (profundidad > profundidadMaxima) {
                profundidadMaxima = profundidad;
                hoja = ultimo;
            }
        }
        return hoja;
    }

    private List<String> extraerTallesVtex(JsonNode prod) {
        JsonNode skuSpecs = prod.path("skuSpecifications");
        if (skuSpecs.isArray()) {
            for (JsonNode spec : skuSpecs) {
                String fname = spec.path("field").path("name").asText("").toLowerCase();
                if (esTalleField(fname)) {
                    List<String> talles = CatalogJson.textosNoVacios(spec.path("values"), "name");
                    if (!talles.isEmpty()) return talles;
                }
            }
        }

        JsonNode allSpecs = prod.path("allSpecifications");
        if (allSpecs.isArray()) {
            for (JsonNode s : allSpecs) {
                String name = s.asText("").toLowerCase();
                if (esTalleField(name)) {
                    List<String> talles = CatalogJson.textosNoVacios(prod.path(s.asText("")));
                    if (!talles.isEmpty()) return talles;
                }
            }
        }

        JsonNode items = prod.path("items");
        if (items.isArray()) {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : items) {
                JsonNode variations = item.path("variations");
                if (variations.isArray()) {
                    for (JsonNode v : variations) {
                        String fname = v.path("name").asText("").toLowerCase();
                        if (esTalleField(fname)) {
                            JsonNode vals = v.path("values");
                            if (vals.isArray()) {
                                for (JsonNode vv : vals) {
                                    String t = vv.asText("").trim();
                                    if (!t.isBlank()) seen.add(t);
                                }
                            }
                        }
                    }
                }
            }
            if (!seen.isEmpty()) return new ArrayList<>(seen);

            // Fallback: primer variation de primer item
            if (!items.isEmpty()) {
                JsonNode firstItem = items.get(0);
                JsonNode variations = firstItem.path("variations");
                if (variations.isArray() && !variations.isEmpty()) {
                    Set<String> fallback = new LinkedHashSet<>();
                    String firstField = variations.get(0).path("name").asText("");
                    for (JsonNode item : items) {
                        JsonNode itemVars = item.path("variations");
                        if (itemVars.isArray()) {
                            for (JsonNode v : itemVars) {
                                if (v.path("name").asText("").equals(firstField)) {
                                    JsonNode vals = v.path("values");
                                    if (vals.isArray()) {
                                        for (JsonNode vv : vals)
                                            fallback.add(vv.asText("").trim());
                                    }
                                }
                            }
                        }
                    }
                    if (!fallback.isEmpty()) return new ArrayList<>(fallback);
                }
            }
        }

        return List.of();
    }

    private boolean esTalleField(String name) {
        return name.contains("talle") || name.contains("size") || name.contains("talla")
                || name.contains("tamaño") || name.equals("medida");
    }

    private String extraerGeneroVtex(JsonNode prod, String nombre) {
        JsonNode specGroups = prod.path("specificationGroups");
        if (specGroups.isArray()) {
            for (JsonNode group : specGroups) {
                JsonNode specs = group.path("specifications");
                if (specs.isArray()) {
                    for (JsonNode spec : specs) {
                        String fname = spec.path("name").asText("").toLowerCase();
                        if (fname.contains("genero") || fname.contains("género")
                                || fname.equals("gender") || fname.equals("sexo")) {
                            JsonNode vals = spec.path("values");
                            if (vals.isArray() && !vals.isEmpty()) {
                                String val = vals.get(0).asText("").toLowerCase();
                                return mapearGenero(val);
                            }
                        }
                    }
                }
            }
        }

        JsonNode allSpecs = prod.path("allSpecifications");
        if (allSpecs.isArray()) {
            for (JsonNode s : allSpecs) {
                String name = s.asText("").toLowerCase();
                if (name.contains("genero") || name.contains("género")
                        || name.equals("gender") || name.equals("sexo")) {
                    JsonNode vals = prod.path(s.asText(""));
                    if (vals.isArray() && !vals.isEmpty()) {
                        return mapearGenero(vals.get(0).asText("").toLowerCase());
                    }
                }
            }
        }

        List<String> fuentes = new ArrayList<>();
        fuentes.add(nombre.toLowerCase());
        JsonNode cats = prod.path("categories");
        if (cats.isArray()) {
            for (JsonNode c : cats) fuentes.add(c.asText("").toLowerCase());
        }

        return CatalogJson.genero(fuentes, PALABRAS_HOMBRE, PALABRAS_MUJER, PALABRAS_UNISEX);
    }

    private String mapearGenero(String val) {
        if (PALABRAS_UNISEX.stream().anyMatch(val::contains)) return "unisex";
        if (CatalogJson.tienePalabra(val, PALABRAS_HOMBRE)) return "hombre";
        if (PALABRAS_MUJER.stream().anyMatch(val::contains))  return "mujer";
        return "";
    }
}
