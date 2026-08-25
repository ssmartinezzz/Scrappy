package ar.scraper.scrapers;

import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.aggregator.normalize.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;

/**
 * close-1nf-and-3nf-foundation extension (design E1): the 8 name-sets and
 * {@code PLATAFORMA_NOMBRES} that used to drive {@link #crear}'s routing are
 * DELETED, not mirrored — {@code sitio.plataforma}, read through
 * {@link SiteRegistry}, is now the single source (`CODE-6`). The URL-based
 * fallbacks ({@code myshopify.com}, {@code vtexcommercestable.com.br}) stay
 * in code: they are not name-based and cannot live in a static seed table.
 */
public class ScraperFactory {

    public static BaseScraper crear(ScraperConfig config, SiteConfig site, SiteRegistry siteRegistry) {
        String n       = site.nombre().toLowerCase();
        String display = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        String plataforma = siteRegistry.plataforma(SiteClassification.sitioKey(n));

        if ("woocommerce".equals(plataforma))
            return new WooCommerceScraper(config, display, site.url());

        if ("maximus".equals(plataforma))
            return new MaximusScraper(config, display, site.url());

        if ("fullh4rd".equals(plataforma))
            return new FullH4rdScraper(config, display, site.url());

        if ("compragamer".equals(plataforma))
            return new CompraGamerScraper(config, display, site.url());

        if ("vaypol".equals(plataforma))
            return new VaypolScraper(config, display, site.url());

        if ("qloud".equals(plataforma))
            return new QloudScraper(config, display, site.url());

        if ("oscommerce".equals(plataforma))
            return new OsCommerceScraper(config, display, site.url());

        if ("vtex".equals(plataforma)
                || site.url().contains("vtexcommercestable.com.br")
                || site.url().contains("vteximg.com.br"))
            return new VtexScraper(config, display, site.url());

        if ("shopify".equals(plataforma)
                || site.url().contains("myshopify.com"))
            return new ShopifyScraper(config, display, site.url());

        if ("monkyforce".equals(plataforma))
            return new MonkyforceScraper(config, display, site.url(), site.extraUrls());

        return new TiendanubeScraper(config, display, site.url(), site.extraUrls());
    }
}
