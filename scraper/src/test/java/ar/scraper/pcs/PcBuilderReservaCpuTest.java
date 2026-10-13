package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Presupuesto 1M, no GPU, no gama: mother cuota 171 428, cpu cuota 285 714. */
class PcBuilderReservaCpuTest {

    private static final double PRESUPUESTO = 1_000_000;
    private static final String Z790 = "https://t/z790";
    private static final String B840 = "https://t/b840";
    private static final String B850 = "https://t/b850";
    private static final String I9 = "https://t/14900k";
    private static final String R9 = "https://t/9950x3d";
    private static final String R7G = "https://t/8700g";

    private final PcBuilder builder = new PcBuilder();

    private static Product producto(String nombre, double precio, String categoria, String url) {
        return Product.builder()
                .sitio("TestSitio")
                .nombre(nombre)
                .precio(precio)
                .url(url)
                .imagenUrl("https://img/test.jpg")
                .categoria(categoria)
                .genero("")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("")
                .rubro("tecnologia")
                .gymrat(false)
                .build();
    }

    private static String urlDe(PcBuild build, String slot) {
        return build.picks().stream().filter(p -> p.slot().equals(slot)).map(PcPick::url)
                .findFirst().orElse("");
    }

    @Test
    @DisplayName("the mother's leftover counts: a CPU above the raw cpu cuota still chooses the platform")
    void elSobranteDeLaMotherCuentaParaElegirPlataforma() {
        PcBuild build = builder.armar(List.of(
                producto("Motherboard Gigabyte B840M H DDR5 AM5", 100_000, "Motherboard", B840),
                producto("Mother Asus Rog Strix Z790-E LGA1700 DDR5", 100_000, "Motherboard", Z790),
                producto("Procesador Intel Core i9 14900K", 250_000, "CPU", I9),
                producto("Procesador Amd Ryzen 9 9950X3D", 330_000, "CPU", R9)), PRESUPUESTO, false, Set.of());

        assertThat(urlDe(build, "mother")).isEqualTo(B840);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }

    @Test
    @DisplayName("a better-tier board that leaves too little for the target CPU loses to one that keeps it")
    void laMotherReservaLaPlataParaElCpuObjetivo() {
        PcBuild build = builder.armar(List.of(
                producto("Motherboard Gigabyte B840M H DDR5 AM5", 100_000, "Motherboard", B840),
                producto("Mother Msi B850m-b Gen 4 M-atx Am5 Ddr5", 170_000, "Motherboard", B850),
                producto("MICRO AMD RYZEN 7 8700G C/VIDEO C/COOLER BOX AM5", 250_000, "CPU", R7G),
                producto("Procesador Amd Ryzen 9 9950X3D", 330_000, "CPU", R9)), PRESUPUESTO, false, Set.of());

        assertThat(urlDe(build, "mother")).isEqualTo(B840);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }

    @Test
    @DisplayName("when the target CPU fits next to the better board, the better board stays")
    void siElCpuObjetivoEntraConLaMejorMotherSeQuedaLaMejor() {
        PcBuild build = builder.armar(List.of(
                producto("Motherboard Gigabyte B840M H DDR5 AM5", 100_000, "Motherboard", B840),
                producto("Mother Msi B850m-b Gen 4 M-atx Am5 Ddr5", 170_000, "Motherboard", B850),
                producto("Procesador Amd Ryzen 9 9950X3D", 280_000, "CPU", R9)), PRESUPUESTO, false, Set.of());

        assertThat(urlDe(build, "mother")).isEqualTo(B850);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }
}
