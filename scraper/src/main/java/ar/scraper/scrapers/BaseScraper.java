package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public abstract class BaseScraper {

    /**
     * JS init script applied to every new page to spoof bot-detection fingerprints.
     * Must be {@code public} so that {@link ar.scraper.web.ScraperService} (different
     * package) can reference it at the second call-site in {@code rescrapearSitio()}.
     */
    public static final String STEALTH_INIT_SCRIPT = """
            // Spoof: navigator.webdriver, navigator.plugins, navigator.languages, window.chrome
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            Object.defineProperty(navigator, 'plugins',   {get: () => [1, 2, 3, 4, 5]});
            Object.defineProperty(navigator, 'languages', {get: () => ['es-AR', 'es', 'en']});
            window.chrome = { runtime: {} };
            """;

    /**
     * Chromium launch flags shared by every scrape. {@code imagesEnabled=false}
     * is the load-bearing one: the scraper stores the image <em>URL</em> and
     * never reads a single image byte, yet fetching them anyway accounted for
     * 9.5 MB of the 12.7 MB transferred on a measured Tiendanube listing page.
     *
     * <p>Disabling them at the Blink level costs nothing at runtime. Doing it
     * with a catch-all {@code page.route("**&#47;*")} instead would round-trip
     * every single request through the Java process, which is the opposite of
     * an optimisation. The DOM is untouched either way — {@code src} and
     * {@code data-src} attributes still parse, so extraction is unaffected
     * (verified: identical img/anchor counts with and without the flag).
     */
    public static List<String> launchArgs() {
        return List.of("--no-sandbox",
                       "--disable-dev-shm-usage",
                       "--disable-blink-features=AutomationControlled",
                       "--blink-settings=imagesEnabled=false");
    }

    /**
     * Aborts requests that can never contribute to a product record. Shared so
     * that the favourites re-scrape path in {@code ScraperService} cannot drift
     * away from the main scrape path — it previously duplicated these rules.
     */
    public static void aplicarBloqueosDeRed(Page page) {
        page.route("**/*.{woff,woff2,ttf,otf}", r -> r.abort());
        page.route("**/analytics**", r -> r.abort());
        page.route("**/gtag**",       r -> r.abort());
        page.route("**/hotjar**",     r -> r.abort());
    }

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
                .setArgs(launchArgs()))) {
            try (BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1366, 768)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                    .setLocale("es-AR"))) {
                try (Page page = ctx.newPage()) {
                    page.addInitScript(STEALTH_INIT_SCRIPT);
                    page.setDefaultTimeout(config.getTimeoutMs());
                    aplicarBloqueosDeRed(page);
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
