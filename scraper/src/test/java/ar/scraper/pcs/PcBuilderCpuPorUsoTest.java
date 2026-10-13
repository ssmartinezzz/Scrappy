package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blender puts the 14700K (4416) over the 9800X3D (3339); the gaming index puts it the other way
 * round (764 vs 970). The socket follows the CPU, so the mother has to follow the uso too.
 */
class PcBuilderCpuPorUsoTest {

    private final PcBuilder builder = new PcBuilder();

    private static Product producto(String nombre, double precio, String categoria, String url) {
        return Product.builder().sitio("TestSitio").nombre(nombre).precio(precio).url(url)
                .imagenUrl("https://img/test.jpg").categoria(categoria).genero("").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("").rubro("tecnologia").gymrat(false).build();
    }

    private static final List<Product> CATALOGO = List.of(
            producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb-am5"),
            producto("Motherboard ASUS Prime Z790-P WiFi DDR5 LGA1700", 260_000, "Motherboard", "https://t/mb-1700"),
            producto("Procesador AMD Ryzen 7 9800X3D AM5", 600_000, "CPU", "https://t/9800x3d"),
            producto("Procesador Intel Core i7 14700K LGA1700", 650_000, "CPU", "https://t/14700k"),
            producto("Memoria RAM Corsair Vengeance DDR5 32GB (2x16GB) 6000MHz", 120_000, "RAM", "https://t/ram"),
            producto("Fuente Antec 850W 80 Plus Gold ATX 3.1", 150_000, "Fuente", "https://t/fuente"),
            producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
            producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"),
            producto("SSD Kingston NV2 2TB", 90_000, "Almacenamiento", "https://t/nvme2"));

    private static String pick(PcBuild build, String slot) {
        return build.picks().stream().filter(p -> p.slot().equals(slot)).map(PcPick::url).findFirst().orElse("");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 5_000_000})
    void gamingEligeElX3dYSuPlataforma(double presupuesto) {
        PcBuild build = builder.armar(CATALOGO, presupuesto, false, Set.of(), Gama.ALTA,
                PreferenciasDeArmado.NINGUNA, Uso.GAMING);

        assertThat(pick(build, "cpu")).isEqualTo("https://t/9800x3d");
        assertThat(pick(build, "mother")).isEqualTo("https://t/mb-am5");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 5_000_000})
    void homelabSigueEligiendoPorBlender(double presupuesto) {
        PcBuild build = builder.armar(CATALOGO, presupuesto, false, Set.of(), Gama.ALTA,
                PreferenciasDeArmado.NINGUNA, Uso.HOMELAB);

        assertThat(pick(build, "cpu")).isEqualTo("https://t/14700k");
        assertThat(pick(build, "mother")).isEqualTo("https://t/mb-1700");
    }
}
