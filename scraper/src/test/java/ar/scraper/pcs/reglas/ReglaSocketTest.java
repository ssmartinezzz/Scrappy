package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaSocket — cpu.socket vs mother.socket")
class ReglaSocketTest {

    private final ReglaSocket regla = new ReglaSocket();

    @Test
    @DisplayName("vetoes when both sockets parsed and differ")
    void vetaCuandoDifierenYAmbosParsearon() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM4", "", "", 0, 0, ""));
        TechSpecs cpu = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(cpu, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto when both sockets parsed and match")
    void noVetaCuandoCoinciden() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM5", "", "", 0, 0, ""));
        TechSpecs cpu = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(cpu, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the cpu did not state a socket")
    void abstieneCuandoCpuNoDeclaraSocket() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM4", "", "", 0, 0, ""));
        TechSpecs cpu = TechSpecs.EMPTY;

        assertThat(regla.permite(cpu, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the mother did not state a socket")
    void abstieneCuandoMotherNoDeclaraSocket() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(TechSpecs.EMPTY);
        TechSpecs cpu = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(cpu, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
