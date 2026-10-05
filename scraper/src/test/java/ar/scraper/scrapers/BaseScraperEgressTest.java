package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.security.egress.EgressProxy;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduces the SSRF probe with a real Chromium: an allowed host answers 302 to an internal server.
 * A {@code page.route} guard sees only the first URL of that chain, so only the proxy can veto the hop.
 * Skipped when no Chromium can be launched (the backend CI installs none).
 */
@Epic("Security")
@Feature("Outbound egress")
@Story("Redirect to an internal host")
@DisplayName("BaseScraper — Chromium cannot be redirected to an internal host")
class BaseScraperEgressTest {

    private static Playwright playwright;

    private HttpServer allowed;
    private HttpServer internal;
    private final AtomicInteger allowedHits = new AtomicInteger();
    private final AtomicInteger internalHits = new AtomicInteger();
    private EgressProxy proxy;

    @BeforeAll
    static void launchChromiumOrSkip() {
        try {
            playwright = Playwright.create();
            playwright.chromium().launch(new BrowserType.LaunchOptions().setArgs(BaseScraper.launchArgs())).close();
        } catch (Exception | LinkageError e) {
            if (playwright != null) playwright.close();
            playwright = null;
            assumeTrue(false, "Chromium cannot be launched here: " + e.getMessage());
        }
    }

    @AfterAll
    static void closePlaywright() {
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void startServers() throws Exception {
        internal = server(internalHits, 200, null);
        allowed = server(allowedHits, 302, "http://127.0.0.1:" + internal.getAddress().getPort() + "/secret");
        proxy = new EgressProxy(host -> "store.test".equals(host)
                ? Optional.of(InetAddress.getLoopbackAddress()) : Optional.empty());
        proxy.start();
    }

    @AfterEach
    void stopServers() throws Exception {
        allowed.stop(0);
        internal.stop(0);
        proxy.close();
    }

    @Test
    void controlWithoutTheProxyTheRedirectReachesTheInternalServer() {
        try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setArgs(BaseScraper.launchArgs()));
             Page page = browser.newPage()) {
            page.navigate("http://127.0.0.1:" + allowed.getAddress().getPort() + "/");
        }

        assertThat(internalHits).hasValueGreaterThanOrEqualTo(1);
    }

    @Test
    void redirectFromAnAllowedHostToAnInternalOneIsNeverFollowed() {
        navigateWithBaseScraper("http://store.test:" + allowed.getAddress().getPort() + "/");

        assertThat(allowedHits).as("the allowed host was reached through the proxy").hasValue(1);
        assertThat(internalHits).as("the internal server must not be hit").hasValue(0);
    }

    @Test
    void navigatingStraightToAnInternalHostIsBlocked() {
        navigateWithBaseScraper("http://127.0.0.1:" + internal.getAddress().getPort() + "/secret");

        assertThat(internalHits).hasValue(0);
    }

    private void navigateWithBaseScraper(String url) {
        ScraperConfig config = mock(ScraperConfig.class);
        when(config.isHeadless()).thenReturn(true);
        when(config.getTimeoutMs()).thenReturn(15_000);
        BaseScraper.PageFactory factory = (page, ctx) -> () -> {
            try {
                page.navigate(url);
            } catch (RuntimeException ignored) {
            }
            return List.of();
        };

        new BaseScraper(config, "probe", url, List.of(), factory, proxy::server).ejecutar(playwright);
    }

    private static HttpServer server(AtomicInteger hits, int status, String location) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", ex -> {
            hits.incrementAndGet();
            if (location != null) ex.getResponseHeaders().add("Location", location);
            byte[] body = "SECRET".getBytes();
            ex.sendResponseHeaders(status, status == 302 ? -1 : body.length);
            if (status != 302) ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        return s;
    }
}
