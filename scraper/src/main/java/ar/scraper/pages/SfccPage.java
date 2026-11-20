package ar.scraper.pages;

import ar.scraper.model.Product;
import ar.scraper.model.SitioCategoriaNodo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Salesforce Commerce Cloud (Demandware) storefront: Dexter and Moov. The grid controller
 * {@code Search-UpdateGrid} answers bare product tiles, so the whole catalogue is one paged listing of the
 * {@code root} category ({@code sz} tiles per request, a page past the end is empty). The controller path carries
 * the SFCC site id ({@code Sites-Dexter-Site}), read from the home page instead of configured per site.
 *
 * <p>A tile has no category, but its tracking payload ({@code productGtmData}) lists up to five category ids, mixed
 * with campaign ones ({@code cyberweek}, {@code marcas-nike}). The taxonomy ones read
 * {@code <hombre|mujer|categorias-infantil-nino|-nina>-<calzado|indumentaria|accesorios>[-<hoja>]}: the leaf
 * ({@code calzado-zapatillas}) is the site category, the gender prefix is the gender. A product listed under both
 * adult genders is unisex. About 7% of the tiles lose their taxonomy id to the five-id cut and keep an empty leaf.
 *
 * <p>The tree origin ids are a hash of the leaf key, stable across runs because {@link String#hashCode()} is
 * specified.
 */
public class SfccPage extends StorePage implements CatalogPage, ArbolDeCategorias {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int POR_PAGINA = 100;
    private static final int MAX_PAGINAS = 200;
    private static final int PAUSA_MS = 300;
    // Paging the default relevance order lost ~20 of 9,940 tiles to rank shuffles between requests; price is stable.
    private static final String ORDEN = "lowest-price";
    // 2% slack: the count is read in a separate request and a product can go out of stock between the two.
    private static final double TOLERANCIA = 0.98;
    private static final String FETCH_TEXTO = "(u) => fetch(u).then(r => r.ok ? r.text() : '')";
    private static final String MARCADOR_TARJETA = "<div class=\"product\" data-pid=\"";

    private static final Pattern SITE_ID = Pattern.compile("Sites-([A-Za-z0-9_]+)-Site");
    private static final Pattern TOTAL = Pattern.compile("class=\"count\">\\s*(\\d+)");
    private static final Pattern PID = Pattern.compile("^([^\"]+)\"");
    private static final Pattern ENLACE = Pattern.compile("<a class=\"link\" href=\"([^\"]+)\"[^>]*>([^<]*)</a>");
    private static final Pattern IMAGEN = Pattern.compile("<img class=\"tile-image[^\"]*\"[^>]*?\\ssrc=\"([^\"]+)\"");
    private static final Pattern VENTA = Pattern.compile("class=\"sales\">\\s*<span class=\"value\" content=\"([0-9.]+)\"");
    private static final Pattern TACHADO = Pattern.compile(
            "class=\"strike-through list\">\\s*<span class=\"value\" content=\"([0-9.]+)\"");
    private static final Pattern GTM = Pattern.compile("productGtmData\"[^>]*?value='(.*?)'\\s*/>", Pattern.DOTALL);
    private static final Pattern TAXONOMIA = Pattern.compile(
            "^(hombre|mujer|categorias-infantil-nino|categorias-infantil-nina)-(calzado|indumentaria|accesorios)(?:-(.+))?$");
    private static final String[] CLAVES_CATEGORIA = {
            "item_category", "item_category2", "item_category3", "item_category4", "item_category5"};

    /** {@code tarjetas} counts every tile on the page, before the price band. */
    record Listado(List<Tile> tiles, int tarjetas) {}

    /** A parsed tile: the product plus the taxonomy leaf it was listed under, null when the five ids had none. */
    record Tile(String pid, Product producto, String hoja, Set<String> generos) {}

    private final Map<String, String> tipoDeHoja = new LinkedHashMap<>();

    public SfccPage(Page page, int timeoutMs, String sitio, String baseUrl, double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
    }

    @Override
    public List<Product> scrapeAll() {
        limpiarTruncamiento();
        tipoDeHoja.clear();
        navigateTo(baseUrl);
        String siteId = siteId(page.content());
        int total = total(texto(controlador(siteId, "Search-Show") + "?cgid=root"));

        List<Product> result = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        int crudos = 0;
        for (int p = 0; p < MAX_PAGINAS; p++) {
            Listado l;
            try {
                l = parseListado(texto(urlGrilla(siteId, p * POR_PAGINA)), sitio, baseUrl, precioMin, precioMax);
            } catch (RuntimeException e) {
                if (result.isEmpty()) throw e;
                registrarTruncamiento(p + 1, e);
                return result;
            }
            if (l.tarjetas() == 0) {
                if (p == 0) throw new SfccPayloadException("la primera página de " + siteId + " no trae productos");
                break;
            }
            crudos += l.tarjetas();
            acumular(l, vistos, result);
            log.debug("[{}] SFCC p{}: {}/{} tarjetas, {} acumulados", sitio, p + 1, crudos, total, result.size());
            page.waitForTimeout(PAUSA_MS);
        }
        log.info("[{}] SFCC {} productos distintos, {} tarjetas, {} anunciados", sitio, result.size(), crudos, total);
        if (total > 0 && crudos < total * TOLERANCIA)
            registrarTruncamiento(crudos / POR_PAGINA + 1, new SfccPayloadException("llegaron " + crudos + " de " + total + " productos"));
        return result;
    }

    /** The same master can be listed twice when the grid shifts between two requests: first one wins. */
    void acumular(Listado l, Set<String> vistos, List<Product> result) {
        for (Tile t : l.tiles()) {
            if (!vistos.add(t.pid())) continue;
            if (t.hoja() != null) tipoDeHoja.putIfAbsent(t.hoja(), tipo(t.hoja()));
            result.add(t.producto());
        }
    }

    private String texto(String url) {
        return StringUtils.defaultString((String) page.evaluate(FETCH_TEXTO, url));
    }

    static String siteId(String homeHtml) {
        Matcher m = SITE_ID.matcher(StringUtils.defaultString(homeHtml));
        if (!m.find()) throw new SfccPayloadException("la home no expone el id del sitio (Sites-<id>-Site)");
        return m.group(1);
    }

    /** 0 when the count is missing: the completeness check is skipped, the crawl itself does not depend on it. */
    static int total(String html) {
        Matcher m = TOTAL.matcher(StringUtils.defaultString(html));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String controlador(String siteId, String accion) {
        return baseUrl + "/on/demandware.store/Sites-" + siteId + "-Site/es_AR/" + accion;
    }

    String urlGrilla(String siteId, int inicio) {
        return controlador(siteId, "Search-UpdateGrid") + "?cgid=root&srule=" + ORDEN + "&start=" + inicio + "&sz=" + POR_PAGINA;
    }

    static Listado parseListado(String html, String sitio, String baseUrl, double precioMin, double precioMax) {
        if (StringUtils.isBlank(html)) return new Listado(List.of(), 0);
        List<String> tarjetas = cards(html, Pattern.quote(MARCADOR_TARJETA));
        List<Tile> tiles = new ArrayList<>();
        for (String card : tarjetas) {
            Optional<Tile> tile = parseTarjeta(card, sitio, baseUrl, precioMin, precioMax);
            tile.ifPresent(tiles::add);
        }
        return new Listado(tiles, tarjetas.size());
    }

    private static Optional<Tile> parseTarjeta(String card, String sitio, String baseUrl, double precioMin, double precioMax) {
        Matcher pid = PID.matcher(card);
        Matcher enlace = ENLACE.matcher(card);
        Matcher venta = VENTA.matcher(card);
        if (!pid.find() || !enlace.find() || !venta.find()) return Optional.empty();
        String nombre = HtmlUtils.htmlUnescape(enlace.group(2)).trim();
        // a price range tile lists its lowest price first
        double precio = Double.parseDouble(venta.group(1));
        if (nombre.isEmpty() || precio <= 0 || precio < precioMin || precio > precioMax) return Optional.empty();

        Matcher tachado = TACHADO.matcher(card);
        Double anterior = tachado.find() ? Double.valueOf(tachado.group(1)) : null;
        Matcher imagen = IMAGEN.matcher(card);
        Set<String> generos = new LinkedHashSet<>();
        String hoja = taxonomia(card, generos);

        Product producto = Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(precio)
                .precioOriginal(anterior != null && anterior > precio ? anterior : null)
                .url(baseUrl + enlace.group(1))
                .imagenUrl(imagen.find() ? HtmlUtils.htmlUnescape(imagen.group(1)) : "")
                .categoria("")
                .genero(genero(generos))
                .talles(List.of())
                .marca("")
                .sitioCategoriaOrigenId(hoja == null ? null : idDeHoja(hoja))
                .build();
        return Optional.of(new Tile(pid.group(1), producto, hoja, generos));
    }

    /** The first taxonomy id wins the leaf; every taxonomy id contributes its gender. Null leaf when none matches. */
    private static String taxonomia(String card, Set<String> generos) {
        Matcher gtm = GTM.matcher(card);
        if (!gtm.find()) return null;
        JsonNode item;
        try {
            item = MAPPER.readTree(HtmlUtils.htmlUnescape(gtm.group(1))).path(0);
        } catch (JsonProcessingException e) {
            return null;
        }
        String hoja = null;
        for (String clave : CLAVES_CATEGORIA) {
            Matcher m = TAXONOMIA.matcher(item.path(clave).asText(""));
            if (!m.matches()) continue;
            generos.add(m.group(1).startsWith("categorias-infantil") ? "infantil" : m.group(1));
            if (hoja == null) hoja = m.group(2) + (m.group(3) == null ? "" : "-" + m.group(3));
        }
        return hoja;
    }

    static String genero(Set<String> generos) {
        if (generos.size() == 1) return generos.iterator().next();
        return generos.isEmpty() ? "" : "unisex";
    }

    public static int idDeHoja(String hoja) {
        return ("sfcc:" + hoja).hashCode() & Integer.MAX_VALUE;
    }

    private static String tipo(String hoja) {
        return StringUtils.substringBefore(hoja, "-");
    }

    @Override
    public List<SitioCategoriaNodo> arbolDeCategorias() {
        List<SitioCategoriaNodo> nodos = new ArrayList<>();
        Set<String> tipos = new LinkedHashSet<>(tipoDeHoja.values());
        for (String tipo : tipos) nodos.add(new SitioCategoriaNodo(idDeHoja(tipo), null, nombre(tipo), false));
        for (Map.Entry<String, String> e : tipoDeHoja.entrySet()) {
            if (tipos.contains(e.getKey()) && e.getKey().equals(e.getValue())) continue;
            nodos.add(new SitioCategoriaNodo(idDeHoja(e.getKey()), idDeHoja(e.getValue()),
                    nombre(StringUtils.substringAfter(e.getKey(), "-")), false));
        }
        return nodos;
    }

    private static String nombre(String slug) {
        return StringUtils.capitalize(slug);
    }
}
