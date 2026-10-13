package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EjesTecnicos.RAM — latency in ns (CL·2000/MHz) after módulos, before MHz; abstention last")
class EjesTecnicosLatenciaTest {

    private static TechSpecs ram(int modulos, int mhz, int cl, int gb) {
        return TechSpecs.builder().ddr("DDR5").modulos(modulos).velocidadMhz(mhz).latenciaCl(cl)
                .capacidadGb(gb).build();
    }

    private static void primeroGana(TechSpecs mejor, TechSpecs peor) {
        assertThat(EjesTecnicos.RAM.compare(mejor, peor)).isNegative();
        assertThat(EjesTecnicos.RAM.compare(peor, mejor)).isPositive();
    }

    @Test
    void menorLatenciaGanaAunqueLaOtraTengaMasMhz() {
        // 6000 CL30 = 10.0 ns; 6400 CL40 = 12.5 ns
        primeroGana(ram(2, 6000, 30, 32), ram(2, 6400, 40, 32));
    }

    @Test
    void mismaLatenciaEnNsDesempataPorMhz() {
        // 6000 CL30 = 10.0 ns = 4800 CL24
        primeroGana(ram(2, 6000, 30, 32), ram(2, 4800, 24, 32));
    }

    @Test
    void sinClVaUltimaEnEseEje() {
        primeroGana(ram(2, 5600, 46, 16), ram(2, 6000, 0, 16));
    }

    @Test
    void sinMhzNoHayLatencia() {
        primeroGana(ram(2, 5600, 46, 16), ram(2, 0, 30, 16));
    }

    @Test
    void modulosSiguenAntesQueLaLatencia() {
        primeroGana(ram(2, 5600, 46, 16), ram(1, 6000, 30, 16));
    }
}
