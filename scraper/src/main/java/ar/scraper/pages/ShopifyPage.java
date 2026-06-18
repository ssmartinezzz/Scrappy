package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.*;

public class ShopifyPage extends BasePage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Tags / product_type que indican género
    private static final Set<String> PALABRAS_HOMBRE  = Set.of(
            "hombre","hombres","masculino","masculina","men","man","male","caballero","varones");
    private static final Set<String> PALABRAS_MUJER   = Set.of(
            "mujer","mujeres","femenino","femenina","women","woman","female","dama","damas");
    private static final Set<String> PALABRAS_UNISEX  = Set.of(
            "unisex","unisexo","neutro");

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public ShopifyPage(Page page, int timeoutMs, String sitio, String baseUrl,
                       double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio    = sitio;
        this.baseUrl  = baseUrl;
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    public List<Product> scrapeAll() {
        List<Product> result = new ArrayList<>();
        String dom = domain(baseUrl);

        for (int p = 1; p <= 20; p++) {
            log.debug("[{}] Shopify API p{}", sitio, p);
            try {
                navigateTo(dom + "/products.json?limit=250&page=" + p);
                String body = (String) page.evaluate("document.body.innerText");
                if (body == null || !body.contains("\"products\"")) break;

                JsonNode products = MAPPER.readTree(body).path("products");
                if (!products.isArray() || products.isEmpty()) break;

                for (JsonNode prod : products) fromJson(prod, dom).ifPresent(result::add);
                log.debug("[{}] p{}: {} acumulados", sitio, p, result.size());
                if (products.size() < 250) break;
            } catch (Exception e) {
                log.warn("[{}] error p{}: {}", sitio, p, e.getMessage());
                break;
            }
        }
        return result;
    }

    /**
     * Extrae el handle de producto de una URL Shopify, p.ej.
     * "https://dom.com/products/zapatilla-x?variant=123" -> "zapatilla-x"
     */
    public String extractHandle(String url) {
        if (url == null) return "";
        int idx = url.indexOf("/products/");
        if (idx < 0) return "";
        String rest = url.substring(idx + "/products/".length());
        int cut = rest.length();
        int q = rest.indexOf('?');
        if (q >= 0 && q < cut) cut = q;
        int h = rest.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        return rest.substring(0, cut);
    }

    /**
     * Re-scrapea un único producto a partir de su URL canónica, usando el
     * endpoint público "/products/{handle}.json" (wrapper singular "product").
     */
    public Optional<Product> scrapeOne(String url) {
        String dom = domain(baseUrl);
        String handle = extractHandle(url);
        if (handle.isBlank()) return Optional.empty();
        try {
            navigateTo(dom + "/products/" + handle + ".json");
            String body = (String) page.evaluate("document.body.innerText");
            if (body == null || !body.contains("\"product\"")) return Optional.empty();
            JsonNode prod = MAPPER.readTree(body).path("product");
            if (prod.isMissingNode()) return Optional.empty();
            return fromJson(prod, dom);
        } catch (Exception e) {
            log.warn("[{}] scrapeOne error url={}: {}", sitio, url, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Product> fromJson(JsonNode prod, String dom) {
        try {
            String nombre = prod.path("title").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();

            String url = dom + "/products/" + prod.path("handle").asText("");

            String img = "";
            JsonNode images = prod.path("images");
            if (images.isArray() && !images.isEmpty()) {
                img = images.get(0).path("src").asText("");
                img = img.replaceAll("_(\\d+x\\d*|small|compact|thumb|icon)\\.", "_800x.");
                if (img.startsWith("//")) img = "https:" + img;
            }

            JsonNode variants = prod.path("variants");
            if (!variants.isArray() || variants.isEmpty()) return Optional.empty();
            JsonNode v = variants.get(0);

            Optional<Double> precio = parsePrecio(v.path("price").asText(""));
            if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax) return Optional.empty();

            String compare = v.path("compare_at_price").asText("");
            if ("null".equals(compare)) compare = "";

            // --- Categoría: product_type ---
            String categoria = prod.path("product_type").asText("").trim();

            // --- Género: product_type + tags ---
            String genero = detectarGenero(prod, nombre);

            // --- Talles: opciones del producto ---
            List<String> talles = extraerTalles(prod, variants);

            return Optional.of(new Product(sitio, nombre, precio.get(),
                    compare.isBlank() ? null : "$" + compare,
                    url, img, categoria, genero, talles));
        } catch (Exception e) { return Optional.empty(); }
    }

    // ----------------------------------------------------------------
    // Extracción de talles
    // ----------------------------------------------------------------
    private List<String> extraerTalles(JsonNode prod, JsonNode variants) {
        // Estrategia 1: buscar en options[] el que se llame "talle", "size", etc.
        JsonNode options = prod.path("options");
        if (options.isArray()) {
            for (JsonNode opt : options) {
                String name = opt.path("name").asText("").toLowerCase();
                if (esTalleOption(name)) {
                    JsonNode vals = opt.path("values");
                    if (vals.isArray() && !vals.isEmpty()) {
                        List<String> talles = new ArrayList<>();
                        for (JsonNode val : vals) {
                            String t = val.asText("").trim();
                            if (!t.isBlank()) talles.add(t);
                        }
                        if (!talles.isEmpty()) return talles;
                    }
                }
            }
        }

        // Estrategia 2: todos los option1 de las variantes (primer option visible)
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode var : variants) {
            String opt1 = var.path("option1").asText("").trim();
            if (!opt1.isBlank() && !opt1.equalsIgnoreCase("default title")) {
                seen.add(opt1);
            }
        }
        if (!seen.isEmpty()) return new ArrayList<>(seen);

        // Estrategia 3: fallback — primer elemento del primer option[] visible
        if (options.isArray() && !options.isEmpty()) {
            JsonNode firstOpt = options.get(0);
            JsonNode vals = firstOpt.path("values");
            if (vals.isArray() && !vals.isEmpty()) {
                List<String> talles = new ArrayList<>();
                for (JsonNode val : vals) {
                    String t = val.asText("").trim();
                    if (!t.isBlank()) talles.add(t);
                }
                return talles;
            }
        }

        return List.of();
    }

    private boolean esTalleOption(String name) {
        return name.contains("talle") || name.contains("size") || name.contains("talla")
                || name.equals("s") || name.equals("m") || name.equals("l");
    }

    // ----------------------------------------------------------------
    // Detección de género
    // ----------------------------------------------------------------
    private String detectarGenero(JsonNode prod, String nombre) {
        // Fuentes: product_type, tags, título
        List<String> fuentes = new ArrayList<>();
        fuentes.add(prod.path("product_type").asText("").toLowerCase());
        fuentes.add(nombre.toLowerCase());
        JsonNode tags = prod.path("tags");
        if (tags.isArray()) {
            for (JsonNode t : tags) fuentes.add(t.asText("").toLowerCase());
        } else if (tags.isTextual()) {
            // A veces es un string separado por comas
            Arrays.stream(tags.asText("").split(","))
                    .map(String::trim).map(String::toLowerCase)
                    .forEach(fuentes::add);
        }

        for (String fuente : fuentes) {
            if (PALABRAS_UNISEX.stream().anyMatch(fuente::contains)) return "unisex";
        }
        boolean esHombre = fuentes.stream().anyMatch(f ->
                PALABRAS_HOMBRE.stream().anyMatch(f::contains));
        boolean esMujer  = fuentes.stream().anyMatch(f ->
                PALABRAS_MUJER.stream().anyMatch(f::contains));

        if (esHombre && !esMujer)  return "hombre";
        if (esMujer  && !esHombre) return "mujer";
        if (esHombre && esMujer)   return "unisex";
        return "";
    }
}
