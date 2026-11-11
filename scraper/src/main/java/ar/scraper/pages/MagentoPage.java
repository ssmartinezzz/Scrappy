package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magento 2 storefront (Las Pepas). The listing is server-rendered: {@code li.product-item-info} cards, 12 per
 * page, {@code ?p=N}, and a page past the end has no cards. {@code baseUrl} plus {@code extraUrls} are catalogue
 * URLs, each paged until a page has no cards (or repeats the previous one).
 *
 * <p>The same product is listed under several catalogue paths ({@code /ss27/ver-todo/x-09} and
 * {@code /shop/carteras/ver-todo-carteras/x-09}), so the crawl dedups on the last path segment and keeps the first
 * url, not on the url the upsert keys on.
 */
public class MagentoPage extends StorePage implements CatalogPage {

    private static final String MARCADOR_TARJETA = "<li class=\"item product";
    private static final Pattern ENLACE = Pattern.compile(
            "<a class=\"product-item-link\" href=\"([^\"]+)\"[^>]*>([^<]*)</a>");
    private static final Pattern PRECIO = Pattern.compile(
            "<[a-z]+[^>]*data-price-type=\"(finalPrice|oldPrice)\"[^>]*>");
    private static final Pattern MONTO = Pattern.compile("data-price-amount=\"([0-9.]+)\"");
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>");
    private static final Pattern ATRIBUTO_SRC = Pattern.compile("\\ssrc=\"([^\"]+)\"");
    private static final Pattern ATRIBUTO_DATA_SRC = Pattern.compile("\\sdata-src=\"([^\"]+)\"");
    // The dog line is named for its target ("PARA PERROS") or its brand word ("GUAU"); a print that merely says
    // "perro" on a blouse is a human item.
    private static final Pattern MASCOTA = Pattern.compile("(?iu)\\bguau\\b|\\bpara perros?\\b");
    private static final Pattern PARA_NINOS = Pattern.compile("(?iu)\\bpara ni(?:ñ|n)(?:a|o)s\\b");
    private static final String RUTA_MASCOTAS = "/shop/pets/";
    private static final String RUTA_INFANTIL = "/pepas-mini/";

    private final List<String> extraUrls;
    private final int maxPaginas;

    /** {@code tarjetas} counts every card on the page, before the price band and the pets filter. */
    record Listado(List<Product> productos, int tarjetas) {}

    public MagentoPage(Page page, int timeoutMs, String sitio, String baseUrl,
                       double precioMin, double precioMax, List<String> extraUrls, int maxPaginas) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
        this.extraUrls = extraUrls == null ? List.of() : extraUrls;
        this.maxPaginas = maxPaginas;
    }

    @Override
    public List<Product> scrapeAll() {
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (String catalogo : catalogos()) {
            int antes = result.size();
            crawlCatalogo(catalogo, url -> {
                navigateConReintentos(url);
                return page.content();
            }, vistas, result);
            log.info("[{}] Magento {} -> {} nuevos ({} acumulados)", sitio, catalogo, result.size() - antes, result.size());
        }
        return result;
    }

    List<String> catalogos() {
        Set<String> urls = new LinkedHashSet<>();
        urls.add(baseUrl);
        extraUrls.stream().map(StorePage::sinBarraFinal).forEach(urls::add);
        return List.copyOf(urls);
    }

    /** Pages one catalogue into {@code result}; {@code htmlDe} fetches a page's html by url. */
    void crawlCatalogo(String catalogo, Function<String, String> htmlDe, Set<String> vistas, List<Product> result) {
        Set<String> paginaPrevia = Set.of();
        for (int p = 1; p <= maxPaginas; p++) {
            Listado l;
            Set<String> urlsDeLaPagina;
            try {
                String html = htmlDe.apply(p == 1 ? catalogo : catalogo + "?p=" + p);
                l = parseListado(html, sitio, precioMin, precioMax);
                urlsDeLaPagina = urlsDeTarjetas(html);
            } catch (RuntimeException e) {
                if (p == 1) throw e;
                registrarTruncamiento(p, e);
                return;
            }
            if (l.tarjetas() == 0) {
                if (p == 1) throw new MagentoPayloadException(catalogo);
                return;
            }
            if (urlsDeLaPagina.equals(paginaPrevia)) return;
            paginaPrevia = urlsDeLaPagina;
            l.productos().stream().filter(prod -> vistas.add(slug(prod.url()))).forEach(result::add);
        }
    }

    static Listado parseListado(String html, String sitio, double precioMin, double precioMax) {
        if (StringUtils.isBlank(html)) return new Listado(List.of(), 0);
        List<String> tarjetas = cards(html, Pattern.quote(MARCADOR_TARJETA));
        List<Product> productos = new ArrayList<>();
        for (String card : tarjetas) {
            Matcher enlace = ENLACE.matcher(card);
            if (!enlace.find()) continue;
            String url = enlace.group(1).trim();
            String nombre = HtmlUtils.htmlUnescape(enlace.group(2)).trim();
            if (url.isEmpty() || nombre.isEmpty() || esMascota(url, nombre)) continue;

            Double precio = monto(card, "finalPrice");
            if (precio == null || precio <= 0 || precio < precioMin || precio > precioMax) continue;
            Double anterior = monto(card, "oldPrice");

            productos.add(Product.builder()
                    .sitio(sitio)
                    .nombre(nombre)
                    .precio(precio)
                    .precioOriginal(anterior != null && anterior > precio ? anterior : null)
                    .url(url)
                    .imagenUrl(imagen(card))
                    .categoria("")
                    .genero(esInfantil(url, nombre) ? "infantil" : "mujer")
                    .talles(List.of())
                    .build());
        }
        return new Listado(productos, tarjetas.size());
    }

    private static Set<String> urlsDeTarjetas(String html) {
        Set<String> urls = new LinkedHashSet<>();
        for (String card : cards(StringUtils.defaultString(html), Pattern.quote(MARCADOR_TARJETA))) {
            Matcher m = ENLACE.matcher(card);
            if (m.find()) urls.add(m.group(1).trim());
        }
        return urls;
    }

    // The kids line has its own catalogue and its products' urls carry it; names are a weaker signal because
    // "mini" also names adult items (mini skirt, mini bag) and not every kids item says "para niñas".
    private static boolean esInfantil(String url, String nombre) {
        return url.contains(RUTA_INFANTIL) || PARA_NINOS.matcher(nombre).find();
    }

    private static boolean esMascota(String url, String nombre) {
        return url.contains(RUTA_MASCOTAS) || MASCOTA.matcher(nombre).find();
    }

    private static Double monto(String card, String tipo) {
        Matcher m = PRECIO.matcher(card);
        while (m.find()) {
            if (!tipo.equals(m.group(1))) continue;
            Matcher monto = MONTO.matcher(m.group(0));
            if (!monto.find()) return null;
            try {
                return Double.parseDouble(monto.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The gallery's first slide has the real {@code src}; the rest are lazy ({@code src} is a data: gif). */
    private static String imagen(String card) {
        Matcher imgs = IMG.matcher(card);
        while (imgs.find()) {
            String tag = imgs.group();
            if (!tag.contains("product-image-photo")) continue;
            Optional<String> real = valor(ATRIBUTO_SRC, tag).filter(s -> !s.startsWith("data:"))
                    .or(() -> valor(ATRIBUTO_DATA_SRC, tag));
            if (real.isPresent()) return real.get();
        }
        return "";
    }

    private static Optional<String> valor(Pattern atributo, String tag) {
        Matcher m = atributo.matcher(tag);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    static String slug(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
