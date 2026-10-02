package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.*;

public class ShopifyPage extends StorePage implements CatalogPage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ShopifyPage(Page page, int timeoutMs, String sitio, String baseUrl,
                       double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax);
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

            Optional<Double> precio = precioEnRango(v.path("price").asText(""));
            if (precio.isEmpty()) return Optional.empty();

            return Optional.of(producto(nombre, precio.get(), CatalogJson.precioComparado(v), url, img,
                    prod.path("product_type").asText("").trim(), detectarGenero(prod, nombre),
                    extraerTalles(prod, variants)));
        } catch (Exception e) { return Optional.empty(); }
    }

    private List<String> extraerTalles(JsonNode prod, JsonNode variants) {
        JsonNode options = prod.path("options");
        if (options.isArray()) {
            for (JsonNode opt : options) {
                String name = opt.path("name").asText("").toLowerCase();
                if (esTalleOption(name)) {
                    JsonNode vals = opt.path("values");
                    List<String> talles = CatalogJson.textosNoVacios(vals);
                    if (!talles.isEmpty()) return talles;
                }
            }
        }

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
            if (vals.isArray() && !vals.isEmpty()) return CatalogJson.textosNoVacios(vals);
        }

        return List.of();
    }

    private boolean esTalleOption(String name) {
        return name.contains("talle") || name.contains("size") || name.contains("talla")
                || name.equals("s") || name.equals("m") || name.equals("l");
    }

    private String detectarGenero(JsonNode prod, String nombre) {
        List<String> fuentes = new ArrayList<>();
        fuentes.add(prod.path("product_type").asText("").toLowerCase());
        fuentes.add(nombre.toLowerCase());
        CatalogJson.agregarTags(fuentes, prod.path("tags"));
        return CatalogJson.genero(fuentes);
    }
}
