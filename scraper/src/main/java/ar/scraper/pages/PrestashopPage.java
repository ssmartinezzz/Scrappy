package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PrestaShop 1.7 storefront (Armytech). The listing answers JSON when asked with
 * {@code X-Requested-With: XMLHttpRequest}; {@code baseUrl} is the listing itself
 * ({@code .../2-productos}), whose root category holds the whole catalogue.
 */
public class PrestashopPage extends StorePage implements CatalogPage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGINAS = 100;
    private static final String FETCH_JSON = """
            (u) => fetch(u, {headers: {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}})
                    .then(r => r.text())""";

    record Listing(List<Product> productos, int paginas) {}

    public PrestashopPage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
    }

    public List<Product> scrapeAll() {
        navigateTo(baseUrl);
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        int paginas = 1;
        for (int p = 1; p <= Math.min(paginas, MAX_PAGINAS); p++) {
            Listing listing = parseListing((String) page.evaluate(FETCH_JSON, baseUrl + "?page=" + p),
                    sitio, precioMin, precioMax);
            paginas = listing.paginas();
            listing.productos().stream().filter(prod -> vistas.add(prod.url())).forEach(result::add);
            log.debug("[{}] PrestaShop p{}/{}: {} acumulados", sitio, p, paginas, result.size());
        }
        if (result.isEmpty())
            throw new PrestashopPayloadException("0 productos en " + paginas + " paginas de " + baseUrl);
        return result;
    }

    static Listing parseListing(String json, String sitio, double precioMin, double precioMax) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new PrestashopPayloadException("no es JSON: " + prefijo(json));
        }
        JsonNode products = root == null ? null : root.path("products");
        if (products == null || !products.isArray())
            throw new PrestashopPayloadException("sin 'products': " + prefijo(json));

        List<Product> productos = new ArrayList<>();
        for (JsonNode n : products) {
            double precio = n.path("price_amount").asDouble(0);
            String nombre = n.path("name").asText("").trim();
            String url = n.path("url").asText("");
            // price 0 = brand placeholder rows ("Marca - Intel"); precio.minimo is 0, so the band alone keeps them
            if (precio <= 0 || precio < precioMin || precio > precioMax || nombre.isEmpty() || url.isEmpty()) continue;

            double regular = n.path("regular_price_amount").asDouble(0);
            Double precioOriginal = n.path("has_discount").asBoolean(false) && regular > precio ? regular : null;
            productos.add(tecnologia(sitio, nombre, precio, precioOriginal, url,
                    n.path("cover").path("large").path("url").asText(""),
                    n.path("category_name").asText("")));
        }
        return new Listing(productos, root.path("pagination").path("pages_count").asInt(1));
    }

    private static String prefijo(String s) {
        return s == null ? "" : s.substring(0, Math.min(120, s.length()));
    }
}
