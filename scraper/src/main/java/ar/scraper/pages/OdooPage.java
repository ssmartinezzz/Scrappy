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
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Odoo {@code website_sale} storefront (Macstation, Gentech). The listing is server-rendered at {@code /shop}
 * and {@code /shop/page/N}; {@code ?ppg=} is ignored, so the whole catalogue is paged. A page past the end repeats
 * the last one instead of coming back empty, so the crawl stops at the page the pagination links announce, or at the
 * first page with nothing new.
 *
 * <p>Two themes are read by one parser: a card is a {@code form.oe_product_cart}; the name comes from
 * {@code itemprop="name"@content} (Macstation's visible title is shortened), then the title span, then the form's
 * {@code aria-label}; the price from {@code itemprop="price"} (numeric) or the first {@code oe_currency_value}
 * outside a {@code <del>} (locale formatted: {@code 105.900,00} in es-AR, {@code 43,900.00} in en); the list price
 * from a visible {@code <del>}.
 */
public class OdooPage extends StorePage implements CatalogPage {

    private static final int MAX_PAGINAS = 200;
    private static final int PAUSA_MS = 250;
    private static final String FETCH_TEXTO = "(u) => fetch(u).then(r => r.ok ? r.text() : '')";

    private static final Pattern INICIO_TARJETA = Pattern.compile("<form\\b[^>]*\\boe_product_cart\\b");
    private static final Pattern ETIQUETA_NOMBRE = Pattern.compile("<[a-z0-9]+\\b[^>]*\\bitemprop=\"name\"[^>]*>");
    private static final Pattern ATRIBUTO_CONTENT = Pattern.compile("\\scontent=\"([^\"]*)\"");
    private static final Pattern TITULO = Pattern.compile(
            "o_wsale_products_item_title[^>]*>.*?<span[^>]*>([^<]*)</span>", Pattern.DOTALL);
    private static final Pattern ARIA_DEL_FORM = Pattern.compile("^<form\\b[^>]*\\baria-label=\"([^\"]*)\"");
    // The language prefix (/en, /es_AR) is dropped: the url the upsert keys on must not depend on the request locale.
    private static final Pattern ENLACE = Pattern.compile(
            "<a\\b[^>]*\\bhref=\"(?:/[a-z]{2}(?:_[A-Z]{2})?)?(/shop/(?!cart|page|category|checkout|wishlist)[^\"#?]+)[^\"]*\"");
    private static final Pattern IMAGEN = Pattern.compile("<img\\b[^>]*\\bsrc=\"([^\"]*/web/image/[^\"]*)\"");
    private static final Pattern PRECIO_ITEMPROP = Pattern.compile("itemprop=\"price\"[^>]*>\\s*([0-9.,]+)\\s*<");
    private static final Pattern TACHADO = Pattern.compile("<del\\b([^>]*)>(.*?)</del>", Pattern.DOTALL);
    private static final Pattern MONTO_ODOO = Pattern.compile("oe_currency_value\"[^>]*>([^<]+)<");
    private static final Pattern ETIQUETAS = Pattern.compile("<[^>]+>");
    // Macstation lists its card-payment discounts as products priced at $1.21 ("10% de descuento tarjeta de crédito").
    private static final Pattern DESCUENTO_COMO_PRODUCTO = Pattern.compile("(?iu)^\\d+\\s*% de descuento\\b");
    private static final Pattern PAGINA = Pattern.compile("/shop/page/(\\d+)");

    /** {@code tarjetas} counts every card on the page, before the price band; {@code urls} are all their urls. */
    record Listado(List<Product> productos, int tarjetas, Set<String> urls) {}

    public OdooPage(Page page, int timeoutMs, String sitio, String baseUrl, double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
    }

    @Override
    public List<Product> scrapeAll() {
        limpiarTruncamiento();
        navigateConReintentos(baseUrl + "/shop");
        return crawl(page.content(), this::texto);
    }

    /** {@code htmlDe} fetches page N (N >= 2); the first page is the one already loaded. */
    List<Product> crawl(String primera, IntFunction<String> htmlDe) {
        String html = primera;
        int ultima = ultimaPagina(html);
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (int p = 1; p <= MAX_PAGINAS && (ultima == 0 || p <= ultima); p++) {
            Listado l;
            try {
                if (p > 1) html = htmlDe.apply(p);
                l = parseListado(html, sitio, baseUrl, precioMin, precioMax);
            } catch (RuntimeException e) {
                if (result.isEmpty()) throw e;
                registrarTruncamiento(p, e);
                return result;
            }
            if (l.tarjetas() == 0) {
                if (p == 1) throw new OdooPayloadException("la primera página de " + baseUrl + "/shop no trae productos");
                if (ultima > 0) registrarTruncamiento(p, new OdooPayloadException("la página " + p + " de " + ultima + " vino vacía"));
                break;
            }
            if (!vistas.addAll(l.urls())) break; // nothing new: the page repeats an earlier one
            result.addAll(l.productos());
            log.debug("[{}] Odoo p{}/{}: {} acumulados", sitio, p, ultima, result.size());
            if (p < ultima || ultima == 0) pausa();
        }
        return dedup(result);
    }

    void pausa() {
        page.waitForTimeout(PAUSA_MS);
    }

    private String texto(int pagina) {
        return StringUtils.defaultString((String) page.evaluate(FETCH_TEXTO, "/shop/page/" + pagina));
    }

    /** A card can reach {@code productos} twice only if a page repeats part of the previous one; first one wins. */
    private static List<Product> dedup(List<Product> productos) {
        Set<String> urls = new HashSet<>();
        return productos.stream().filter(p -> urls.add(p.url())).toList();
    }

    /** 0 when the listing has no pagination links: the crawl then ends at the first page with nothing new. */
    static int ultimaPagina(String html) {
        int ultima = 0;
        Matcher m = PAGINA.matcher(StringUtils.defaultString(html));
        while (m.find()) ultima = Math.max(ultima, Integer.parseInt(m.group(1)));
        return ultima;
    }

    static Listado parseListado(String html, String sitio, String baseUrl, double precioMin, double precioMax) {
        if (StringUtils.isBlank(html)) return new Listado(List.of(), 0, Set.of());
        List<String> tarjetas = tarjetas(html);
        List<Product> productos = new ArrayList<>();
        Set<String> urls = new LinkedHashSet<>();
        for (String card : tarjetas) {
            Matcher enlace = ENLACE.matcher(card);
            if (!enlace.find()) continue;
            String url = baseUrl + enlace.group(1);
            urls.add(url);
            Optional<Product> producto = parseTarjeta(card, sitio, url, baseUrl, precioMin, precioMax);
            producto.ifPresent(productos::add);
        }
        return new Listado(productos, tarjetas.size(), urls);
    }

    private static List<String> tarjetas(String html) {
        List<Integer> inicios = new ArrayList<>();
        Matcher m = INICIO_TARJETA.matcher(html);
        while (m.find()) inicios.add(m.start());
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < inicios.size(); i++)
            cards.add(html.substring(inicios.get(i), i + 1 < inicios.size() ? inicios.get(i + 1) : html.length()));
        return cards;
    }

    private static Optional<Product> parseTarjeta(String card, String sitio, String url, String baseUrl,
                                                  double precioMin, double precioMax) {
        String nombre = HtmlUtils.htmlUnescape(nombre(card)).trim();
        Double precio = precio(card);
        if (nombre.isEmpty() || DESCUENTO_COMO_PRODUCTO.matcher(nombre).find()) return Optional.empty();
        if (precio == null || precio <= 0 || precio < precioMin || precio > precioMax) return Optional.empty();
        Double anterior = precioAnterior(card);
        return Optional.of(Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(precio)
                .precioOriginal(anterior != null && anterior > precio ? anterior : null)
                .url(url)
                .imagenUrl(imagen(card, baseUrl))
                .categoria("")
                .genero("")
                .talles(List.of())
                .build());
    }

    private static String nombre(String card) {
        Matcher etiqueta = ETIQUETA_NOMBRE.matcher(card);
        if (etiqueta.find()) {
            Matcher content = ATRIBUTO_CONTENT.matcher(etiqueta.group());
            if (content.find() && !content.group(1).isBlank()) return content.group(1);
        }
        Matcher titulo = TITULO.matcher(card);
        if (titulo.find() && !titulo.group(1).isBlank()) return titulo.group(1);
        Matcher aria = ARIA_DEL_FORM.matcher(card);
        return aria.find() ? aria.group(1) : "";
    }

    private static Double precio(String card) {
        Matcher numerico = PRECIO_ITEMPROP.matcher(card);
        if (numerico.find()) return monto(numerico.group(1));
        Matcher valor = MONTO_ODOO.matcher(TACHADO.matcher(card).replaceAll(""));
        return valor.find() ? monto(valor.group(1)) : null;
    }

    /** A {@code <del>} hidden with {@code d-none} (Macstation keeps one on every card) is no discount. */
    private static Double precioAnterior(String card) {
        Matcher del = TACHADO.matcher(card);
        while (del.find()) {
            if (del.group(1).contains("d-none")) continue;
            Double monto = monto(ETIQUETAS.matcher(del.group(2)).replaceAll(" "));
            if (monto != null) return monto;
        }
        return null;
    }

    /**
     * Reads a price in either locale: the last separator is the decimal one unless exactly three digits follow it
     * ({@code 1.999}, {@code 43,900}); every other separator is a thousands one. Null when there is no digit.
     */
    static Double monto(String raw) {
        String s = StringUtils.defaultString(raw).replaceAll("[^0-9.,]", "");
        if (!StringUtils.containsAny(s, "0123456789")) return null;
        int ultimo = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
        String decimales = ultimo < 0 ? "" : s.substring(ultimo + 1);
        String entera = decimales.length() == 3 || ultimo < 0 ? s : s.substring(0, ultimo);
        if (decimales.length() == 3) decimales = "";
        entera = StringUtils.defaultIfEmpty(entera.replaceAll("[.,]", ""), "0");
        return Double.valueOf(decimales.isEmpty() ? entera : entera + "." + decimales);
    }

    /** The grid serves a 256 px thumbnail on Macstation; the same endpoint answers a bigger size. */
    private static String imagen(String card, String baseUrl) {
        Matcher m = IMAGEN.matcher(card);
        if (!m.find()) return "";
        String src = HtmlUtils.htmlUnescape(m.group(1)).replace("/image_256", "/image_512");
        return src.startsWith("/") ? baseUrl + src : src;
    }
}
