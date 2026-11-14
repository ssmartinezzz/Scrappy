package ar.scraper.scrapers;

import ar.scraper.promos.LecturaFallidaException;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PlaywrightPaginaWeb — the site's own page as the promo sources' only way to read the web")
class PlaywrightPaginaWebTest {

    private static Page paginaQueResponde(int status, String html) {
        Page page = mock(Page.class);
        Response respuesta = mock(Response.class);
        when(respuesta.status()).thenReturn(status);
        when(page.navigate(eq("https://x.test/"), any(Page.NavigateOptions.class))).thenReturn(respuesta);
        when(page.content()).thenReturn(html);
        return page;
    }

    @Test
    void devuelveElHtmlRenderizado() {
        assertThat(new PlaywrightPaginaWeb(paginaQueResponde(200, "<html>ok</html>"), 5000).html("https://x.test/"))
                .isEqualTo("<html>ok</html>");
    }

    @Test
    void unStatusDeErrorEsUnaLecturaFallida() {
        for (int status : new int[] {403, 404, 429, 500, 503}) {
            assertThatThrownBy(() -> new PlaywrightPaginaWeb(paginaQueResponde(status, "<html>x</html>"), 5000)
                    .html("https://x.test/"))
                    .isInstanceOf(LecturaFallidaException.class).hasMessageContaining(String.valueOf(status));
        }
    }

    @Test
    void sinRespuestaOConLaNavegacionRota() {
        Page sinRespuesta = mock(Page.class);
        assertThatThrownBy(() -> new PlaywrightPaginaWeb(sinRespuesta, 5000).html("https://x.test/"))
                .isInstanceOf(LecturaFallidaException.class);
        Page rota = mock(Page.class);
        when(rota.navigate(any(String.class), any(Page.NavigateOptions.class)))
                .thenThrow(new RuntimeException("net::ERR_CONNECTION_RESET"));
        assertThatThrownBy(() -> new PlaywrightPaginaWeb(rota, 5000).html("https://x.test/"))
                .isInstanceOf(LecturaFallidaException.class).hasMessageContaining("ERR_CONNECTION_RESET");
    }
}
