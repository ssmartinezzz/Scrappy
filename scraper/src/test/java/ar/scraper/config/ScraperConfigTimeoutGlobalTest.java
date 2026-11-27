package ar.scraper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScraperConfig — SCRAPER_TIMEOUT_GLOBAL_MIN, the whole-run budget")
class ScraperConfigTimeoutGlobalTest {

    private static ScraperConfig con(String valor) {
        Properties p = new Properties();
        if (valor != null) p.setProperty("timeout.global.min", valor);
        return new ScraperConfig(p);
    }

    @Test
    void elDefaultSonOchentaMinutos() {
        assertThat(con(null).getTimeoutGlobalMin(null)).isEqualTo(80);
        assertThat(con(null).getTimeoutGlobalMin("  ")).isEqualTo(80);
    }

    @Test
    void laPropertyCambiaElDefault() {
        assertThat(con("120").getTimeoutGlobalMin(null)).isEqualTo(120);
    }

    @Test
    void laVariableDeEntornoPisaLaProperty() {
        assertThat(con("120").getTimeoutGlobalMin("95")).isEqualTo(95);
        assertThat(con(null).getTimeoutGlobalMin(" 30 ")).isEqualTo(30);
    }

    @Test
    void unValorInvalidoVuelveAlDefaultEnVezDeAcortarLaCorrida() {
        assertThat(con(null).getTimeoutGlobalMin("abc")).isEqualTo(80);
        assertThat(con(null).getTimeoutGlobalMin("0")).isEqualTo(80);
        assertThat(con(null).getTimeoutGlobalMin("-5")).isEqualTo(80);
    }
}
