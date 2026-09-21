package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaSodimm — SODIMM (notebook) never enters a desktop build")
class ReglaSodimmTest {

    private final ReglaSodimm regla = new ReglaSodimm();
    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    @Test
    @DisplayName("vetoes SODIMM unconditionally — tipoMemoria is always asserted, never abstains")
    void vetaSodimm() {
        TechSpecs ram = new TechSpecs("", "DDR4", "", 0, 0, "SODIMM");

        assertThat(regla.permite(ram, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto DIMM")
    void noVetaDimm() {
        TechSpecs ram = new TechSpecs("", "DDR4", "", 0, 0, "DIMM");

        assertThat(regla.permite(ram, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
