package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public abstract class BaseScraper {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ScraperConfig config;
    protected final String sitio;
    protected final String baseUrl;

    protected BaseScraper(ScraperConfig config, String sitio, String baseUrl) {
        this.config = config; this.sitio = sitio; this.baseUrl = baseUrl;
    }

    public final ScrapeResult ejecutar(Playwright pw) {
        long t0 = System.currentTimeMillis();
        try (Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(config.isHeadless())
                .setArgs(List.of("--no-sandbox","--disable-dev-shm-usage",
                        "--disable-blink-features=AutomationControlled")))) {
            try (BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 768)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                    .setLocale("es-AR"))) {
                try (Page page = ctx.newPage()) {
                    page.setDefaultTimeout(config.getTimeoutMs());
                    page.route("**/*.{woff,woff2,ttf,otf}", r -> r.abort());
                    page.route("**/analytics**", r -> r.abort());
                    page.route("**/gtag**",       r -> r.abort());
                    page.route("**/hotjar**",     r -> r.abort());
                    List<Product> prods = scrape(page);
                    long ms = System.currentTimeMillis() - t0;
                    log.debug("[{}] scrape completado: {} productos en {}ms", sitio, prods.size(), ms);
                    return new ScrapeResult(sitio, prods, null, ms);
                }
            }
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.warn("[{}] excepcion durante scrape: {}", sitio, e.getMessage());
            return new ScrapeResult(sitio, List.of(), e.getMessage(), ms);
        }
    }

    protected abstract List<Product> scrape(Page page);
}
