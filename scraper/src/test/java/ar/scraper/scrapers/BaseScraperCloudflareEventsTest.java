package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.pages.CloudflareBlockedException;
import ar.scraper.scrape.CloudflareListener;
import ar.scraper.scrape.StatusEvent.ScrapeCloudflare;
import ar.scraper.scrape.StatusEvent.ScrapeCloudflare.Estado;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Scraping Engine")
@Feature("Cloudflare")
@DisplayName("BaseScraper — reports Cloudflare barriers to its listener")
class BaseScraperCloudflareEventsTest {

    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) Chrome/152.0.0.0 Safari/537.36";

    private HttpServer server;
    private volatile String solverBody;
    private final List<ScrapeCloudflare> heard = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        solverBody = """
                {"status":"ok","message":"","solution":{"url":"https://fullh4rd.com.ar/","status":200,
                "headers":{},"response":"<html></html>","cookies":[{"name":"cf_clearance","value":"x"}],
                "userAgent":"%s"}}
                """.formatted(UA);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", this::handle);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] out = solverBody.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private ScraperConfig config(String solverUrl) {
        ScraperConfig config = mock(ScraperConfig.class);
        when(config.requiereBypassCloudflare("Fullh4rd")).thenReturn(true);
        when(config.getFlareSolverrUrl()).thenReturn(solverUrl);
        when(config.getTimeoutMs()).thenReturn(1000);
        return config;
    }

    private BaseScraper scraper(String solverUrl, BaseScraper.PageFactory pages) {
        String url = server == null ? "" : "http://127.0.0.1:" + server.getAddress().getPort();
        String effective = solverUrl == null ? url : solverUrl;
        return new BaseScraper(config(effective), "Fullh4rd", "https://fullh4rd.com.ar", List.of(), pages,
                () -> "http://127.0.0.1:1", u -> new FlareSolverrClient(u), heard::add);
    }

    private static BaseScraper.PageFactory noPages() {
        return (page, ctx) -> {
            throw new UnsupportedOperationException("este test no scrapea");
        };
    }

    private static Playwright playwrightThatCannotLaunch() {
        Playwright pw = mock(Playwright.class);
        BrowserType type = mock(BrowserType.class);
        when(pw.chromium()).thenReturn(type);
        when(type.launch(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("sin browser"));
        return pw;
    }

    private List<Estado> estados() {
        return heard.stream().map(ScrapeCloudflare::estado).toList();
    }

    @Test
    @DisplayName("up-front solve that succeeds reports detectada then resuelta")
    void upFrontSolveSucceeds() {
        scraper(null, noPages()).ejecutar(playwrightThatCannotLaunch());

        assertThat(estados()).containsExactly(Estado.DETECTADA, Estado.RESUELTA);
        assertThat(heard).allMatch(e -> e.sitio().equals("Fullh4rd"));
    }

    @Test
    @DisplayName("up-front solve that fails reports detectada then bloqueada")
    void upFrontSolveFails() {
        solverBody = "{\"status\":\"error\",\"message\":\"boom\"}";

        var result = scraper(null, noPages()).ejecutar(null);

        assertThat(result.error()).contains("Cloudflare");
        assertThat(estados()).containsExactly(Estado.DETECTADA, Estado.BLOQUEADA);
        assertThat(heard.get(1).detalle()).isNotBlank().hasSizeLessThanOrEqualTo(80);
    }

    @Test
    @DisplayName("an empty solver URL reports bloqueada as unavailable, without a detectada solve")
    void noSolverUrl() {
        scraper("", noPages()).ejecutar(null);

        assertThat(estados()).containsExactly(Estado.BLOQUEADA);
        assertThat(heard.get(0).detalle()).contains("FlareSolverr no disponible");
    }

    @Test
    @DisplayName("a mid-run refresh reports resuelta with the counter")
    void refreshSucceeds() {
        BaseScraper bs = scraper(null, noPages());

        assertThat(bs.refrescarCloudflare(mock(BrowserContext.class), UA)).isTrue();

        assertThat(estados()).containsExactly(Estado.RESUELTA);
        assertThat(heard.get(0).detalle()).contains("1/" + BaseScraper.MAX_REFRESCOS_CLOUDFLARE);
    }

    @Test
    @DisplayName("a mid-run refresh with a different UA reports bloqueada")
    void refreshRejected() {
        BaseScraper bs = scraper(null, noPages());

        assertThat(bs.refrescarCloudflare(mock(BrowserContext.class), "otro-ua")).isFalse();

        assertThat(estados()).containsExactly(Estado.BLOQUEADA);
    }

    @Test
    @DisplayName("hitting the refresh cap reports bloqueada")
    void refreshCap() {
        BaseScraper bs = scraper(null, noPages());
        for (int i = 0; i < BaseScraper.MAX_REFRESCOS_CLOUDFLARE; i++)
            bs.refrescarCloudflare(mock(BrowserContext.class), UA);
        heard.clear();

        assertThat(bs.refrescarCloudflare(mock(BrowserContext.class), UA)).isFalse();

        assertThat(estados()).containsExactly(Estado.BLOQUEADA);
    }

    @Test
    @DisplayName("a CloudflareBlockedException during the scrape reports bloqueada")
    void blockedException() {
        var pw = mockBrowserThrowing(new CloudflareBlockedException("[Fullh4rd] p3: challenge sin resolver"));

        scraper(null, noPages()).ejecutar(pw);

        assertThat(estados()).containsExactly(Estado.DETECTADA, Estado.RESUELTA, Estado.BLOQUEADA);
        assertThat(heard.get(2).detalle()).contains("challenge sin resolver");
    }

    @Test
    @DisplayName("without a listener the no-op default keeps scrapers working")
    void noopListener() {
        CloudflareListener.NOOP.reportar(new ScrapeCloudflare("x", Estado.DETECTADA, "y"));
    }

    private static Playwright mockBrowserThrowing(RuntimeException toThrow) {
        Playwright pw = mock(Playwright.class);
        BrowserType type = mock(BrowserType.class);
        when(pw.chromium()).thenReturn(type);
        when(type.launch(org.mockito.ArgumentMatchers.any())).thenThrow(toThrow);
        return pw;
    }
}
