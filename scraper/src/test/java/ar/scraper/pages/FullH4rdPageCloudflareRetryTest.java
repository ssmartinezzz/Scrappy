package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El challenge puede reaparecer a mitad de un crawl largo (clearance vencido). El page debe
 * pedir un clearance fresco y reintentar la MISMA página, en vez de perder el run entero.
 */
@Epic("Scraping Engine")
@Feature("Cloudflare")
@DisplayName("FullH4rdPage — re-solve del challenge a mitad de run")
class FullH4rdPageCloudflareRetryTest {

    private static final String BASE = "https://fullh4rd.com.ar";
    private static final int INTENTOS = 3;

    private static String challenge() {
        return "<html><head><title>Just a moment...</title></head>"
                + "<body><div id=\"challenge-platform\"></div></body></html>";
    }

    private static String dosCards() {
        return """
                <article class="results-card">
                  <a href="/prod/1/producto-a" class="results-card__title-link">PRODUCTO A</a>
                  <p class="results-card__price-current">$1.000,00</p>
                </article>
                <article class="results-card">
                  <a href="/prod/2/producto-b" class="results-card__title-link">PRODUCTO B</a>
                  <p class="results-card__price-current">$2.000,00</p>
                </article>
                """;
    }

    private static FullH4rdPage pageWith(Page page, CloudflareSolver solver) {
        return new FullH4rdPage(page, 1000, "Fullh4rd", BASE, 0, 1_000_000_000, solver);
    }

    @Test
    @DisplayName("con solver que refresca, la página bloqueada se reintenta y el run sigue")
    void refuerzaYContinua() {
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        CloudflareSolver solver = mock(CloudflareSolver.class);
        when(page.context()).thenReturn(ctx);
        when(page.content()).thenReturn(challenge(), dosCards(), "<div>sin cards</div>");
        when(solver.refrescar(ctx)).thenReturn(true);

        List<Product> prods = pageWith(page, solver).scrapeAll();

        assertThat(prods).hasSize(2);
        verify(solver).refrescar(ctx);
    }

    @Test
    @DisplayName("si el solver no puede refrescar, el run corta explícito (nunca un 0 silencioso)")
    void sinRefrescoCortaExplicito() {
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        CloudflareSolver solver = mock(CloudflareSolver.class);
        when(page.context()).thenReturn(ctx);
        when(page.content()).thenReturn(challenge());
        when(solver.refrescar(ctx)).thenReturn(false);

        assertThatThrownBy(() -> pageWith(page, solver).scrapeAll())
                .isInstanceOf(CloudflareBlockedException.class);
    }

    @Test
    @DisplayName("sin bypass armado (solver null) mantiene el comportamiento previo: corta explícito")
    void sinSolverCortaExplicito() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn(challenge());

        assertThatThrownBy(() -> pageWith(page, null).scrapeAll())
                .isInstanceOf(CloudflareBlockedException.class);
    }

    @Test
    @DisplayName("refrescos acotados: un challenge que no cede no genera un loop infinito")
    void refrescosAcotados() {
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        CloudflareSolver solver = mock(CloudflareSolver.class);
        when(page.context()).thenReturn(ctx);
        when(page.content()).thenReturn(challenge());
        when(solver.refrescar(ctx)).thenReturn(true);

        assertThatThrownBy(() -> pageWith(page, solver).scrapeAll())
                .isInstanceOf(CloudflareBlockedException.class);
        verify(solver, atMost(INTENTOS - 1)).refrescar(ctx);
    }
}
