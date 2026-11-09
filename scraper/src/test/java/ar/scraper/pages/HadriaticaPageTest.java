package ar.scraper.pages;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("HadriaticaPage")
class HadriaticaPageTest {

    private static final String BASE = "https://www.hadriatica.com/productos/";

    @Test
    @DisplayName("es una TiendanubePage")
    void esUnaTiendanubePage() {
        assertThat(new HadriaticaPage(null, 0, "Hadriatica", BASE, 0, 1, List.of(), 520))
                .isInstanceOf(TiendanubePage.class);
    }

    @Test
    @DisplayName("no scrollea ni espera: el listado ya viene renderizado del server")
    void noEsperaElListado() {
        Page page = mock(Page.class);
        new HadriaticaPage(page, 0, "Hadriatica", BASE, 0, 1, List.of(), 520).esperarListado();
        verifyNoInteractions(page);
    }

    @Test
    @DisplayName("la base sigue scrolleando y esperando un segundo")
    void laBaseSigueEsperando() {
        Page page = mock(Page.class);
        new TiendanubePage(page, 0, "x", "https://x.com/productos/", 0, 1).esperarListado();
        verify(page).evaluate(anyString());
        verify(page).waitForTimeout(1000);
    }
}
