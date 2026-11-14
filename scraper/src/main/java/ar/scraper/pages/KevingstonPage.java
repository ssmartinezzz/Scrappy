package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kevingston is a custom PHP storefront, not VTEX. Its listing is a form-encoded {@code POST
 * /api/v1/products} that answers {@code {code, msg, data:{page, per_page, count_page, count_datatotal,
 * data[]}}}, 36 rows per page. An empty {@code category} returns the whole catalogue; each row says
 * which section it belongs to in {@code idcategory} (1 = hombres, 2 = mujer), which is the gender hint.
 * {@code baseUrl} is the storefront root.
 */
public class KevingstonPage extends StorePage implements CatalogPage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_PATH = "/api/v1/products";
    private static final int MAX_PAGINAS = 100;
    private static final String FETCH_POST = """
            (a) => fetch(a.url, {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                          'X-Requested-With': 'XMLHttpRequest'},
                body: a.body
            }).then(r => r.text())""";
    private static final Pattern IMG_DATA_SRC = Pattern.compile("<img[^>]*?\\sdata-src=\"([^\"]+)\"");
    private static final Pattern IMG_SRC = Pattern.compile("<img[^>]*?\\ssrc=\"([^\"]+)\"");

    /** {@code crudos} counts every row the API sent, before stock and price band, to compare against {@code total}. */
    record Listado(List<Product> productos, int total, int crudos, int paginas) {}

    public KevingstonPage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
    }

    @Override
    public List<Product> scrapeAll() {
        navigateTo(baseUrl);
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        int crudos = 0;
        int total = 0;
        for (int p = 1; p <= MAX_PAGINAS; p++) {
            Listado l = parseListado((String) page.evaluate(FETCH_POST,
                    Map.of("url", baseUrl + API_PATH, "body", cuerpo(p))), sitio, precioMin, precioMax);
            total = l.total();
            crudos += l.crudos();
            l.productos().stream().filter(prod -> vistas.add(prod.url())).forEach(result::add);
            log.debug("[{}] Kevingston p{}/{}: {}/{} crudos, {} acumulados", sitio, p, l.paginas(), crudos, total,
                    result.size());
            if (p >= l.paginas()) break;
        }
        verificarCompleto(crudos, total);
        return result;
    }

    static String cuerpo(int pagina) {
        return "page=" + pagina + "&category=&type=&linea=&condition=&order=&promotion=&price_from=&price_to="
                + "&group_dto=&is_bomba=0&subcategory=&porcentaje=&idcolor=&search=";
    }

    static void verificarCompleto(int crudos, int total) {
        if (crudos < total)
            throw new KevingstonPayloadException("llegaron " + crudos + " de " + total + " productos");
    }

    static Listado parseListado(String json, String sitio, double precioMin, double precioMax) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new KevingstonPayloadException("no es JSON: " + prefijo(json));
        }
        JsonNode datos = root == null ? null : root.path("data");
        JsonNode filas = datos == null ? null : datos.path("data");
        // The API sends these counters as strings ("1105"), so read them as text.
        int total = datos == null ? 0 : datos.path("count_datatotal").asInt(0);
        int paginas = datos == null ? 0 : datos.path("count_page").asInt(0);
        if (filas == null || !filas.isArray() || total <= 0 || paginas <= 0)
            throw new KevingstonPayloadException("sin 'count_datatotal'/'data': " + prefijo(json));
        if (filas.isEmpty())
            throw new KevingstonPayloadException("pagina vacia (total=" + total + ", paginas=" + paginas + ")");

        List<Product> productos = new ArrayList<>();
        for (JsonNode n : filas) {
            double precio = n.path("precio").asDouble(0);
            String nombre = n.path("nombre").asText("").trim();
            String url = n.path("url_completa").asText("").trim();
            if (precio <= 0 || precio < precioMin || precio > precioMax
                    || nombre.isEmpty() || url.isEmpty()) continue;

            double lista = n.path("preciomax").asDouble(0);
            productos.add(Product.builder()
                    .sitio(sitio)
                    .nombre(nombre)
                    .precio(precio)
                    .precioOriginal(lista > precio ? lista : null)
                    .url(url)
                    .imagenUrl(imagen(n))
                    .categoria("")
                    .genero(genero(n.path("idcategory").asText("")))
                    .talles(List.of())
                    .enStock(enStock(n))
                    .build());
        }
        return new Listado(productos, total, filas.size(), paginas);
    }

    /**
     * Only an explicit 0 is out of stock; 34 of 1548 rows carry {@code "stock": null}, which the
     * site still lists for sale but is no stock signal, so it stays unknown (null).
     */
    private static Boolean enStock(JsonNode n) {
        JsonNode stock = n.path("stock");
        if (stock.isNull() || stock.isMissingNode()) return null;
        return stock.asInt(0) > 0;
    }

    private static String genero(String idCategoria) {
        return switch (idCategoria) {
            case "1" -> "hombre";
            case "2" -> "mujer";
            default -> "";
        };
    }

    /** {@code image} is null on some rows; the card html then has the photo, lazy-loaded in {@code data-src}. */
    private static String imagen(JsonNode n) {
        String directa = n.path("image").asText("");
        if (!directa.isBlank()) return directa;
        String html = n.path("html").asText("");
        Matcher m = IMG_DATA_SRC.matcher(html);
        if (m.find()) return m.group(1);
        m = IMG_SRC.matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private static String prefijo(String s) {
        return s == null ? "" : s.substring(0, Math.min(120, s.length()));
    }
}
