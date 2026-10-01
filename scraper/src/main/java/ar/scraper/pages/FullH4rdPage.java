package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/** . fullh4rd.com.ar after its redesign: */
public class FullH4rdPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(FullH4rdPage.class);

    /** Only a safety net: the crawl ends at the first page with no new card (~161 measured). */
    static final int MAX_PAGINAS = 300;
    private static final int INTENTOS = 3;
    private static final long ESPERA_BASE_MS = 3_000;

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public FullH4rdPage(Page page, int timeoutMs, String sitio, String baseUrl,
                         double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio     = sitio;
        this.baseUrl   = baseUrl.replaceAll("/+$", "");
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    public List<Product> scrapeAll() {
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        OptionalInt total = OptionalInt.empty();

        for (int p = 1; p <= MAX_PAGINAS; p++) {
            // Orden explícito: el default no es estable entre requests.
            String html = fetchHtml(baseUrl + "/productos?sort=name_asc&page=" + p, p, total);
            if (html == null) break;
            if (total.isEmpty()) total = totalDisponibles(html);

            int cards = contarCards(html);
            if (cards == 0) {
                if (!esFinDeCatalogo(0, p, total)) {
                    log.warn("[{}] p{}: sin cards antes del total declarado ({}), se corta con {} productos",
                            sitio, p, total.getAsInt(), result.size());
                }
                break;
            }
            for (Product prod : parseListing(html, sitio, baseUrl, precioMin, precioMax)) {
                if (vistas.add(prod.url())) result.add(prod);
            }
            log.debug("[{}] p{}: {} cards ({} acumulados)", sitio, p, cards, result.size());
        }
        log.info("[{}] COMPLETADO: {} productos (el sitio declara {})", sitio, result.size(),
                total.isPresent() ? total.getAsInt() : "?");
        return result;
    }

    /**
     * Una página sin cards antes del total declarado es un bloqueo o un error del sitio, no el fin:
     * se reintenta con espera creciente..
     */
    private String fetchHtml(String url, int pagina, OptionalInt total) {
        String html = null;
        for (int intento = 1; intento <= INTENTOS; intento++) {
            try {
                navigateTo(url);
                html = page.content();
                int cards = contarCards(html);
                if (cards > 0 || esFinDeCatalogo(cards, pagina, total)) return html;
            } catch (Exception e) {
                log.debug("[{}] p{} intento {}: {}", sitio, pagina, intento, e.getMessage());
            }
            if (intento < INTENTOS) page.waitForTimeout(ESPERA_BASE_MS * intento);
        }
        if (html == null) log.warn("[{}] p{}: la navegación falló {} veces", sitio, pagina, INTENTOS);
        return html;
    }

    private static final Pattern TOTAL = Pattern.compile("(\\d+)\\s+productos disponibles");
    static final int POR_PAGINA = 12;

    static OptionalInt totalDisponibles(String html) {
        var m = TOTAL.matcher(html == null ? "" : html);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /** Sin total declarado, una página vacía es el fin; con total, sólo si ya lo pasamos. */
    static boolean esFinDeCatalogo(int cardsEnPagina, int pagina, OptionalInt total) {
        if (cardsEnPagina > 0) return false;
        return total.isEmpty() || (pagina - 1) * POR_PAGINA >= total.getAsInt();
    }

    static int contarCards(String html) {
        return html == null ? 0 : html.split(CARD, -1).length - 1;
    }

    private static final String CARD = "<article class=\"results-card\"";
    private static final Pattern TITLE_URL = Pattern.compile(
            "<a href=\"([^\"]+)\" class=\"results-card__title-link\"[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern IMG = Pattern.compile("<img[^>]*\\ssrc=\"([^\"]+)\"");
    private static final Pattern META = Pattern.compile(
            "results-card__meta\"[^>]*>([\\s\\S]*?)</p>");
    private static final Pattern PRECIO_CURRENT = Pattern.compile(
            "results-card__price-current\"[^>]*>([\\s\\S]*?)</p>");
    private static final Pattern PRECIO_LIST = Pattern.compile(
            "results-card__price-list\"[^>]*>([\\s\\S]*?)</p>");

    static List<Product> parseListing(String html, String sitio, String baseUrl,
                                       double precioMin, double precioMax) {
        if (StringUtils.isBlank(html)) return List.of();

        List<Product> result = new ArrayList<>();
        Set<String> vistasEnPagina = new HashSet<>();
        String[] cards = html.split(CARD);
        // cards[0] es lo que precede a la primera card, no es una card.
        for (int i = 1; i < cards.length; i++) {
            String card = cards[i];

            var mTitleUrl = TITLE_URL.matcher(card);
            if (!mTitleUrl.find()) continue;
            String url = ImageUrl.absolutize(mTitleUrl.group(1), baseUrl);
            String nombre = decodeText(mTitleUrl.group(2));
            if (url.isBlank() || nombre.isBlank() || !vistasEnPagina.add(url)) continue;

            var mPrecio = PRECIO_CURRENT.matcher(card);
            if (!mPrecio.find()) continue;
            Optional<Double> precio = TechStorePage.parsePrecioTech(mPrecio.group(1).trim());
            if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax) continue;

            // price-list es el precio de lista: sólo es "original" si es mayor.
            Double precioOriginal = null;
            var mList = PRECIO_LIST.matcher(card);
            if (mList.find()) {
                Optional<Double> listado = TechStorePage.parsePrecioTech(mList.group(1).trim());
                if (listado.isPresent() && listado.get() > precio.get()) precioOriginal = listado.get();
            }

            String img = "";
            var mImg = IMG.matcher(card);
            if (mImg.find()) img = ImageUrl.absolutize(mImg.group(1), baseUrl);

            String categoria = "";
            var mMeta = META.matcher(card);
            if (mMeta.find()) categoria = decodeText(mMeta.group(1));

            result.add(new Product(
                    sitio, nombre, precio.get(), precioOriginal,
                    url, img, categoria, "",
                    List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
        }
        return result;
    }

    /** Chromium's serializer escapes exactly these in text nodes; */
    private static String decodeText(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }
}
