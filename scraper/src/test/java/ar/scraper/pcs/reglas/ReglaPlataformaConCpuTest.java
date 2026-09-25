package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaPlataformaConCpu — mother.socket vs los sockets con CPU elegible (T13, pc-builder-homelab)")
class ReglaPlataformaConCpuTest {

    private final ReglaPlataformaConCpu regla = new ReglaPlataformaConCpu();

    private ContextoDeArmado contextoCon(Set<String> socketsConCpuElegible) {
        return ContextoDeArmado.inicial(450, Gama.BAJA, Certificacion.NINGUNA, PreferenciasDeArmado.NINGUNA,
                socketsConCpuElegible);
    }

    @Test
    @DisplayName("vetoes a mother whose socket has no eligible CPU in the pool")
    void vetaCuandoElSocketNoTieneCpuElegible() {
        TechSpecs mother = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(mother, contextoCon(Set.of("LGA1700")))).isFalse();
    }

    @Test
    @DisplayName("does not veto a mother whose socket does have an eligible CPU")
    void noVetaCuandoElSocketTieneCpuElegible() {
        TechSpecs mother = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(mother, contextoCon(Set.of("AM5", "LGA1700")))).isTrue();
    }

    @Test
    @DisplayName("no platform qualifies (empty set) — falls back to unrestricted, never vetoes")
    void noVetaCuandoNingunaPlataformaCalifica() {
        TechSpecs mother = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(mother, contextoCon(Set.of()))).isTrue();
    }

    @Test
    @DisplayName("abstains when the mother's own socket did not parse")
    void abstieneCuandoLaMotherNoDeclaraSocket() {
        assertThat(regla.permite(TechSpecs.EMPTY, contextoCon(Set.of("LGA1700")))).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
