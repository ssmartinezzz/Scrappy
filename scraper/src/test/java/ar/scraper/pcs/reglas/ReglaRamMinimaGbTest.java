package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaRamMinimaGb — capacidadGb() del kit contra el piso de RAM pedido")
class ReglaRamMinimaGbTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs kit(int gb) {
        return TechSpecs.builder().capacidadGb(gb).build();
    }

    @Test
    void noFiltraCuandoNoSePidioRam() {
        assertThat(new ReglaRamMinimaGb(null).permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    void permiteExactamenteElPiso() {
        assertThat(new ReglaRamMinimaGb(32).permite(kit(32), contexto)).isTrue();
    }

    @Test
    void permiteMasQueElPiso() {
        assertThat(new ReglaRamMinimaGb(32).permite(kit(64), contexto)).isTrue();
    }

    @Test
    void vetaPorDebajoDelPiso() {
        assertThat(new ReglaRamMinimaGb(32).permite(kit(16), contexto)).isFalse();
    }

    @Test
    void laAbstencionVetaCuandoSePidioRam() {
        assertThat(new ReglaRamMinimaGb(8).permite(kit(0), contexto)).isFalse();
    }

    @Test
    void elMotivoNombraElPisoPedido() {
        assertThat(new ReglaRamMinimaGb(32).motivo()).contains("32").contains("RAM");
    }
}
