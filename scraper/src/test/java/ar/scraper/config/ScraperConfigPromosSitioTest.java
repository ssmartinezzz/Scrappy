package ar.scraper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScraperConfig — PROMOS_SITIO_HABILITADO kill switch for the site-level promo read")
class ScraperConfigPromosSitioTest {

    private static ScraperConfig con(String clave, String valor) {
        Properties p = new Properties();
        if (clave != null) p.setProperty(clave, valor);
        return new ScraperConfig(p);
    }

    @Test
    void porDefectoEstaPrendido() {
        assertThat(con(null, null).isPromosSitioHabilitado(null)).isTrue();
        assertThat(con(null, null).isPromosSitioHabilitado("  ")).isTrue();
    }

    @Test
    void laPropertyLoApaga() {
        assertThat(con("promos.sitio.habilitado", "false").isPromosSitioHabilitado(null)).isFalse();
    }

    @Test
    void laVariableDeEntornoPisaLaProperty() {
        assertThat(con("promos.sitio.habilitado", "true").isPromosSitioHabilitado("false")).isFalse();
        assertThat(con("promos.sitio.habilitado", "false").isPromosSitioHabilitado("true")).isTrue();
    }

    @Test
    void unValorQueNoEsBooleanoNoApagaNada() {
        assertThat(con(null, null).isPromosSitioHabilitado("quizas")).isTrue();
    }
}
