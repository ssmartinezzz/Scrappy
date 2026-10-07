package ar.scraper.web;

import ar.scraper.config.ScraperConfig.SiteConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrdenDeSitios — longest first")
class OrdenDeSitiosTest {

    private static SiteConfig sitio(String nombre) {
        return new SiteConfig(nombre, "https://" + nombre + ".test", "indumentaria");
    }

    private static List<String> nombres(List<SiteConfig> sitios) {
        return sitios.stream().map(SiteConfig::nombre).toList();
    }

    @Test
    @DisplayName("sorts by historical duration, longest first")
    void longestFirst() {
        var orden = OrdenDeSitios.masLargosPrimero(
                List.of(sitio("a"), sitio("b"), sitio("c")),
                Map.of("a", 10L, "b", 30L, "c", 20L));

        assertThat(nombres(orden)).containsExactly("b", "c", "a");
    }

    @Test
    @DisplayName("sites without history go first, in config order")
    void unknownFirstInConfigOrder() {
        var orden = OrdenDeSitios.masLargosPrimero(
                List.of(sitio("a"), sitio("nuevo1"), sitio("b"), sitio("nuevo2")),
                Map.of("a", 10L, "b", 30L));

        assertThat(nombres(orden)).containsExactly("nuevo1", "nuevo2", "b", "a");
    }

    @Test
    @DisplayName("ties keep config order")
    void tiesAreStable() {
        var orden = OrdenDeSitios.masLargosPrimero(
                List.of(sitio("a"), sitio("b"), sitio("c")),
                Map.of("a", 10L, "b", 10L, "c", 10L));

        assertThat(nombres(orden)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("names are matched through the sitio_key normalization")
    void keyedBySitioKey() {
        var orden = OrdenDeSitios.masLargosPrimero(
                List.of(sitio("Monky Force"), sitio("Vaypol")),
                Map.of("monkyforce", 50L, "vaypol", 900L));

        assertThat(nombres(orden)).containsExactly("Vaypol", "Monky Force");
    }

    @Test
    @DisplayName("an empty history leaves the config order untouched")
    void emptyHistory() {
        var original = List.of(sitio("a"), sitio("b"));

        assertThat(OrdenDeSitios.masLargosPrimero(original, Map.of())).isEqualTo(original);
    }
}
