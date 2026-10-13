package ar.scraper.pcs.specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cpu/GpuSpecsReader — rendimiento is the table's score for the model key")
class RendimientoDesdeTablaTest {

    private static final TablaDeRendimiento TABLA =
            clave -> Map.of("core-i5-14600k", 3015, "rx-9070-xt", 813).getOrDefault(clave, 0);

    @Test
    void cpuLeeElPuntajeDeSuClave() {
        assertThat(new CpuSpecsReader(TABLA).leer(Tokens.de("Procesador Intel Core i5 14600K")).rendimiento())
                .isEqualTo(3015);
    }

    @Test
    void gpuLeeElPuntajeDeSuClave() {
        assertThat(new GpuSpecsReader(TABLA).leer(Tokens.de("Radeon RX 9070 XT 16GB")).rendimiento())
                .isEqualTo(813);
    }

    @Test
    void modeloFueraDeLaTablaAbstiene() {
        assertThat(new CpuSpecsReader(TABLA).leer(Tokens.de("Procesador Intel Core i5 14400F")).rendimiento())
                .isZero();
    }

    @Test
    void sinClaveAbstieneAunqueLaTablaResponda() {
        TablaDeRendimiento todoPuntua = clave -> 1;
        assertThat(new CpuSpecsReader(todoPuntua).leer(Tokens.de("Procesador Intel Celeron G5905")).rendimiento())
                .isZero();
    }
}
