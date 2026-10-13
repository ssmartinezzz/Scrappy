package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PcBuilderPlataformaTest {

    private static final String Z790 = "https://t/z790";
    private static final String B650 = "https://t/b650";
    private static final String I9 = "https://t/14900k";
    private static final String R9 = "https://t/9950x3d";

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

    private static List<Product> catalogo(double precioR9) {
        return List.of(
                producto("Mother Asus Rog Strix Z790-E LGA1700 DDR5", 150_000, "Motherboard", Z790),
                producto("Mother Asus Tuf B650-Plus AM5 DDR5", 150_000, "Motherboard", B650),
                producto("Procesador Intel Core i9 14900K", 250_000, "CPU", I9),
                producto("Procesador Amd Ryzen 9 9950X3D", precioR9, "CPU", R9));
    }

    private static String urlDe(PcBuild build, String slot) {
        return build.picks().stream().filter(p -> p.slot().equals(slot)).map(PcPick::url)
                .findFirst().orElse("");
    }

    @Test
    @DisplayName("sin presupuesto, the platform follows the best CPU: AM5 + 9950X3D beats a higher-tier Z790")
    void sinPresupuestoLaPlataformaSigueAlMejorCpu() {
        PcBuild build = builder.armar(catalogo(900_000), 0, false, Set.of());

        assertThat(urlDe(build, "mother")).isEqualTo(B650);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }

    @Test
    @DisplayName("with gama ALTA requested, the platform still follows the best CPU")
    void conGamaLaPlataformaSigueAlMejorCpu() {
        PcBuild build = builder.armar(catalogo(900_000), 0, false, Set.of(), Gama.ALTA);

        assertThat(urlDe(build, "mother")).isEqualTo(B650);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }

    @Test
    @DisplayName("with a budget, only CPUs that fit the cpu cuota choose the platform")
    void conPresupuestoSoloCuentanLosCpuQueEntranEnLaCuota() {
        PcBuild entra = builder.armar(catalogo(280_000), 1_000_000, false, Set.of());
        PcBuild noEntra = builder.armar(catalogo(900_000), 1_000_000, false, Set.of());

        assertThat(urlDe(entra, "mother")).isEqualTo(B650);
        assertThat(urlDe(entra, "cpu")).isEqualTo(R9);
        assertThat(urlDe(noEntra, "mother")).isEqualTo(Z790);
        assertThat(urlDe(noEntra, "cpu")).isEqualTo(I9);
    }
}
