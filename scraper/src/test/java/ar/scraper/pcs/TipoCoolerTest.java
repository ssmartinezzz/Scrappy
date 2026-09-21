package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TipoCooler — DESCONOCIDO does not participate in the LIQUIDO/AIRE scale")
class TipoCoolerTest {

    @Test
    void liquidoYAireAreKnown() {
        assertThat(TipoCooler.LIQUIDO.esConocido()).isTrue();
        assertThat(TipoCooler.AIRE.esConocido()).isTrue();
    }

    @Test
    void desconocidoIsNotKnown() {
        assertThat(TipoCooler.DESCONOCIDO.esConocido()).isFalse();
    }
}
