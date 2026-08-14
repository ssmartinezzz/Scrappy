package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Reader for stores on the Qloud platform (confirmed via {@code cdn.qloud.ar}
 * / {@code statics.qloud.com.ar} references, 368 hits on rockethard.com.ar).
 * Qloud is a multi-tenant platform, not a single store — this pair
 * generalizes to any Argentine store on it, same argument as
 * {@link VaypolPage}/{@code VaypolScraper} (design D1).
 *
 * <p>Server-rendered: no JS hydration wait needed, {@code curl} already sees
 * the product cards. Every product renders TWICE in the DOM (a desktop card
 * and a responsive mobile duplicate, identical URL both times) — dedup by
 * URL within a page is mandatory, not just across pages.</p>
 *
 * <p>{@code /productos} is a confirmed 404 (redirects to {@code /productos/}
 * which 404s) — never synthesized as a category slug.</p>
 */
public class QloudPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(QloudPage.class);

    /**
     * Named fallback used only when nav discovery returns 0 slugs (design D5).
     * Frozen list measured live 2026-08-13 against rockethard.com.ar.
     */
    static final List<String> FALLBACK_SLUGS = List.of(
            "hardware", "perifericos", "monitores", "gabinete",
            "refrigeracion", "conectividad", "almacenamiento", "accesorios-");

    /** Non-listing pages that legitimately show up in a flat top-level nav link. */
    private static final Set<String> DENYLIST = Set.of(
            "contacto", "atencion-a-empresas", "arma-tu-pc", "elegi-tu-pc", "eligi-tu-combo",
            "mi-cuenta", "carrito", "checkout", "login", "registro", "productos");

    private static final int MAX_PAGES = 30;

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public QloudPage(Page page, int timeoutMs, String sitio, String baseUrl,
                      double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio = sitio;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public List<Product> scrapeAll() {
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();

        List<String> slugs = discoverCategorySlugs();
        log.info("[{}] categorías Qloud: {}", sitio, slugs);

        int zeroYieldSlugs = 0;
        for (String slug : slugs) {
            String categoriaHint = humanize(slug);
            Set<String> urlsPrevias = new HashSet<>(vistas);
            boolean sloExtCategoriaVacia = crawlCategory(slug, categoriaHint, vistas, result);
            if (sloExtCategoriaVacia && vistas.size() == urlsPrevias.size()) {
                zeroYieldSlugs++;
                log.warn("[{}] slug '{}' rindió 0 productos", sitio, slug);
            }
        }
        log.info("[{}] COMPLETADO: {} productos ({} de {} slugs sin yield)",
                sitio, result.size(), zeroYieldSlugs, slugs.size());
        return result;
    }

    /** Crawls one category slug's pagination until an empty or fully-repeated page. Returns true if the slug never yielded anything. */
    private boolean crawlCategory(String slug, String categoriaHint, Set<String> vistas, List<Product> result) {
        boolean neverYielded = true;
        for (int p = 1; p <= MAX_PAGES; p++) {
            String url = baseUrl + "/" + slug + "/" + (p > 1 ? "?page=" + p : "");
            try {
                navigateTo(url);
                String html = page.content();
                List<Product> pagina = parseListing(html, sitio, baseUrl, categoriaHint, precioMin, precioMax);

                List<Product> nuevos = pagina.stream()
                        .filter(prod -> vistas.add(prod.url()))
                        .toList();
                if (nuevos.isEmpty()) break; // página vacía o 100% repetida -> fin del catálogo/categoría
                result.addAll(nuevos);
                neverYielded = false;
            } catch (Exception e) {
                log.debug("[{}] slug={} p={}: {}", sitio, slug, p, e.getMessage());
                break;
            }
        }
        return neverYielded;
    }

    /** Navega a la home y descubre slugs vía nav; frozen list como fallback nombrado si la descubierta da 0 (design D5). */
    private List<String> discoverCategorySlugs() {
        try {
            navigateTo(baseUrl + "/");
            List<String> discovered = extractCategorySlugs(page.content(), baseUrl);
            if (!discovered.isEmpty()) return discovered;
        } catch (Exception e) {
            log.debug("[{}] discover slugs error: {}", sitio, e.getMessage());
        }
        log.warn("[{}] nav discovery returned 0 slugs, falling back to the frozen list of {}",
                sitio, FALLBACK_SLUGS.size());
        return FALLBACK_SLUGS;
    }

    private static String humanize(String slug) {
        String s = slug.replaceAll("-+$", "").replace('-', ' ').trim();
        if (s.isBlank()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ─── Pure, package-private: parsing (design D6) ───────────────────────

    private static final java.util.regex.Pattern TITLE_URL = java.util.regex.Pattern.compile(
            "card-title[^\"]*\"\\s*>\\s*<a href=\"([^\"]+)\">([^<]+)</a>");
    private static final java.util.regex.Pattern IMG = java.util.regex.Pattern.compile("<img src=\"([^\"]+)\"");
    private static final java.util.regex.Pattern PRECIO_FINAL = java.util.regex.Pattern.compile(
            "data-precio=\"([0-9.]+)\"");
    private static final java.util.regex.Pattern TACHADO = java.util.regex.Pattern.compile(
            "tachado_\\d\"[^>]*>\\$([0-9.,]+)</h6>");

    static List<Product> parseListing(String html, String sitio, String baseUrl,
                                       String categoriaHint, double precioMin, double precioMax) {
        if (html == null || html.isBlank()) return List.of();

        List<Product> result = new ArrayList<>();
        Set<String> vistasEnPagina = new HashSet<>();
        // Split on the card wrapper's opening tag, NOT on the "<!--Card-->" HTML
        // comment: the real markup also emits "<!--Card image-->"/"<!--Card
        // content-->" sub-comments inside the SAME card, which would fragment
        // one product's title/price across different split pieces.
        String[] cards = html.split("<div class=\"card card-ecommerce");
        // cards[0] is whatever precedes the first card — not a card.
        for (int i = 1; i < cards.length; i++) {
            String card = cards[i];

            var mTitleUrl = TITLE_URL.matcher(card);
            if (!mTitleUrl.find()) continue;
            String url = mTitleUrl.group(1);
            String nombre = mTitleUrl.group(2).trim();
            if (url.isBlank() || nombre.isBlank() || !vistasEnPagina.add(url)) continue;

            var mPrecio = PRECIO_FINAL.matcher(card);
            if (!mPrecio.find()) continue;
            double precio;
            try {
                precio = Double.parseDouble(mPrecio.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (precio <= 0 || precio < precioMin || precio > precioMax) continue;

            String img = "";
            var mImg = IMG.matcher(card);
            if (mImg.find()) img = mImg.group(1);
            if (img.startsWith("//")) img = "https:" + img;

            Double precioOriginal = null;
            var mTachado = TACHADO.matcher(card);
            if (mTachado.find()) {
                String raw = mTachado.group(1).replace(".", "").replace(",", ".");
                try {
                    double tachado = Double.parseDouble(raw);
                    if (tachado > precio) precioOriginal = tachado;
                } catch (NumberFormatException ignored) {
                    // markup sin tachado parseable -> sin precioOriginal, no es un error
                }
            }

            result.add(new Product(
                    sitio, nombre, precio, precioOriginal,
                    url, img, categoriaHint, "",
                    List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
        }
        return result;
    }

    private static final java.util.regex.Pattern NAV_LINK = java.util.regex.Pattern.compile(
            "href=\"https?://[^\"/]+/([a-z0-9][a-z0-9-]*)/?\"");

    static List<String> extractCategorySlugs(String navHtml, String baseUrl) {
        if (navHtml == null || navHtml.isBlank()) return List.of();
        Set<String> slugs = new LinkedHashSet<>();
        var m = NAV_LINK.matcher(navHtml);
        while (m.find()) {
            String slug = m.group(1);
            if (!DENYLIST.contains(slug)) slugs.add(slug);
        }
        return List.copyOf(slugs);
    }
}
