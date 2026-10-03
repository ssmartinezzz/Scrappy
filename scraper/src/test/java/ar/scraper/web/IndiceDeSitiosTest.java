package ar.scraper.web;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run lists sites by key ({@code vcp}); a scraper reports by display name ({@code Vcp}).
 * Run 40 (2026-10-02) closed all 29 sites as ERROR "Deadline global" because the two never met.
 */
@Epic("Scraping")
@Feature("Run bookkeeping")
@Story("A finished site is found by its display name")
@DisplayName("IndiceDeSitios — clave del sitio vs. nombre visible")
class IndiceDeSitiosTest {

    private final IndiceDeSitios indice = new IndiceDeSitios(List.of("vcp", "fullh4rd", "harvey"));

    @Test
    @DisplayName("a result named by display name finds its site's slot")
    void elNombreVisibleEncuentraSuLugar() {
        assertThat(indice.de("Vcp")).isZero();
        assertThat(indice.de("Fullh4rd")).isEqualTo(1);
        assertThat(indice.de("Harvey")).isEqualTo(2);
    }

    @Test
    @DisplayName("the site key still finds its slot")
    void laClaveEncuentraSuLugar() {
        assertThat(indice.de("fullh4rd")).isEqualTo(1);
    }

    @Test
    @DisplayName("a site outside the run has no slot")
    void unSitioAjenoNoTieneLugar() {
        assertThat(indice.de("Freres")).isEqualTo(-1);
        assertThat(indice.de("")).isEqualTo(-1);
    }
}
