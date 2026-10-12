package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * El refresco a mitad de run solo es seguro si el clearance nuevo sirve para el MISMO UA que ya
 * tiene el contexto (Playwright no permite cambiarlo en vivo). Si el UA no coincide, se rechaza el
 * ticket en vez de reintentar contra un challenge que no va a ceder.
 */
@Epic("Scraping Engine")
@Feature("Cloudflare")
@DisplayName("BaseScraper — re-solve del clearance a mitad de run")
class BaseScraperCloudflareRefreshTest {

    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) Chrome/152.0.0.0 Safari/537.36";

    private HttpServer server;
    private final AtomicReference<String> uaToReturn = new AtomicReference<>(UA);

    @BeforeEach
    void startStub() throws IOException {
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
        String body = """
                {"status":"ok","message":"","solution":{"url":"https://fullh4rd.com.ar/","status":200,
                "headers":{},"response":"<html></html>","cookies":[{"name":"cf_clearance","value":"fresco"}],
                "userAgent":"%s"}}
                """.formatted(uaToReturn.get());
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private BaseScraper scraper() {
        String solverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new BaseScraper(new ScraperConfig(), "Fullh4rd", "https://fullh4rd.com.ar", List.of(),
                (page, ctx) -> {
                    throw new UnsupportedOperationException("este test no scrapea");
                },
                () -> "http://127.0.0.1:1",
                url -> new FlareSolverrClient(solverUrl));
    }

    @Test
    @DisplayName("refresca el clearance cuando el UA del ticket coincide con el del contexto")
    void refrescaConUaCoincidente() {
        BrowserContext ctx = mock(BrowserContext.class);

        assertThat(scraper().refrescarCloudflare(ctx, UA)).isTrue();

        verify(ctx).addCookies(argThat(BaseScraperCloudflareRefreshTest::traeClearance));
    }

    @Test
    @DisplayName("rechaza el ticket si el UA no coincide: reintentar no serviría")
    void noRefrescaConUaDistinto() {
        BrowserContext ctx = mock(BrowserContext.class);

        assertThat(scraper().refrescarCloudflare(ctx, "otro-ua")).isFalse();

        verify(ctx, never()).addCookies(anyList());
    }

    @Test
    @DisplayName("sin bypass armado (sin UA) no refresca")
    void noRefrescaSinUaArmado() {
        BrowserContext ctx = mock(BrowserContext.class);

        assertThat(scraper().refrescarCloudflare(ctx, null)).isFalse();

        verify(ctx, never()).addCookies(anyList());
    }

    @Test
    @DisplayName("acota la cantidad de refrescos por run")
    void acotaLosRefrescos() {
        BrowserContext ctx = mock(BrowserContext.class);
        BaseScraper bs = scraper();

        for (int i = 1; i <= BaseScraper.MAX_REFRESCOS_CLOUDFLARE; i++)
            assertThat(bs.refrescarCloudflare(ctx, UA)).as("refresco %d", i).isTrue();
        assertThat(bs.refrescarCloudflare(ctx, UA)).isFalse();

        verify(ctx, times(BaseScraper.MAX_REFRESCOS_CLOUDFLARE)).addCookies(anyList());
    }

    private static boolean traeClearance(List<Cookie> cookies) {
        return cookies.stream().anyMatch(c -> c.name.equals("cf_clearance"));
    }
}
