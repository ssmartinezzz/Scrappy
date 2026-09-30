package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.InproPage;
import com.microsoft.playwright.Page;

import java.util.List;

public class InproScraper extends BaseScraper {

    public InproScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }

    @Override
    protected List<Product> scrape(Page page) {
        return new InproPage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(), config.getPrecioMaximo())
                .scrapeAll();
    }
}
