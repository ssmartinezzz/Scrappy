package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaGama — candidato.gama() vs la gama pedida; D2: la unica regla donde la abstencion veta")
class ReglaGamaTest {

    private final ReglaGama regla = new ReglaGama();

    @Test
    @DisplayName("does not veto when the candidate's gama matches the requested one")
    void noVetaCuandoCoincideConLaPedida() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450, Gama.ALTA, ar.scraper.pcs.Certificacion.NINGUNA);
        TechSpecs cpu = new TechSpecs("", "", "", 0, 0, "", Gama.ALTA, ar.scraper.pcs.Certificacion.NINGUNA);

        assertThat(regla.permite(cpu, contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes when the candidate's gama is known but different from the requested one")
    void vetaCuandoDifiereDeLaPedida() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450, Gama.ALTA, ar.scraper.pcs.Certificacion.NINGUNA);
        TechSpecs cpu = new TechSpecs("", "", "", 0, 0, "", Gama.MEDIA, ar.scraper.pcs.Certificacion.NINGUNA);

        assertThat(regla.permite(cpu, contexto)).isFalse();
    }

    @Test
    @DisplayName("D2: vetoes on abstention (candidato.gama()==DESCONOCIDA) when a gama was requested — the house's inverse")
    void vetaPorAbstencionCuandoSePidioGama() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450, Gama.ALTA, ar.scraper.pcs.Certificacion.NINGUNA);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not filter at all when no gama was requested (gamaPedida == null)")
    void noFiltraCuandoNoSePidioGama() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
        assertThat(regla.permite(
                new TechSpecs("", "", "", 0, 0, "", Gama.BAJA, ar.scraper.pcs.Certificacion.NINGUNA),
                contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
