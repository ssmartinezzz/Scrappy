package ar.scraper.scrapers;

import ar.scraper.classification.SiteClassification;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import ar.scraper.pages.CatalogPage;
import ar.scraper.pages.FullH4rdPage;
import ar.scraper.pages.InproPage;
import ar.scraper.pages.MonkyforcePage;
import ar.scraper.pages.MorashopPage;
import ar.scraper.pages.OsCommercePage;
import ar.scraper.pages.QloudPage;
import ar.scraper.pages.ShopifyPage;
import ar.scraper.pages.TechStorePage;
import ar.scraper.pages.TechStorePage.TechStoreType;
import ar.scraper.pages.TiendanubePage;
import ar.scraper.pages.VaypolPage;
import ar.scraper.pages.VtexPage;
import ar.scraper.pages.WooCommercePage;
import ar.scraper.scrapers.BaseScraper.PageFactory;
import com.microsoft.playwright.Page;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * The URL-based fallbacks ({@code myshopify.com}, {@code vtexcommercestable.com.br}) stay in code:
 * they are not name-based and cannot live in a static seed table.
 */
public class ScraperFactory {

    @FunctionalInterface
    private interface SimplePage {
        CatalogPage crear(Page page, int timeoutMs, String sitio, String baseUrl, double precioMin, double precioMax);
    }

    @FunctionalInterface
    private interface PaginatedPage {
        CatalogPage crear(Page page, int timeoutMs, String sitio, String baseUrl, double precioMin, double precioMax,
                       List<String> extraUrls, int maxPaginas);
    }

    private static final String TIENDANUBE = "tiendanube";

    private static final Map<String, PageFactory> PAGINAS = Map.ofEntries(
            entry("woocommerce", simple(WooCommercePage::new)),
            entry("maximus",     simple((p, t, s, u, min, max) -> new TechStorePage(p, t, s, u, min, max, TechStoreType.MAXIMUS))),
            entry("fullh4rd",    simple(FullH4rdPage::new)),
            entry("compragamer", simple((p, t, s, u, min, max) -> new TechStorePage(p, t, s, u, min, max, TechStoreType.COMPRAGAMER))),
            entry("vaypol",      simple(VaypolPage::new)),
            entry("qloud",       simple(QloudPage::new)),
            entry("oscommerce",  simple(OsCommercePage::new)),
            entry("inpro",       simple(InproPage::new)),
            entry("vtex",        simple(VtexPage::new)),
            entry("shopify",     simple(ShopifyPage::new)),
            entry("monkyforce",  paginated(MonkyforcePage::new)),
            entry("morashop",    paginated(MorashopPage::new)),
            entry(TIENDANUBE,    paginated(TiendanubePage::new)));

    /** Platforms whose registry value wins over a VTEX or Shopify URL; the rest yield to the URL. */
    private static final Set<String> GANAN_A_LA_URL = Set.of(
            "woocommerce", "maximus", "fullh4rd", "compragamer", "vaypol", "qloud", "oscommerce", "inpro", "vtex");

    public static BaseScraper crear(ScraperConfig config, SiteConfig site, SiteRegistry siteRegistry) {
        String n       = site.nombre().toLowerCase();
        String display = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        String plataforma = StringUtils.defaultString(siteRegistry.plataforma(SiteClassification.sitioKey(n)));
        return new BaseScraper(config, display, site.url(), site.extraUrls(), elegir(plataforma, site.url()));
    }

    private static PageFactory elegir(String plataforma, String url) {
        if (GANAN_A_LA_URL.contains(plataforma))
            return PAGINAS.get(plataforma);
        if (url.contains("vtexcommercestable.com.br") || url.contains("vteximg.com.br"))
            return PAGINAS.get("vtex");
        if (url.contains("myshopify.com"))
            return PAGINAS.get("shopify");
        return PAGINAS.getOrDefault(plataforma, PAGINAS.get(TIENDANUBE));
    }

    private static PageFactory simple(SimplePage pagina) {
        return (page, c) -> pagina.crear(page, c.config().getTimeoutMs(), c.sitio(), c.baseUrl(),
                c.config().getPrecioMinimo(), c.config().getPrecioMaximo());
    }

    private static PageFactory paginated(PaginatedPage pagina) {
        return (page, c) -> pagina.crear(page, c.config().getTimeoutMs(), c.sitio(), c.baseUrl(),
                c.config().getPrecioMinimo(), c.config().getPrecioMaximo(), c.extraUrls(),
                c.config().getMaxPaginas(c.sitio(), TiendanubePage.MAX_PAGINAS_DEFAULT));
    }
}
