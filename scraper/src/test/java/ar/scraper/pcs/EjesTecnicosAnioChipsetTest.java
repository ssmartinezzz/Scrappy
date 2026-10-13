package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EjesTecnicos.mother — newer chipset first within the same tier")
class EjesTecnicosAnioChipsetTest {

    private static TechSpecs mother(int tier, int anio) {
        return TechSpecs.builder().socket("AM5").ddr("DDR5").tierChipset(tier).anioChipset(anio).build();
    }

    private static void primeroGana(Gama gama, TechSpecs mejor, TechSpecs peor) {
        assertThat(EjesTecnicos.mother(gama).compare(mejor, peor)).isNegative();
        assertThat(EjesTecnicos.mother(gama).compare(peor, mejor)).isPositive();
    }

    @Test
    void mismoTierGanaElChipsetMasNuevo() {
        primeroGana(null, mother(1, 2024), mother(1, 2022));
        primeroGana(Gama.MEDIA, mother(2, 2025), mother(2, 2022));
    }

    @Test
    void elTierVaAntesQueElAnio() {
        primeroGana(null, mother(1, 2022), mother(2, 2025));
        primeroGana(Gama.MEDIA, mother(2, 2022), mother(3, 2025));
    }

    @Test
    void sinAnioVaUltimoEnEseEje() {
        primeroGana(null, mother(2, 2020), mother(2, 0));
    }
}
