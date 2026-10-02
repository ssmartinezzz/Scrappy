package ar.scraper.pcs;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("PC Builder")
@Feature("TechSpecs")
@DisplayName("TechSpecs — builder")
class TechSpecsBuilderTest {

    private static final List<String> SOCKETS = List.of("AM5");

    @Test
    void emptyBuilderIsTheAbstentionInstance() {
        assertThat(TechSpecs.builder().build()).isEqualTo(TechSpecs.EMPTY);
    }

    @Test
    void builderDefaultsAreEveryAbstentionSentinel() {
        TechSpecs s = TechSpecs.builder().build();

        assertThat(s.socket()).isEmpty();
        assertThat(s.ddr()).isEmpty();
        assertThat(s.formFactor()).isEmpty();
        assertThat(s.watts()).isZero();
        assertThat(s.capacidadGb()).isZero();
        assertThat(s.tipoMemoria()).isEmpty();
        assertThat(s.gama()).isEqualTo(Gama.DESCONOCIDA);
        assertThat(s.certificacion()).isEqualTo(Certificacion.NINGUNA);
        assertThat(s.velocidadMhz()).isZero();
        assertThat(s.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.DESCONOCIDO);
        assertThat(s.socketsSoportados()).isEmpty();
        assertThat(s.marcaChip()).isEmpty();
        assertThat(s.generacion()).isZero();
        assertThat(s.tierChipset()).isZero();
        assertThat(s.modulos()).isZero();
        assertThat(s.wifi()).isFalse();
        assertThat(s.tipoCooler()).isEqualTo(TipoCooler.DESCONOCIDO);
        assertThat(s.nivel()).isZero();
        assertThat(s.tamanioGabinete()).isEqualTo(TamanioGabinete.DESCONOCIDO);
        assertThat(s.radiadorMm()).isZero();
        assertThat(s.claseDisipador()).isEqualTo(ClaseDisipador.DESCONOCIDA);
        assertThat(s.heatpipes()).isZero();
    }

    @Test
    void toBuilderRoundTripsAFullyPopulatedSpec() {
        TechSpecs full = new TechSpecs("AM5", "DDR5", "ATX", 650, 32, "UDIMM", Gama.ALTA, Certificacion.GOLD,
                6000, TipoAlmacenamiento.NVME, SOCKETS, "AMD", 4, 7, 2, true, TipoCooler.AIRE, 3,
                TamanioGabinete.FULL, 360, ClaseDisipador.TORRE, 6);

        assertThat(full.toBuilder().build()).isEqualTo(full);
        assertThat(full.toBuilder().watts(1).build().watts()).isEqualTo(1);
    }
}
