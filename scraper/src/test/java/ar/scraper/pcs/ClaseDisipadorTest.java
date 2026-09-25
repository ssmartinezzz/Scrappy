package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaseDisipador — DESCONOCIDA does not participate in the DOBLE_TORRE/TORRE scale")
class ClaseDisipadorTest {

    @Test
    void dobleTorreYTorreAreKnown() {
        assertThat(ClaseDisipador.DOBLE_TORRE.esConocida()).isTrue();
        assertThat(ClaseDisipador.TORRE.esConocida()).isTrue();
    }

    @Test
    void desconocidaIsNotKnown() {
        assertThat(ClaseDisipador.DESCONOCIDA.esConocida()).isFalse();
    }
}
