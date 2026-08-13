package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Reader for Venex, an osCommerce/ZenCart storefront (confirmed live:
 * {@code products_favorite_listing.php}, {@code account_edit.php},
 * {@code shopping_cart.php} signatures) (design D1).
 *
 * <p>Two-level category discovery: top-level slugs from the homepage nav,
 * then LEAF sub-category slugs from each top category's landing page — the
 * landing page itself shows 12 unrepresentative items and is never treated
 * as a yield source, only as a source of leaf links.</p>
 *
 * <p>Pagination ({@code ?page=N}) stops on EITHER an empty page OR a page
 * whose products were all already seen — confirmed live that Venex does
 * the latter past the true last page (it repeats, never goes empty).</p>
 */
public class OsCommercePage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(OsCommercePage.class);
    private static final int MAX_PAGES = 40;

    /** Non-listing pages that legitimately show up in a flat top-level nav link. */
    private static final Set<String> DENYLIST = Set.of(
            "carrito", "mi-cuenta", "checkout", "login", "registro", "contacto", "productos");

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public OsCommercePage(Page page, int timeoutMs, String sitio, String baseUrl,
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
        Set<String> vistasGlobal = new HashSet<>();

        List<String> topSlugs = discoverTopCategorySlugs();
        log.info("[{}] categorías top-level osCommerce: {}", sitio, topSlugs);

        for (String topSlug : topSlugs) {
            List<String> leaves = discoverLeafCategorySlugs(topSlug);
            log.debug("[{}] {} -> {} sub-categorías leaf", sitio, topSlug, leaves.size());
            for (String leafSlug : leaves) {
                String leafUrl = baseUrl + "/" + topSlug + "/" + leafSlug;
                String categoriaHint = humanize(leafSlug);
                List<Product> productos = crawlLeafCategory(leafUrl, categoriaHint);
                for (Product p : productos) {
                    if (vistasGlobal.add(p.url())) result.add(p);
                }
            }
        }
        log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
        return result;
    }

    /** Package-private: pagination loop for one leaf category, mocked-Page testable (design D6). */
    List<Product> crawlLeafCategory(String leafUrl, String categoriaHint) {
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (int p = 1; p <= MAX_PAGES; p++) {
            String url = leafUrl + (p > 1 ? "?page=" + p : "");
            try {
                navigateTo(url);
                String html = page.content();
                List<Product> pagina = parseListing(html, sitio, baseUrl, categoriaHint, precioMin, precioMax);

                List<Product> nuevos = pagina.stream()
                        .filter(prod -> vistas.add(prod.url()))
                        .toList();
                // Se detiene tanto en página vacía como en página 100% repetida
                // (medido en vivo: Venex nunca devuelve vacío pasado el final
                // real, repite indefinidamente la última página).
                if (nuevos.isEmpty()) break;
                result.addAll(nuevos);
            } catch (Exception e) {
                log.debug("[{}] leaf={} p={}: {}", sitio, leafUrl, p, e.getMessage());
                break;
            }
        }
        return result;
    }

    private List<String> discoverTopCategorySlugs() {
        try {
            navigateTo(baseUrl + "/");
            List<String> discovered = extractTopCategorySlugs(page.content());
            if (!discovered.isEmpty()) return discovered;
        } catch (Exception e) {
            log.debug("[{}] discover top slugs error: {}", sitio, e.getMessage());
        }
        log.warn("[{}] nav discovery returned 0 top-level slugs", sitio);
        return List.of();
    }

    private List<String> discoverLeafCategorySlugs(String topSlug) {
        try {
            navigateTo(baseUrl + "/" + topSlug);
            List<String> leaves = extractLeafCategorySlugs(page.content(), topSlug);
            if (!leaves.isEmpty()) return leaves;
        } catch (Exception e) {
            log.debug("[{}] discover leaf slugs error for {}: {}", sitio, topSlug, e.getMessage());
        }
        log.warn("[{}] '{}' no tiene sub-categorías leaf descubribles", sitio, topSlug);
        return List.of();
    }

    private static String humanize(String slug) {
        String[] words = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ─── Pure, package-private: parsing (design D6) ───────────────────────

    /**
     * One product-box block, keyed by its {@code enhancedClick({...})} JSON —
     * no external parser dependency (project has no Jsoup).
     *
     * <p>Two shapes are possible for the SAME markup and both must match:
     * the raw HTML the server sends over the wire uses
     * {@code onclick='enhancedClick({"id":...})'} (single-quoted attribute,
     * literal double quotes inside the JSON) — but {@code BasePage.navigateTo}
     * + {@code page.content()} return Chromium's RE-SERIALIZED DOM, not the
     * raw response body: attributes get normalized to double quotes and the
     * JSON's inner double quotes become the {@code &quot;} entity —
     * {@code onclick="enhancedClick({&quot;id&quot;:...})"}. Measured live
     * against venex.com.ar (2026-08-13): a parser that only recognizes the
     * single-quoted raw form reads 0 products from the page Playwright
     * actually sees. {@link #normalizeQuotedEntities} folds both shapes onto
     * one before matching, instead of maintaining two regex sets.</p>
     */
    private static final java.util.regex.Pattern ENHANCED_CLICK = java.util.regex.Pattern.compile(
            "enhancedClick\\(\\{\"id\":\"(\\d+)\",\"name\":\"([^\"]*)\",\"category\":\"([^\"]*)\"");
    private static final java.util.regex.Pattern PRODUCT_URL = java.util.regex.Pattern.compile(
            "<a href=\"([^\"]+)\"\\s+onclick=['\"]enhancedClick");
    private static final java.util.regex.Pattern PRICE_JSON = java.util.regex.Pattern.compile(
            "\"price\":\"([0-9.]+)\"");
    private static final java.util.regex.Pattern IMG = java.util.regex.Pattern.compile("<img src=\"([^\"]+)\"");

    /** Folds Chromium's re-serialized-DOM entity form onto the raw-HTML form. */
    private static String normalizeQuotedEntities(String html) {
        return html.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    static List<Product> parseListing(String html, String sitio, String baseUrl,
                                       String categoriaHint, double precioMin, double precioMax) {
        if (html == null || html.isBlank()) return List.of();
        html = normalizeQuotedEntities(html);

        List<Product> result = new ArrayList<>();
        Set<String> vistasEnPagina = new HashSet<>();
        String[] cards = html.split("<div class=\"product-box\"");
        for (int i = 1; i < cards.length; i++) {
            String card = cards[i];

            var mUrl = PRODUCT_URL.matcher(card);
            if (!mUrl.find()) continue;
            String url = mUrl.group(1);

            var mClick = ENHANCED_CLICK.matcher(card);
            if (!mClick.find()) continue;
            String nombre = mClick.group(2).trim();
            String categoriaJson = mClick.group(3).trim();
            if (nombre.isBlank() || url.isBlank() || !vistasEnPagina.add(url)) continue;

            var mPrecio = PRICE_JSON.matcher(card);
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
            else if (!img.isBlank() && !img.startsWith("http")) {
                img = baseUrl.replaceAll("/+$", "") + "/" + img.replaceAll("^/+", "");
            }

            String categoria = !categoriaJson.isBlank() ? categoriaJson : categoriaHint;

            // Venex no expone un precio tachado/anterior en el listado (CODE-5:
            // sin señal -> null, nunca inventado).
            result.add(new Product(
                    sitio, nombre, precio, null,
                    url, img, categoria, "",
                    List.of(), Product.MlScore.EMPTY, "", "tecnologia", false));
        }
        return result;
    }

    private static final java.util.regex.Pattern TOP_NAV_LINK = java.util.regex.Pattern.compile(
            "href=\"https?://[^\"/]+/([a-z0-9][a-z0-9-]*)/?\"");

    static List<String> extractTopCategorySlugs(String navHtml) {
        if (navHtml == null || navHtml.isBlank()) return List.of();
        Set<String> slugs = new LinkedHashSet<>();
        var m = TOP_NAV_LINK.matcher(navHtml);
        while (m.find()) {
            String slug = m.group(1);
            if (!DENYLIST.contains(slug)) slugs.add(slug);
        }
        return List.copyOf(slugs);
    }

    static List<String> extractLeafCategorySlugs(String landingHtml, String topSlug) {
        if (landingHtml == null || landingHtml.isBlank()) return List.of();
        var pattern = java.util.regex.Pattern.compile(
                "href=\"https?://[^\"/]+/" + java.util.regex.Pattern.quote(topSlug)
                        + "/([a-z0-9][a-z0-9-]*)/?\"");
        Set<String> leaves = new LinkedHashSet<>();
        var m = pattern.matcher(landingHtml);
        while (m.find()) leaves.add(m.group(1));
        return List.copyOf(leaves);
    }
}
