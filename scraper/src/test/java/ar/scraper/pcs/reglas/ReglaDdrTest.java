package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaDdr — ram.ddr vs motherDdr")
class ReglaDdrTest {

    private final ReglaDdr regla = new ReglaDdr();

    @Test
    @DisplayName("vetoes when both ddr generations parsed and differ")
    void vetaCuandoDifierenYAmbosParsearon() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM4", "DDR4", "", 0, 0, ""));
        TechSpecs ram = new TechSpecs("", "DDR5", "", 0, 0, "");

        assertThat(regla.permite(ram, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto when both ddr generations parsed and match")
    void noVetaCuandoCoinciden() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM4", "DDR4", "", 0, 0, ""));
        TechSpecs ram = new TechSpecs("", "DDR4", "", 0, 0, "");

        assertThat(regla.permite(ram, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the ram did not state a ddr generation")
    void abstieneCuandoRamNoDeclaraDdr() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM4", "DDR4", "", 0, 0, ""));

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when motherDdr could not be derived (LGA1700)")
    void abstieneCuandoMotherDdrEsIndeterminado() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("LGA1700", "", "", 0, 0, ""));
        TechSpecs ram = new TechSpecs("", "DDR5", "", 0, 0, "");

        assertThat(regla.permite(ram, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
