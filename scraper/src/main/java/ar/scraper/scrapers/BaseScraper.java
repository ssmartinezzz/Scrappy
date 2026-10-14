package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.pages.CatalogPage;
import ar.scraper.pages.CloudflareBlockedException;
import ar.scraper.pages.CloudflareSolver;
import ar.scraper.scrape.CloudflareListener;
import ar.scraper.scrape.StatusEvent.ScrapeCloudflare;
import ar.scraper.scrape.StatusEvent.ScrapeCloudflare.Estado;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Proxy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ar.scraper.security.egress.EgressProxy;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class BaseScraper {

    static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";

    record PageContext(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls,
                       CloudflareSolver cloudflare) {}

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

    /** Tope de refrescos de clearance por run: evita loops si el challenge no cede. */
    static final int MAX_REFRESCOS_CLOUDFLARE = 3;

    private static final int MAX_DETALLE = 80;

    private final String sitio;
    private final ScraperConfig config;
    private final PageContext pageContext;
    private final PageFactory pageFactory;
    private final Supplier<String> proxyServer;
    private final Function<String, FlareSolverrClient> solverFactory;
    private final CloudflareListener cloudflareListener;
    private String cloudflareUserAgent;
    private int refrescos;

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory) {
        this(config, sitio, baseUrl, extraUrls, pageFactory, () -> EgressProxy.shared().server());
    }

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory,
                CloudflareListener cloudflareListener) {
        this(config, sitio, baseUrl, extraUrls, pageFactory, () -> EgressProxy.shared().server(),
                FlareSolverrClient::new, cloudflareListener);
    }

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory,
                Supplier<String> proxyServer) {
        this(config, sitio, baseUrl, extraUrls, pageFactory, proxyServer, FlareSolverrClient::new);
    }

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory,
                Supplier<String> proxyServer, Function<String, FlareSolverrClient> solverFactory) {
        this(config, sitio, baseUrl, extraUrls, pageFactory, proxyServer, solverFactory, CloudflareListener.NOOP);
    }

    BaseScraper(ScraperConfig config, String sitio, String baseUrl, List<String> extraUrls, PageFactory pageFactory,
                Supplier<String> proxyServer, Function<String, FlareSolverrClient> solverFactory,
                CloudflareListener cloudflareListener) {
        this.cloudflareListener = cloudflareListener;
        this.proxyServer = proxyServer;
        this.solverFactory = solverFactory;
        this.sitio = sitio;
        this.config = config;
        this.pageContext = new PageContext(config, sitio, baseUrl, extraUrls, this::refrescar);
        this.pageFactory = pageFactory;
    }

    public ScrapeResult ejecutar(Playwright pw) {
        long t0 = System.currentTimeMillis();
        boolean requiereCloudflare = config.requiereBypassCloudflare(sitio);
        FlareSolverrClient.Ticket cloudflare = resolverCloudflareSiHaceFalta(requiereCloudflare);
        if (requiereCloudflare && cloudflare == null) {
            long ms = System.currentTimeMillis() - t0;
            String msg = "bloqueado por Cloudflare: no se pudo resolver el challenge (FlareSolverr en "
                    + config.getFlareSolverrUrl() + ")";
            log.warn("[{}] {}", sitio, msg);
            return new ScrapeResult(sitio, List.of(), msg, ms);
        }
        cloudflareUserAgent = requiereCloudflare ? userAgentDe(cloudflare) : null;
        try (Browser browser = pw.chromium().launch(launchOptions(config.isHeadless(), proxyServer.get()))) {
            try (BrowserContext ctx = browser.newContext(newContextOptions(cloudflare))) {
                if (cloudflare != null) aplicarCookiesCloudflare(ctx, cloudflare);
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
            if (e instanceof CloudflareBlockedException) {
                avisar(Estado.BLOQUEADA, StringUtils.abbreviate(e.getMessage(), MAX_DETALLE));
            }
            return new ScrapeResult(sitio, List.of(), e.getMessage(), ms);
        }
    }

    /** One solve per run: the clearance cookie + UA that solved it are replayed on our own context. */
    private FlareSolverrClient.Ticket resolverCloudflareSiHaceFalta(boolean requiereCloudflare) {
        if (!requiereCloudflare) return null;
        String url = config.getFlareSolverrUrl();
        if (StringUtils.isBlank(url)) {
            log.warn("[{}] requiere bypass de Cloudflare pero scraper.flaresolverr.url esta vacio", sitio);
            avisar(Estado.BLOQUEADA, "FlareSolverr no disponible: sin URL configurada");
            return null;
        }
        log.info("[{}] resolviendo challenge de Cloudflare via FlareSolverr ({})", sitio, url);
        avisar(Estado.DETECTADA, "resolviendo via FlareSolverr");
        FlareSolverrClient.Ticket ticket = resolverCon(url).orElse(null);
        if (ticket == null) avisar(Estado.BLOQUEADA, "no se pudo resolver el challenge");
        else avisar(Estado.RESUELTA, "clearance obtenido");
        return ticket;
    }

    /** Seam para re-resolver a mitad de run: reusa el clearance solo si sirve para el UA ya armado. */
    private boolean refrescar(BrowserContext ctx) {
        return refrescarCloudflare(ctx, cloudflareUserAgent);
    }

    boolean refrescarCloudflare(BrowserContext ctx, String uaArmado) {
        if (StringUtils.isBlank(uaArmado)) return false;
        if (refrescos >= MAX_REFRESCOS_CLOUDFLARE) {
            log.warn("[{}] tope de {} refrescos de Cloudflare alcanzado en este run",
                    sitio, MAX_REFRESCOS_CLOUDFLARE);
            avisar(Estado.BLOQUEADA, "tope de " + MAX_REFRESCOS_CLOUDFLARE + " refrescos alcanzado");
            return false;
        }
        String url = config.getFlareSolverrUrl();
        if (StringUtils.isBlank(url)) {
            avisar(Estado.BLOQUEADA, "FlareSolverr no disponible: sin URL configurada");
            return false;
        }
        FlareSolverrClient.Ticket ticket = resolverCon(url).orElse(null);
        if (ticket == null) {
            log.warn("[{}] no se pudo re-resolver el challenge para refrescar el clearance", sitio);
            avisar(Estado.BLOQUEADA, "no se pudo refrescar el clearance");
            return false;
        }
        if (!uaArmado.equals(userAgentDe(ticket))) {
            // Playwright no permite cambiar el UA de un contexto vivo: reintentar no serviría.
            log.warn("[{}] el UA del ticket no coincide con el del contexto; no se refresca", sitio);
            avisar(Estado.BLOQUEADA, "el UA del clearance nuevo no coincide");
            return false;
        }
        aplicarCookiesCloudflare(ctx, ticket);
        refrescos++;
        log.info("[{}] clearance refrescado ({}/{})", sitio, refrescos, MAX_REFRESCOS_CLOUDFLARE);
        avisar(Estado.RESUELTA, "clearance refrescado (" + refrescos + "/" + MAX_REFRESCOS_CLOUDFLARE + ")");
        return true;
    }

    private void avisar(Estado estado, String detalle) {
        cloudflareListener.reportar(new ScrapeCloudflare(sitio, estado, detalle));
    }

    private Optional<FlareSolverrClient.Ticket> resolverCon(String url) {
        return solverFactory.apply(url).resolver(pageContext.baseUrl(), config.getTimeoutMs());
    }

    /** UA efectivo del contexto: el del ticket si vino, si no el default. */
    private static String userAgentDe(FlareSolverrClient.Ticket cloudflare) {
        return cloudflare != null && StringUtils.isNotBlank(cloudflare.userAgent())
                ? cloudflare.userAgent()
                : DEFAULT_USER_AGENT;
    }

    private Browser.NewContextOptions newContextOptions(FlareSolverrClient.Ticket cloudflare) {
        return new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setUserAgent(userAgentDe(cloudflare))
                .setLocale("es-AR");
    }

    /** Replays the cf_clearance/__cf_bm cookies FlareSolverr earned; nothing else is carried over. */
    private void aplicarCookiesCloudflare(BrowserContext ctx, FlareSolverrClient.Ticket cloudflare) {
        final String host = hostDe(pageContext.baseUrl());
        if (StringUtils.isBlank(host)) {
            log.warn("[{}] no se pudo derivar el host de {} para las cookies de Cloudflare", sitio, pageContext.baseUrl());
            return;
        }
        List<Cookie> cookies = cloudflare.cookies().stream()
                .map(c -> new Cookie(c.name(), c.value())
                        .setDomain("." + host)
                        .setPath("/")
                        .setSecure(true)
                        .setHttpOnly(c.name().equals("cf_clearance")))
                .toList();
        ctx.addCookies(cookies);
        log.debug("[{}] {} cookies de Cloudflare aplicadas al contexto", sitio, cookies.size());
    }

    private static String hostDe(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    List<Product> scrape(Page page) {
        return pageFactory.crear(page, pageContext).scrapeAll();
    }
}
