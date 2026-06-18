package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.TechStorePage;
import com.microsoft.playwright.Page;
import java.util.List;

public class FullH4rdScraper extends BaseScraper {
    public FullH4rdScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }
    @Override
    protected List<Product> scrape(Page page) {
        return new TechStorePage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(), config.getPrecioMaximo(),
                TechStorePage.TechStoreType.FULLH4RD)
                .scrapeAll();
    }
}
