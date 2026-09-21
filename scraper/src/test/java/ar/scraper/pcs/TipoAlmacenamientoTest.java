package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TipoAlmacenamiento — DESCONOCIDO does not participate in the NVME/SSD/HDD scale")
class TipoAlmacenamientoTest {

    @Test
    void nvmeSsdHddAreKnown() {
        assertThat(TipoAlmacenamiento.NVME.esConocido()).isTrue();
        assertThat(TipoAlmacenamiento.SSD.esConocido()).isTrue();
        assertThat(TipoAlmacenamiento.HDD.esConocido()).isTrue();
    }

    @Test
    void desconocidoIsNotKnown() {
        assertThat(TipoAlmacenamiento.DESCONOCIDO.esConocido()).isFalse();
    }
}
