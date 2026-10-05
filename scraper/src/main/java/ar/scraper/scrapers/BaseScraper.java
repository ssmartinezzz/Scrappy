package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.pages.CatalogPage;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ar.scraper.security.egress.EgressProxy;
import java.util.List;
import java.util.function.Supplier;

public final class BaseScraper {

    record PageContext(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls) {}

    @FunctionalInterface
    interface PageFactory {
        CatalogPage crear(Page page, PageContext ctx);
    }

    static final String STEALTH_INIT_SCRIPT = """
            // Spoof: navigator.webdriver, navigator.plugins, navigator.languages, window.chrome
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            Object.defineProperty(navigator, 'plugins',   {get: () => [1, 2, 3, 4, 5]});
            Object.defineProperty(navigator, 'languages', {get: () => ['es-AR', 'es', 'en']});
            window.chrome = { runtime: {} };
            """;

    /**
     * {@code imagesEnabled=false} is the load-bearing one: the scraper stores the image URL and
     * never reads a single image byte, yet fetching them anyway accounted for 9.5 MB of the 12.7 MB
     * transferred on a measured Tiendanube listing page.
     */
    static List<String> launchArgs() {
        return List.of("--no-sandbox",
                       "--disable-dev-shm-usage",
                       "--disable-blink-features=AutomationControlled",
                       "--blink-settings=imagesEnabled=false");
    }

    /**
     * Chromium goes through the egress proxy because {@code page.route} never sees the later hops of a
     * redirect chain; the proxy is the only place that can refuse an internal destination on every hop.
     */
    static BrowserType.LaunchOptions launchOptions(boolean headless, String proxyServer) {
        return new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(launchArgs())
                .setProxy(new Proxy(proxyServer));
    }

    /** Aborts requests that can never contribute to a product record. */
    static void aplicarBloqueosDeRed(Page page) {
        page.route("**/*.{woff,woff2,ttf,otf}", r -> r.abort());
        page.route("**/analytics**", r -> r.abort());
        page.route("**/gtag**",       r -> r.abort());
        page.route("**/hotjar**",     r -> r.abort());
    }

    private static final Logger log = LoggerFactory.getLogger(BaseScraper.class);
    private final String sitio;
    private final ScraperConfig config;
    private final PageContext pageContext;
    private final PageFactory pageFactory;
    private final Supplier<String> proxyServer;

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory) {
        this(config, sitio, baseUrl, extraUrls, pageFactory, () -> EgressProxy.shared().server());
    }

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory,
                Supplier<String> proxyServer) {
        this.proxyServer = proxyServer;
        this.sitio = sitio;
        this.config = config;
        this.pageContext = new PageContext(config, sitio, baseUrl, extraUrls);
        this.pageFactory = pageFactory;
    }

    public ScrapeResult ejecutar(Playwright pw) {
        long t0 = System.currentTimeMillis();
        try (Browser browser = pw.chromium().launch(launchOptions(config.isHeadless(), proxyServer.get()))) {
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

    List<Product> scrape(Page page) {
        return pageFactory.crear(page, pageContext).scrapeAll();
    }
}
