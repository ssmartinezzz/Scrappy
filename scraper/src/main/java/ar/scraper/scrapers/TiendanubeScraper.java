package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.TiendanubePage;
import com.microsoft.playwright.Page;
import java.util.List;

public class TiendanubeScraper extends BaseScraper {
    private final List<String> extraUrls;

    public TiendanubeScraper(ScraperConfig config, String sitio, String url) {
        this(config, sitio, url, List.of());
    }

    public TiendanubeScraper(ScraperConfig config, String sitio, String url, List<String> extraUrls) {
        super(config, sitio, url);
        this.extraUrls = extraUrls != null ? extraUrls : List.of();
    }

    @Override
    protected List<Product> scrape(Page page) {
        return new TiendanubePage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(),
                config.getPrecioMaximo(),
                extraUrls).scrapeAll();
    }
}
