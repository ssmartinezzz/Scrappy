package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;

import java.util.function.ToIntFunction;
import java.util.*;
import org.apache.commons.lang3.StringUtils;

/** Si no está, se hace heurística sobre nombre + categorías. */
public class VtexPage extends StorePage implements CatalogPage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_SIZE = 50;
    /** Techo del path IO (Vaypol y similares) — sin cambios en este fix. */
    private static final int MAX_PRODUCTS = 2500;

    /** Techo sólo si el header {@code resources} nunca llega. */
    private static final int PAGINAS_SEGURIDAD_SIN_HEADER = 400;
    static final int VENTANA_LEGACY = 2500;
    private static final int REINTENTOS = 3;
    private static final long ESPERA_REINTENTO_MS = 2_000;
    private static final int PROFUNDIDAD_ARBOL = 3;

    private static final Set<String> PALABRAS_HOMBRE = Set.of(
            "hombre","hombres","masculino","men","man","male","caballero");
    private static final Set<String> PALABRAS_MUJER = Set.of(
            "mujer","mujeres","femenino","women","woman","female","dama","damas");
    private static final Set<String> PALABRAS_UNISEX = Set.of(
            "unisex","unisexo","neutro");

    public VtexPage(Page page, int timeoutMs,
                    String sitio, String baseUrl,
                    double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax);
    }

    public List<Product> scrapeAll() {
        String dom = domain(baseUrl);

        prepararSesion(dom);

        List<Product> result = scrapeApiLegacy(dom);

        if (result.isEmpty()) {
            log.debug("[{}] Legacy API vacia, intentando VTEX IO Intelligent Search", sitio);
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
            for (Product p : crawlLegacy(dom, fq)) {
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
                APIResponse resp = page.request().get(apiUrl);
                boolean ok;
                int status;
                OptionalInt totalDeEstaPagina;
                String body;
                try {
                    ok = resp.ok();
                    status = resp.status();
                    totalDeEstaPagina = parseResourcesTotal(resp.headers());
                    body = ok ? resp.text() : null;
                } finally {
                    resp.dispose();
                }
                if (!ok) {
                    if (reintentos < REINTENTOS) {
                        reintentos++;
                        page.waitForTimeout(ESPERA_REINTENTO_MS * reintentos);
                        log.debug("[{}] Legacy status HTTP {} en from={}, reintento {}",
                                sitio, status, from, reintentos);
                        continue;
                    }
                    log.warn("[{}] Legacy corta en from={}: status HTTP {}", sitio, from, status);
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
            } catch (Exception e) {
                if (reintentos < REINTENTOS) {
                    reintentos++;
                    page.waitForTimeout(ESPERA_REINTENTO_MS * reintentos);
                    log.debug("[{}] Legacy error transitorio en from={}, reintento {}: {}",
                            sitio, from, reintentos, e.getMessage());
                    continue;
                }
                log.warn("[{}] Legacy corta en from={}: {}", sitio, from, e.getMessage());
                break;
            }
        }
        return result;
    }

    record CategoriaVtex(int id, List<CategoriaVtex> hijos) { }

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
            if (n.hasNonNull("id")) out.add(new CategoriaVtex(n.get("id").asInt(), parseArbol(n.path("children"))));
        }
        return out;
    }

    private OptionalInt totalLegacy(String dom, String path) {
        APIResponse resp = page.request().get(dom + "/api/catalog_system/pub/products/search?_from=0&_to=0&fq=C:" + path);
        try {
            return resp.ok() ? parseResourcesTotal(resp.headers()) : OptionalInt.empty();
        } finally {
            resp.dispose();
        }
    }

    private String getText(String url) {
        APIResponse resp = page.request().get(url);
        try {
            if (!resp.ok()) throw new IllegalStateException("HTTP " + resp.status() + " en " + url);
            return resp.text();
        } finally {
            resp.dispose();
        }
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
                navigateTo(apiUrl);
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
                log.warn("[{}] IO error page={}: {}", sitio, page_num, e.getMessage());
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

            String linkText = prod.path("linkText").asText("");
            String url = linkText.isBlank() ? "" : dom + "/" + linkText + "/p";

            JsonNode items = prod.path("items");
            String img = primeraImagen(items);
            if (io) img = StringUtils.substringBefore(img, "?");

            Optional<Oferta> oferta = primeraOferta(items);
            if (oferta.isEmpty()) return Optional.empty();
            double p = oferta.get().precio();
            if (fueraDeRango(p)) return Optional.empty();

            String categoria = ultimoSegmento(prod.path("categories"));
            if (io && categoria.isBlank()) {
                JsonNode catTree = prod.path("categoryTree");
                if (catTree.isArray() && !catTree.isEmpty()) {
                    categoria = catTree.get(catTree.size() - 1).path("name").asText("").trim();
                }
            }

            return Optional.of(producto(nombre, p, oferta.get().original(), url, img, categoria,
                    extraerGeneroVtex(prod, nombre), extraerTallesVtex(prod)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private record Oferta(double precio, Double original) {}

    private static String primeraImagen(JsonNode items) {
        if (!items.isArray() || items.isEmpty()) return "";
        JsonNode images = items.get(0).path("images");
        if (!images.isArray() || images.isEmpty()) return "";
        return images.get(0).path("imageUrl").asText("");
    }

    private static Optional<Oferta> primeraOferta(JsonNode items) {
        if (!items.isArray()) return Optional.empty();
        for (JsonNode item : items) {
            JsonNode sellers = item.path("sellers");
            if (!sellers.isArray()) continue;
            for (JsonNode seller : sellers) {
                JsonNode offer = seller.path("commertialOffer");
                double p = offer.path("Price").asDouble(0);
                double pOrig = offer.path("ListPrice").asDouble(0);
                if (p > 0) return Optional.of(new Oferta(p, pOrig > p ? pOrig : null));
            }
        }
        return Optional.empty();
    }

    private static String ultimoSegmento(JsonNode cats) {
        if (!cats.isArray() || cats.isEmpty()) return "";
        String[] parts = cats.get(cats.size() - 1).asText("").trim().split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return "";
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
        if (PALABRAS_HOMBRE.stream().anyMatch(val::contains)) return "hombre";
        if (PALABRAS_MUJER.stream().anyMatch(val::contains))  return "mujer";
        return "";
    }
}
