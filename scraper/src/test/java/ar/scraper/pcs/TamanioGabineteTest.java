package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TamanioGabinete — DESCONOCIDO no participa de la escala MINI/MID/FULL")
class TamanioGabineteTest {

    @Test
    void losTresTamaniosRealesSonConocidos() {
        assertThat(TamanioGabinete.MINI.esConocido()).isTrue();
        assertThat(TamanioGabinete.MID.esConocido()).isTrue();
        assertThat(TamanioGabinete.FULL.esConocido()).isTrue();
    }

    @Test
    void desconocidoNoEsConocido() {
        assertThat(TamanioGabinete.DESCONOCIDO.esConocido()).isFalse();
    }
}
