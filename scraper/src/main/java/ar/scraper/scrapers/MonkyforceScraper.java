package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.pages.MonkyforcePage;
import ar.scraper.pages.TiendanubePage;
import com.microsoft.playwright.Page;
import java.util.List;

/**
 * Scraper de Monkyforce (Tiendanube). Idéntico a {@link TiendanubeScraper}
 * salvo que usa {@link MonkyforcePage} para arreglar la extracción del nombre
 * (el tema mete un {@code <h4>Sin stock</h4>} en cada card que el extractor
 * base tomaba como nombre → dedup por sitio+nombre colapsaba la tienda a 1).
 *
 * <p>Hereda toda la lógica de scraping; solo overridea el Factory Method
 * {@link #crearPage(Page)} (Open/Closed) — no reescribe {@code scrape()}.</p>
 */
public class MonkyforceScraper extends TiendanubeScraper {

    public MonkyforceScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }

    public MonkyforceScraper(ScraperConfig config, String sitio, String url, List<String> extraUrls) {
        super(config, sitio, url, extraUrls);
    }

    @Override
    protected TiendanubePage crearPage(Page page) {
        return new MonkyforcePage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(),
                config.getPrecioMaximo(),
                extraUrls);
    }
}
