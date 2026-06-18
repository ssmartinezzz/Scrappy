package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.*;

/**
 * Scraper para tiendas VTEX usando la API pública de catálogo.
 *
 * Endpoint: GET /api/catalog_system/pub/products/search
 * Paginación: headers _from y _to (bloques de 50, máx 2500 por endpoint)
 *
 * Estrategia de género: VTEX expone specificationGroups con
 * "Género"/"Gender"/"Genero" como especificación de producto.
 * Si no está, se hace heurística sobre nombre + categorías.
 *
 * Talles: specifications con nombre "Talle"/"Size"/"Tamaño" o
 * los skuSpecifications de cada SKU.
 */
public class VtexPage extends BasePage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PRODUCTS = 2500;

    private static final Set<String> PALABRAS_HOMBRE = Set.of(
            "hombre","hombres","masculino","men","man","male","caballero");
    private static final Set<String> PALABRAS_MUJER = Set.of(
            "mujer","mujeres","femenino","women","woman","female","dama","damas");
    private static final Set<String> PALABRAS_UNISEX = Set.of(
            "unisex","unisexo","neutro");

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public VtexPage(Page page, int timeoutMs,
                    String sitio, String baseUrl,
                    double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio    = sitio;
        this.baseUrl  = baseUrl;
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    public List<Product> scrapeAll() {
        String dom = domain(baseUrl);

        // Paso 1: navegar al homepage para establecer sesion y cerrar popup de zona
        prepararSesion(dom);

        // Paso 2: intentar API Legacy primero (Sporting y similares)
        List<Product> result = scrapeApiLegacy(dom);

        // Paso 3: si legacy devuelve vacío, intentar VTEX IO Intelligent Search (Vaypol y similares)
        if (result.isEmpty()) {
            log.debug("[{}] Legacy API vacia, intentando VTEX IO Intelligent Search", sitio);
            result = scrapeApiIO(dom);
        }

        return result;
    }

    /**
     * Navega al homepage, espera y cierra cualquier popup de zona/localidad.
     * VTEX stores con region-based catalog requieren esto para que la API devuelva productos.
     */
    private void prepararSesion(String dom) {
        try {
            navigateTo(dom);
            page.waitForTimeout(1500);

            // Intentar cerrar popup de zona con diferentes selectores comunes
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

            // Intentar también hacer click en "Continuar" o "Aceptar"
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
     * Extrae el "linkText" de la URL canónica almacenada, que tiene la forma
     * "{dom}/{linkText}/p" (ver fromVtex/fromVtexIO, donde se construye igual).
     */
    public String extractLinkText(String url) {
        if (url == null) return "";
        String dom = domain(baseUrl);
        String prefix = dom + "/";
        if (!url.startsWith(prefix)) return "";
        String rest = url.substring(prefix.length());
        if (rest.endsWith("/p")) rest = rest.substring(0, rest.length() - "/p".length());
        // Quitar posible query string / fragment
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        int h = rest.indexOf('#');
        if (h >= 0) rest = rest.substring(0, h);
        return rest;
    }

    /**
     * Re-scrapea un único producto a partir de su URL canónica.
     * Intenta primero la API Legacy (path form, igual shape que fromVtex) y,
     * si devuelve vacío/no-array, hace fallback a VTEX IO Intelligent Search.
     */
    public Optional<Product> scrapeOne(String url) {
        String dom = domain(baseUrl);
        prepararSesion(dom);
        String linkText = extractLinkText(url);
        if (linkText.isBlank()) return Optional.empty();

        // 1. Legacy path form
        try {
            String apiUrl = dom + "/api/catalog_system/pub/products/search/" + linkText + "/p";
            navigateTo(apiUrl);
            String body = (String) page.evaluate("document.body.innerText");
            if (body != null && !body.isBlank() && body.trim().startsWith("[")) {
                JsonNode arr = MAPPER.readTree(body.trim());
                if (arr.isArray() && !arr.isEmpty()) {
                    return fromVtex(arr.get(0), dom);
                } else {
                    log.debug("[{}] scrapeOne legacy vacio/no-array para linkText={}, body={}",
                            sitio, linkText, truncar(body));
                }
            } else {
                log.debug("[{}] scrapeOne legacy body no-array para linkText={}, body={}",
                        sitio, linkText, truncar(body));
            }
        } catch (Exception e) {
            log.warn("[{}] scrapeOne legacy error linkText={}: {}", sitio, linkText, e.getMessage());
        }

        // 2. Fallback VTEX IO Intelligent Search
        try {
            String ioUrl = dom + "/api/io/_v/api/intelligent-search/product_search/trade-policy/1"
                    + "?query=" + linkText + "&count=1";
            navigateTo(ioUrl);
            String body = (String) page.evaluate("document.body.innerText");
            if (body == null || body.isBlank() || !body.trim().startsWith("{")) {
                log.debug("[{}] scrapeOne IO fallback sin respuesta valida para linkText={}", sitio, linkText);
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(body.trim());
            JsonNode prods = root.path("products");
            if (!prods.isArray() || prods.isEmpty()) {
                log.debug("[{}] scrapeOne IO fallback sin productos para linkText={}", sitio, linkText);
                return Optional.empty();
            }
            return fromVtexIO(prods.get(0), dom);
        } catch (Exception e) {
            log.warn("[{}] scrapeOne IO fallback error linkText={}: {}", sitio, linkText, e.getMessage());
            return Optional.empty();
        }
    }

    private String truncar(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** VTEX Legacy catalog API — funciona para Sporting y la mayoría de tiendas VTEX tradicionales */
    private List<Product> scrapeApiLegacy(String dom) {
        List<Product> result = new ArrayList<>();
        int from = 0;
        while (from < MAX_PRODUCTS) {
            int to = from + PAGE_SIZE - 1;
            String apiUrl = dom + "/api/catalog_system/pub/products/search"
                    + "?_from=" + from + "&_to=" + to + "&O=OrderByReleaseDateDESC";
            log.debug("[{}] Legacy API from={}", sitio, from);
            try {
                navigateTo(apiUrl);
                String body = (String) page.evaluate("document.body.innerText");
                if (body == null || body.isBlank() || !body.trim().startsWith("[")) break;
                JsonNode arr = MAPPER.readTree(body);
                if (!arr.isArray() || arr.isEmpty()) break;
                for (JsonNode prod : arr) fromVtex(prod, dom).ifPresent(result::add);
                log.debug("[{}] Legacy from={}: {} acumulados", sitio, from, result.size());
                if (arr.size() < PAGE_SIZE) break;
                from += PAGE_SIZE;
            } catch (Exception e) {
                log.warn("[{}] Legacy error from={}: {}", sitio, from, e.getMessage());
                break;
            }
        }
        return result;
    }

    /**
     * VTEX IO Intelligent Search API — para tiendas headless como Vaypol.
     * Endpoint: /api/io/_v/api/intelligent-search/product_search/trade-policy/1
     * No requiere autenticacion. Paginacion por ?page=N&count=50.
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
                if (body == null || body.isBlank()) break;

                String trimmed = body.trim();
                // La respuesta IO es {"products":[...],"pagination":{...}}
                if (!trimmed.startsWith("{")) break;

                JsonNode root = MAPPER.readTree(trimmed);
                JsonNode prods = root.path("products");
                if (!prods.isArray() || prods.isEmpty()) break;

                for (JsonNode prod : prods) fromVtexIO(prod, dom).ifPresent(result::add);

                // Leer paginacion
                JsonNode pagination = root.path("pagination");
                if (!pagination.isMissingNode()) {
                    JsonNode last = pagination.path("last");
                    if (!last.isMissingNode()) {
                        lastPage = last.path("index").asInt(1);
                    }
                    // "count" = total de productos
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

    /**
     * Parser para respuesta de VTEX IO Intelligent Search.
     * La estructura es similar a Legacy pero con algunas diferencias en imágenes y specs.
     */
    private Optional<Product> fromVtexIO(JsonNode prod, String dom) {
        try {
            String nombre = prod.path("productName").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();

            String linkText = prod.path("linkText").asText("");
            String url = linkText.isBlank() ? "" : dom + "/" + linkText + "/p";

            // IO: imágenes en items[0].images[0].imageUrl
            String img = "";
            JsonNode items = prod.path("items");
            if (items.isArray() && !items.isEmpty()) {
                JsonNode images = items.get(0).path("images");
                if (images.isArray() && !images.isEmpty()) {
                    img = images.get(0).path("imageUrl").asText("");
                    // IO también puede tener imageUrls en un formato con query string
                    if (img.contains("?")) img = img.substring(0, img.indexOf("?"));
                }
            }

            // Precio: mismo que legacy
            OptionalDouble precio = OptionalDouble.empty();
            String precioCompare = "";
            if (items.isArray() && !items.isEmpty()) {
                for (JsonNode item : items) {
                    JsonNode sellers = item.path("sellers");
                    if (!sellers.isArray()) continue;
                    for (JsonNode seller : sellers) {
                        JsonNode offer = seller.path("commertialOffer");
                        double p = offer.path("Price").asDouble(0);
                        double pOrig = offer.path("ListPrice").asDouble(0);
                        if (p > 0) {
                            precio = OptionalDouble.of(p);
                            if (pOrig > p) precioCompare = String.valueOf((long) pOrig);
                            break;
                        }
                    }
                    if (precio.isPresent()) break;
                }
            }

            if (precio.isEmpty()) return Optional.empty();
            double p = precio.getAsDouble();
            if (p < precioMin || p > precioMax) return Optional.empty();

            // Categoría
            String categoria = "";
            JsonNode cats = prod.path("categories");
            if (cats.isArray() && !cats.isEmpty()) {
                String rawCat = cats.get(cats.size() - 1).asText("").trim();
                String[] parts = rawCat.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (!parts[i].isBlank()) { categoria = parts[i]; break; }
                }
            }

            // IO también puede tener categorías en categoryTree
            if (categoria.isBlank()) {
                JsonNode catTree = prod.path("categoryTree");
                if (catTree.isArray() && !catTree.isEmpty()) {
                    categoria = catTree.get(catTree.size() - 1).path("name").asText("").trim();
                }
            }

            // Género y talles — mismos métodos que legacy
            String genero = extraerGeneroVtex(prod, nombre);
            List<String> talles = extraerTallesVtex(prod);

            return Optional.of(new Product(
                    sitio, nombre, p,
                    precioCompare.isBlank() ? null : "$" + precioCompare,
                    url, img, categoria, genero, talles));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Product> fromVtex(JsonNode prod, String dom) {
        try {
            String nombre = prod.path("productName").asText("").trim();
            if (nombre.isBlank()) nombre = prod.path("name").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();

            // URL
            String linkText = prod.path("linkText").asText("");
            String url = linkText.isBlank() ? "" : dom + "/" + linkText + "/p";

            // Imagen: primer item, primera imagen
            String img = "";
            JsonNode items = prod.path("items");
            if (items.isArray() && !items.isEmpty()) {
                JsonNode firstItem = items.get(0);
                JsonNode images = firstItem.path("images");
                if (images.isArray() && !images.isEmpty()) {
                    img = images.get(0).path("imageUrl").asText("");
                }
            }

            // Precio: buscar en sellers del primer item
            OptionalDouble precio = OptionalDouble.empty();
            String precioCompare = "";

            if (items.isArray() && !items.isEmpty()) {
                for (JsonNode item : items) {
                    JsonNode sellers = item.path("sellers");
                    if (!sellers.isArray()) continue;
                    for (JsonNode seller : sellers) {
                        JsonNode offer = seller.path("commertialOffer");
                        double p = offer.path("Price").asDouble(0);
                        double pOrig = offer.path("ListPrice").asDouble(0);
                        if (p > 0) {
                            precio = OptionalDouble.of(p);
                            if (pOrig > p) precioCompare = String.valueOf((long) pOrig);
                            break;
                        }
                    }
                    if (precio.isPresent()) break;
                }
            }

            if (precio.isEmpty()) return Optional.empty();
            double p = precio.getAsDouble();
            if (p < precioMin || p > precioMax) return Optional.empty();

            // Categorías
            String categoria = "";
            JsonNode cats = prod.path("categories");
            if (cats.isArray() && !cats.isEmpty()) {
                // VTEX categories son strings tipo "/Ropa/Remeras/"
                String rawCat = cats.get(cats.size() - 1).asText("").trim();
                String[] parts = rawCat.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (!parts[i].isBlank()) { categoria = parts[i]; break; }
                }
            }

            // Género: specificationGroups
            String genero = extraerGeneroVtex(prod, nombre);

            // Talles: skuSpecifications del item
            List<String> talles = extraerTallesVtex(prod);

            return Optional.of(new Product(
                    sitio, nombre, p,
                    precioCompare.isBlank() ? null : "$" + precioCompare,
                    url, img, categoria, genero, talles));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------
    // Talles desde VTEX
    // ----------------------------------------------------------------
    private List<String> extraerTallesVtex(JsonNode prod) {
        // Estrategia 1: skuSpecifications a nivel producto
        JsonNode skuSpecs = prod.path("skuSpecifications");
        if (skuSpecs.isArray()) {
            for (JsonNode spec : skuSpecs) {
                String fname = spec.path("field").path("name").asText("").toLowerCase();
                if (esTalleField(fname)) {
                    JsonNode values = spec.path("values");
                    if (values.isArray() && !values.isEmpty()) {
                        List<String> talles = new ArrayList<>();
                        for (JsonNode v : values) {
                            String t = v.path("name").asText("").trim();
                            if (!t.isBlank()) talles.add(t);
                        }
                        if (!talles.isEmpty()) return talles;
                    }
                }
            }
        }

        // Estrategia 2: specifications dentro de allSpecifications
        JsonNode allSpecs = prod.path("allSpecifications");
        if (allSpecs.isArray()) {
            for (JsonNode s : allSpecs) {
                String name = s.asText("").toLowerCase();
                if (esTalleField(name)) {
                    JsonNode vals = prod.path(s.asText(""));
                    if (vals.isArray() && !vals.isEmpty()) {
                        List<String> talles = new ArrayList<>();
                        for (JsonNode v : vals) {
                            String t = v.asText("").trim();
                            if (!t.isBlank()) talles.add(t);
                        }
                        if (!talles.isEmpty()) return talles;
                    }
                }
            }
        }

        // Estrategia 3: items[].variations[]
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

    // ----------------------------------------------------------------
    // Género desde VTEX
    // ----------------------------------------------------------------
    private String extraerGeneroVtex(JsonNode prod, String nombre) {
        // 1. specificationGroups — buscar spec llamada "Género", "Genero", "Gender"
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

        // 2. allSpecifications con el mismo criterio
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

        // 3. Heurística sobre nombre y categorías
        List<String> fuentes = new ArrayList<>();
        fuentes.add(nombre.toLowerCase());
        JsonNode cats = prod.path("categories");
        if (cats.isArray()) {
            for (JsonNode c : cats) fuentes.add(c.asText("").toLowerCase());
        }

        for (String f : fuentes) {
            if (PALABRAS_UNISEX.stream().anyMatch(f::contains)) return "unisex";
        }
        boolean esH = fuentes.stream().anyMatch(f -> PALABRAS_HOMBRE.stream().anyMatch(f::contains));
        boolean esM = fuentes.stream().anyMatch(f -> PALABRAS_MUJER.stream().anyMatch(f::contains));
        if (esH && !esM) return "hombre";
        if (esM && !esH) return "mujer";
        if (esH)         return "unisex";
        return "";
    }

    private String mapearGenero(String val) {
        if (PALABRAS_UNISEX.stream().anyMatch(val::contains)) return "unisex";
        if (PALABRAS_HOMBRE.stream().anyMatch(val::contains)) return "hombre";
        if (PALABRAS_MUJER.stream().anyMatch(val::contains))  return "mujer";
        return "";
    }
}
