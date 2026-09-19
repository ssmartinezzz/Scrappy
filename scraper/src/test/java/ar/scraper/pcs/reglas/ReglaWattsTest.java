package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaWatts — fuente.watts vs the context's watts floor")
class ReglaWattsTest {

    private final ReglaWatts regla = new ReglaWatts();

    @Test
    @DisplayName("vetoes when the psu is below the floor")
    void vetaCuandoNoAlcanzaElPiso() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);
        TechSpecs fuente = new TechSpecs("", "", "", 400, 0, "");

        assertThat(regla.permite(fuente, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto when the psu meets the floor")
    void noVetaCuandoAlcanzaElPiso() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);
        TechSpecs fuente = new TechSpecs("", "", "", 450, 0, "");

        assertThat(regla.permite(fuente, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the psu did not state a wattage")
    void abstieneCuandoNoDeclaraPotencia() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
