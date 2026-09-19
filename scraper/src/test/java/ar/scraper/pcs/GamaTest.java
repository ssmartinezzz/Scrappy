package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Gama — DESCONOCIDA does not participate in the BAJA/MEDIA/ALTA scale")
class GamaTest {

    @Test
    void bajaMediaAltaAreKnown() {
        assertThat(Gama.BAJA.esConocida()).isTrue();
        assertThat(Gama.MEDIA.esConocida()).isTrue();
        assertThat(Gama.ALTA.esConocida()).isTrue();
    }

    @Test
    void desconocidaIsNotKnown() {
        assertThat(Gama.DESCONOCIDA.esConocida()).isFalse();
    }
}
