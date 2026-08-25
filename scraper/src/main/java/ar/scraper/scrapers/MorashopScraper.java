package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.pages.MorashopPage;
import ar.scraper.pages.TiendanubePage;
import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Scraper de Morashop (Tiendanube). Idéntico a {@link TiendanubeScraper} salvo
 * que usa {@link MorashopPage}, que descubre las categorías hoja porque la
 * tienda no tiene URL de catálogo — su {@code /productos/} es una landing del
 * tema con cero productos.
 *
 * <p>Hereda toda la lógica de scraping; sólo overridea el Factory Method
 * {@link #crearPage(Page)} (Open/Closed) — no reescribe {@code scrape()}.
 * Mismo patrón que {@link MonkyforceScraper}.</p>
 */
public class MorashopScraper extends TiendanubeScraper {

    public MorashopScraper(ScraperConfig config, String sitio, String url) {
        super(config, sitio, url);
    }

    public MorashopScraper(ScraperConfig config, String sitio, String url, List<String> extraUrls) {
        super(config, sitio, url, extraUrls);
    }

    @Override
    protected TiendanubePage crearPage(Page page) {
        return new MorashopPage(page, config.getTimeoutMs(),
                sitio, baseUrl,
                config.getPrecioMinimo(),
                config.getPrecioMaximo(),
                extraUrls,
                maxPaginas());
    }
}
