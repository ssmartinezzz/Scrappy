package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gamer Factory is a custom React SPA; its catalogue comes from a public JSON API that answers
 * {@code {total, data[]}}. One request with a large {@code perPage} returns everything, so pagination
 * is only a safety net. {@code baseUrl} is the storefront, used for the product links.
 */
public class GamerFactoryPage extends StorePage implements CatalogPage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://core.gamerfactory.com.ar/products/search";
    private static final int POR_PAGINA = 1000;
    private static final int MAX_PAGINAS = 20;
    private static final String FETCH_JSON = "(u) => fetch(u).then(r => r.text())";

    /** {@code crudos} counts every row the API sent, before the price band, to compare against {@code total}. */
    record Search(List<Product> productos, int total, int crudos) {}

    public GamerFactoryPage(Page page, int timeoutMs, String sitio, String baseUrl,
                            double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
    }

    public List<Product> scrapeAll() {
        navigateTo(baseUrl);
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        int crudos = 0;
        int total = 0;
        for (int p = 1; p <= MAX_PAGINAS; p++) {
            Search s = parseSearch((String) page.evaluate(FETCH_JSON, searchUrl(p)),
                    sitio, baseUrl, precioMin, precioMax);
            total = s.total();
            crudos += s.crudos();
            s.productos().stream().filter(prod -> vistas.add(prod.url())).forEach(result::add);
            log.debug("[{}] Gamer Factory p{}: {}/{} crudos, {} acumulados", sitio, p, crudos, total, result.size());
            if (crudos >= total || s.crudos() == 0) break;
        }
        verificarCompleto(crudos, total);
        return result;
    }

    static String searchUrl(int pagina) {
        return API + "?filter=%7B%7D&page=" + pagina + "&perPage=" + POR_PAGINA + "&sort=%7B%7D";
    }

    static void verificarCompleto(int crudos, int total) {
        if (crudos < total)
            throw new GamerFactoryPayloadException("llegaron " + crudos + " de " + total + " productos");
    }

    static Search parseSearch(String json, String sitio, String baseUrl, double precioMin, double precioMax) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new GamerFactoryPayloadException("no es JSON: " + prefijo(json));
        }
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray() || !root.path("total").isInt())
            throw new GamerFactoryPayloadException("sin 'total'/'data': " + prefijo(json));
        int total = root.path("total").asInt();
        if (total <= 0 || data.isEmpty())
            throw new GamerFactoryPayloadException("catalogo vacio (total=" + total + ", data=" + data.size() + ")");

        List<Product> productos = new ArrayList<>();
        for (JsonNode n : data) {
            JsonNode especial = n.path("price").path("special");
            double precio = especial.path("ARS").asDouble(0);
            String nombre = n.path("name").asText("").trim();
            String slug = n.path("slug").asText("");
            if (precio <= 0 || precio < precioMin || precio > precioMax || nombre.isEmpty() || slug.isEmpty()) continue;

            // list vs. special is a payment method, not a discount: only a strikethrough above the price is one
            double tachado = especial.path("strikethrough").asDouble(0);
            productos.add(Product.builder()
                    .sitio(sitio)
                    .nombre(nombre)
                    .precio(precio)
                    .precioOriginal(tachado > precio ? tachado : null)
                    .url(sinBarraFinal(baseUrl) + "/producto/" + slug)
                    .imagenUrl(n.path("images").path(0).path("url").asText(""))
                    .categoria("")
                    .genero("")
                    .talles(List.of())
                    .marca(StringUtils.defaultString(n.path("brand").asText(null)).trim())
                    .rubro("tecnologia")
                    .build());
        }
        return new Search(productos, total, data.size());
    }

    private static String prefijo(String s) {
        return s == null ? "" : s.substring(0, Math.min(120, s.length()));
    }
}
