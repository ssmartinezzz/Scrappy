package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.VtexPage;
import com.microsoft.playwright.Page;
import java.util.List;

public class VtexScraper extends BaseScraper {
    public VtexScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }
    @Override
    protected List<Product> scrape(Page page) {
        return new VtexPage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(),
                config.getPrecioMaximo()).scrapeAll();
    }
}
