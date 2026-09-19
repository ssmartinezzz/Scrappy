package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextoDeArmado — motherDdr derivation from the chosen board's socket")
class ContextoDeArmadoTest {

    @Test
    @DisplayName("initial context has no mother, empty motherDdr, and the given watts floor")
    void inicialNoTieneMother() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(contexto.motherSpecs()).isEqualTo(TechSpecs.EMPTY);
        assertThat(contexto.motherDdr()).isEmpty();
        assertThat(contexto.wattsMin()).isEqualTo(450);
    }

    @Test
    @DisplayName("keeps the board's own ddr when it parsed")
    void preservaLaDdrPropiaDeLaMother() {
        TechSpecs mother = new TechSpecs("AM5", "DDR5", "MATX", 0, 0, "");

        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(mother);

        assertThat(contexto.motherDdr()).isEqualTo("DDR5");
    }

    @Test
    @DisplayName("derives DDR5 from an AM5 socket when the board states no ddr")
    void derivaDdr5DeAm5() {
        TechSpecs mother = new TechSpecs("AM5", "", "MATX", 0, 0, "");

        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(mother);

        assertThat(contexto.motherDdr()).isEqualTo("DDR5");
    }

    @Test
    @DisplayName("derives DDR5 from an LGA1851 socket when the board states no ddr")
    void derivaDdr5DeLga1851() {
        TechSpecs mother = new TechSpecs("LGA1851", "", "MATX", 0, 0, "");

        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(mother);

        assertThat(contexto.motherDdr()).isEqualTo("DDR5");
    }

    @Test
    @DisplayName("derives DDR4 from an AM4 socket when the board states no ddr")
    void derivaDdr4DeAm4() {
        TechSpecs mother = new TechSpecs("AM4", "", "MATX", 0, 0, "");

        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(mother);

        assertThat(contexto.motherDdr()).isEqualTo("DDR4");
    }

    @Test
    @DisplayName("abstains on LGA1700 — mixed platform, phase-1 finding")
    void abstieneEnLga1700() {
        TechSpecs mother = new TechSpecs("LGA1700", "", "MATX", 0, 0, "");

        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(mother);

        assertThat(contexto.motherDdr()).isEmpty();
    }

    @Test
    @DisplayName("abstains when the board states neither ddr nor a derivable socket")
    void abstieneCuandoNoHayNadaQueDerivar() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(TechSpecs.EMPTY);

        assertThat(contexto.motherDdr()).isEmpty();
    }

    @Test
    @DisplayName("conMother keeps the watts floor unchanged")
    void conMotherPreservaElPisoDeWatts() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(650).conMother(TechSpecs.EMPTY);

        assertThat(contexto.wattsMin()).isEqualTo(650);
    }

    // ── T3a: gamaPedida + certificacionMinima ────────────────────────────

    @Test
    @DisplayName("inicial(wattsMin) alone means no gama was requested — null, never Gama.DESCONOCIDA")
    void inicialSinGamaDejaGamaPedidaNulaYCertificacionNinguna() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(contexto.gamaPedida()).isNull();
        assertThat(contexto.certificacionMinima()).isEqualTo(Certificacion.NINGUNA);
    }

    @Test
    @DisplayName("inicial(wattsMin, gamaPedida, certMin) carries both through")
    void inicialConGamaPedidaLaExpone() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(750, Gama.ALTA, Certificacion.GOLD);

        assertThat(contexto.gamaPedida()).isEqualTo(Gama.ALTA);
        assertThat(contexto.certificacionMinima()).isEqualTo(Certificacion.GOLD);
        assertThat(contexto.wattsMin()).isEqualTo(750);
    }

    @Test
    @DisplayName("conMother preserves gamaPedida and certificacionMinima across the mother transition")
    void conMotherPreservaGamaPedidaYCertificacionMinima() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(750, Gama.ALTA, Certificacion.GOLD)
                .conMother(new TechSpecs("AM5", "", "MATX", 0, 0, ""));

        assertThat(contexto.gamaPedida()).isEqualTo(Gama.ALTA);
        assertThat(contexto.certificacionMinima()).isEqualTo(Certificacion.GOLD);
    }
}
