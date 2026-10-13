package ar.scraper.pages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AmbiancedecoPage")
class AmbiancedecoPageTest {

    private static final String BASE = "https://www.ambiancedeco.com.ar/productos/";

    private AmbiancedecoPage page() {
        return new AmbiancedecoPage(null, 0, "Ambiancedeco", BASE, 0, 1_000_000, List.of(), 5);
    }

    @Test
    @DisplayName("es una TiendanubePage")
    void esUnaTiendanubePage() {
        assertThat(page()).isInstanceOf(TiendanubePage.class);
    }

    @Test
    @DisplayName("desde p2 la siguiente es ?page=3, no el link de la pagina actual")
    void desdeP2LaSiguienteEsP3() {
        assertThat(page().nextPageUrl(BASE, 2)).isEqualTo(BASE + "?page=3");
    }

    @Test
    @DisplayName("desde p1 la siguiente es ?page=2")
    void desdeP1LaSiguienteEsP2() {
        assertThat(page().nextPageUrl(BASE, 1)).isEqualTo(BASE + "?page=2");
    }

    @Test
    @DisplayName("una base que ya trae page= no acumula parametros")
    void baseConPageNoAcumula() {
        assertThat(AmbiancedecoPage.siguientePagina(BASE + "?page=2", 2)).isEqualTo(BASE + "?page=3");
    }
}
