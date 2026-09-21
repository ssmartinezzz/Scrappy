package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Certificacion — alcanza(minimo) compares the real 80 PLUS scale, NINGUNA included")
class CertificacionTest {

    @Test
    void goldAlcanzaBronzeYSeAlcanzaASiMisma() {
        assertThat(Certificacion.GOLD.alcanza(Certificacion.BRONZE)).isTrue();
        assertThat(Certificacion.GOLD.alcanza(Certificacion.GOLD)).isTrue();
    }

    @Test
    void bronzeNoAlcanzaGold() {
        assertThat(Certificacion.BRONZE.alcanza(Certificacion.GOLD)).isFalse();
    }

    @Test
    void ningunaSoloAlcanzaNinguna() {
        assertThat(Certificacion.NINGUNA.alcanza(Certificacion.NINGUNA)).isTrue();
        assertThat(Certificacion.NINGUNA.alcanza(Certificacion.WHITE)).isFalse();
    }

    @Test
    void titaniumAlcanzaTodo() {
        for (Certificacion c : Certificacion.values()) {
            assertThat(Certificacion.TITANIUM.alcanza(c)).isTrue();
        }
    }
}
