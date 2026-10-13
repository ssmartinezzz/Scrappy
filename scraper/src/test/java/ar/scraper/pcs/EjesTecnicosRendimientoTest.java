package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EjesTecnicos — rendimiento ranks CPU/GPU within a gama, abstention last")
class EjesTecnicosRendimientoTest {

    private static TechSpecs chip(Gama gama, int nivel, int generacion, int rendimiento) {
        return TechSpecs.builder().gama(gama).nivel(nivel).marcaChip("AMD").generacion(generacion)
                .rendimiento(rendimiento).build();
    }

    private static TechSpecs parse(String nombre, String categoria) {
        return TechSpecsParser.parse(nombre, categoria);
    }

    private static void primeroGana(Comparator<TechSpecs> eje, TechSpecs mejor, TechSpecs peor) {
        assertThat(eje.compare(mejor, peor)).isNegative();
        assertThat(eje.compare(peor, mejor)).isPositive();
    }

    @Test
    void cpuMasRendimientoGanaAunConNivelYAnioMenores() {
        primeroGana(EjesTecnicos.CPU, chip(Gama.ALTA, 7, 7, 5000), chip(Gama.ALTA, 9, 9, 4000));
    }

    @Test
    void gpuMasRendimientoGanaAunConAnioMenor() {
        primeroGana(EjesTecnicos.GPU, chip(Gama.ALTA, 80, 7, 900), chip(Gama.ALTA, 70, 9, 800));
    }

    @Test
    void modeloSinPuntajeVaUltimoDentroDeLaGama() {
        primeroGana(EjesTecnicos.CPU, chip(Gama.ALTA, 7, 7, 100), chip(Gama.ALTA, 9, 9, 0));
        primeroGana(EjesTecnicos.GPU, chip(Gama.ALTA, 70, 7, 100), chip(Gama.ALTA, 90, 9, 0));
    }

    @Test
    void laGamaPedidaSigueAntesQueElRendimiento() {
        primeroGana(EjesTecnicos.CPU, chip(Gama.ALTA, 7, 7, 100), chip(Gama.MEDIA, 5, 9, 9000));
        primeroGana(EjesTecnicos.GPU, chip(Gama.ALTA, 70, 7, 100), chip(Gama.MEDIA, 60, 9, 9000));
    }

    @Test
    void sinRendimientoDecideLaEscaleraDeHoy() {
        primeroGana(EjesTecnicos.CPU, chip(Gama.ALTA, 9, 7, 0), chip(Gama.ALTA, 7, 9, 0));
    }

    @Test
    void sufijosDelCatalogoRealSeOrdenanPorBenchmark() {
        primeroGana(EjesTecnicos.GPU,
                parse("Placa de Video ASUS Prime GeForce RTX 5070 Ti 16GB", "GPU"),
                parse("Placa de Video ASUS Prime GeForce RTX 5070 12GB", "GPU"));
        primeroGana(EjesTecnicos.GPU,
                parse("Placa de Video Sapphire Pulse Radeon RX 9070 XT 16GB", "GPU"),
                parse("Placa de Video Sapphire Pulse Radeon RX 9070 16GB", "GPU"));
        primeroGana(EjesTecnicos.CPU,
                parse("Procesador Intel Core i5 14600K LGA1700", "CPU"),
                parse("Procesador Intel Core i5 14400F LGA1700", "CPU"));
    }
}
