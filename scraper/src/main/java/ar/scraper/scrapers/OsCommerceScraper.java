package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.OsCommercePage;
import com.microsoft.playwright.Page;
import java.util.List;

public class OsCommerceScraper extends BaseScraper {
    public OsCommerceScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }
    @Override
    protected List<Product> scrape(Page page) {
        return new OsCommercePage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(), config.getPrecioMaximo())
                .scrapeAll();
    }
}
