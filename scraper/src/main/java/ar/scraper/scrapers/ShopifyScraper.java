package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.ShopifyPage;
import com.microsoft.playwright.Page;
import java.util.List;
import java.util.Optional;

public class ShopifyScraper extends BaseScraper {
    public ShopifyScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }
    @Override
    protected List<Product> scrape(Page page) {
        return new ShopifyPage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(),
                config.getPrecioMaximo()).scrapeAll();
    }

    /**
     * Re-scrapea un único producto favorito. Banda de precio amplia
     * (0..MAX_VALUE) para no descartar favoritos fuera del rango configurado.
     */
    public Optional<Product> scrapeOne(Page page, String url) {
        return new ShopifyPage(page, config.getTimeoutMs(),
                sitio, baseUrl, 0, Double.MAX_VALUE).scrapeOne(url);
    }
}
