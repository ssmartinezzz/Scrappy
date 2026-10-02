package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaFormFactor — gabinete.formFactor vs mother.formFactor")
class ReglaFormFactorTest {

    private final ReglaFormFactor regla = new ReglaFormFactor();

    @Test
    @DisplayName("vetoes when the case is smaller than the board")
    void vetaCuandoElGabineteEsMasChico() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(TechSpecs.builder().formFactor("MATX").build());
        TechSpecs gabinete = TechSpecs.builder().formFactor("ITX").build();

        assertThat(regla.permite(gabinete, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto when the case is the same class as the board")
    void noVetaCuandoSonDeLaMismaClase() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(TechSpecs.builder().formFactor("MATX").build());
        TechSpecs gabinete = TechSpecs.builder().formFactor("MATX").build();

        assertThat(regla.permite(gabinete, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto when the case is bigger than the board")
    void noVetaCuandoElGabineteEsMasGrande() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(TechSpecs.builder().formFactor("MATX").build());
        TechSpecs gabinete = TechSpecs.builder().formFactor("ATX").build();

        assertThat(regla.permite(gabinete, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the case did not state a form factor")
    void abstieneCuandoElGabineteNoDeclaraForma() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(TechSpecs.builder().formFactor("MATX").build());

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the mother did not state a form factor")
    void abstieneCuandoElMotherNoDeclaraForma() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(TechSpecs.EMPTY);
        TechSpecs gabinete = TechSpecs.builder().formFactor("ITX").build();

        assertThat(regla.permite(gabinete, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
