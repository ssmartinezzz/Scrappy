package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import org.apache.commons.lang3.StringUtils;

/**
 * Qloud is a multi-tenant platform, not a single store — this page generalizes to any Argentine
 * store on it, same argument as {@link VaypolPage}.
 */
public class QloudPage extends StorePage implements CatalogPage {

    private static final Logger log = LoggerFactory.getLogger(QloudPage.class);

    /**
     * Named fallback used only when nav discovery returns 0 slugs. Frozen list measured live
     * 2026-08-13 against rockethard.com.ar.
     */
    static final List<String> FALLBACK_SLUGS = List.of(
            "hardware", "perifericos", "monitores", "gabinete",
            "refrigeracion", "conectividad", "almacenamiento", "accesorios-");

    private static final Set<String> DENYLIST = Set.of(
            "contacto", "atencion-a-empresas", "arma-tu-pc", "elegi-tu-pc", "eligi-tu-combo",
            "mi-cuenta", "carrito", "checkout", "login", "registro", "productos");

    private static final int MAX_PAGES = 30;


    public QloudPage(Page page, int timeoutMs, String sitio, String baseUrl,
                      double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, sinBarraFinal(baseUrl), precioMin, precioMax);
    }

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

    /** Returns true if the slug never yielded anything. */
    private boolean crawlCategory(String slug, String categoriaHint, Set<String> vistas, List<Product> result) {
        return !crawlPaginas(MAX_PAGES, "slug=" + slug,
                p -> baseUrl + "/" + slug + "/" + (p > 1 ? "?page=" + p : ""),
                html -> parseListing(html, sitio, baseUrl, categoriaHint, precioMin, precioMax),
                vistas, result);
    }

    /**
     * Navega a la home y descubre slugs vía nav; frozen list como fallback nombrado si la
     * descubierta da 0.
     */
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

    private static final java.util.regex.Pattern TITLE_URL = java.util.regex.Pattern.compile(
            "card-title[^\"]*\"\\s*>\\s*<a href=\"([^\"]+)\">([^<]+)</a>");
    private static final java.util.regex.Pattern IMG = java.util.regex.Pattern.compile("<img src=\"([^\"]+)\"");
    private static final java.util.regex.Pattern PRECIO_FINAL = java.util.regex.Pattern.compile(
            "data-precio=\"([0-9.]+)\"");
    private static final java.util.regex.Pattern TACHADO = java.util.regex.Pattern.compile(
            "tachado_\\d\"[^>]*>\\$([0-9.,]+)</h6>");

    static List<Product> parseListing(String html, String sitio, String baseUrl,
                                       String categoriaHint, double precioMin, double precioMax) {
        if (StringUtils.isBlank(html)) return List.of();

        List<Product> result = new ArrayList<>();
        Set<String> vistasEnPagina = new HashSet<>();
        // Split on the card wrapper's opening tag, NOT on the "<!--Card-->" HTML comment: the real
        // markup also emits "<!--Card image-->"/"<!--Card content-->" sub-comments inside the SAME
        // card, which would fragment one product's title/price across different split pieces.
        for (String card : cards(html, "<div class=\"card card-ecommerce")) {
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

            String img = ImageUrl.primera(IMG, card, baseUrl);

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

            result.add(tecnologia(sitio, nombre, precio, precioOriginal, url, img, categoriaHint));
        }
        return result;
    }

    private static final java.util.regex.Pattern NAV_LINK = java.util.regex.Pattern.compile(
            "href=\"https?://[^\"/]+/([a-z0-9][a-z0-9-]*)/?\"");

    static List<String> extractCategorySlugs(String navHtml, String baseUrl) {
        if (StringUtils.isBlank(navHtml)) return List.of();
        Set<String> slugs = new LinkedHashSet<>();
        var m = NAV_LINK.matcher(navHtml);
        while (m.find()) {
            String slug = m.group(1);
            if (!DENYLIST.contains(slug)) slugs.add(slug);
        }
        return List.copyOf(slugs);
    }
}
