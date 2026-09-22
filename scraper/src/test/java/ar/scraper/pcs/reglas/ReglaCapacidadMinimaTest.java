package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaCapacidadMinima — candidato.capacidadGb() contra el piso pedido (fase 9, D3)")
class ReglaCapacidadMinimaTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conCapacidad(int gb) {
        return new TechSpecs("", "", "", 0, gb, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.NVME);
    }

    @Test
    void noFiltraCuandoNoSePidioCapacidad() {
        assertThat(new ReglaCapacidadMinima(null).permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    void permiteExactamenteElPiso() {
        assertThat(new ReglaCapacidadMinima(1024).permite(conCapacidad(1024), contexto)).isTrue();
    }

    @Test
    void permiteMasQueElPiso() {
        // D3: el piso es "al menos", no "exactamente" — un 2 TB no puede caer
        // por pedir 1 TB, que es justo el candidato mejor.
        assertThat(new ReglaCapacidadMinima(1024).permite(conCapacidad(2048), contexto)).isTrue();
    }

    @Test
    void vetaPorDebajoDelPiso() {
        assertThat(new ReglaCapacidadMinima(1024).permite(conCapacidad(512), contexto)).isFalse();
    }

    @Test
    void laAbstencionVetaCuandoSePidioCapacidad() {
        // D2: de un nombre sin capacidad legible no se puede afirmar que
        // llegue al piso. Sin pedido no veta a nadie (primer test).
        assertThat(new ReglaCapacidadMinima(512).permite(conCapacidad(0), contexto)).isFalse();
    }

    @Test
    void elMotivoNombraElPisoPedido() {
        assertThat(new ReglaCapacidadMinima(1024).motivo()).contains("1024");
    }
}
