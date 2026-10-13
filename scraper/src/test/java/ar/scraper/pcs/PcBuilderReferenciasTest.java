package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The chart's data: the pick against the best of its gama and the best of the whole catalog. */
class PcBuilderReferenciasTest {

    private final PcBuilder builder = new PcBuilder();

    private static Product producto(String nombre, double precio, String categoria, String url) {
        return Product.builder().sitio("TestSitio").nombre(nombre).precio(precio).url(url)
                .imagenUrl("https://img/test.jpg").categoria(categoria).genero("").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("").rubro("tecnologia").gymrat(false).build();
    }

    private static final Product MEJOR = producto("Procesador AMD Ryzen 7 9850X3D AM5", 900_000, "CPU", "https://t/9850x3d");
    private static final Product SEGUNDO = producto("Procesador AMD Ryzen 7 9800X3D AM5", 700_000, "CPU", "https://t/9800x3d");
    private static final Product MEDIA_TOP = producto("Procesador AMD Ryzen 5 9600X AM5", 300_000, "CPU", "https://t/9600x");
    private static final Product MEDIA_BAJA = producto("Procesador AMD Ryzen 5 7600X AM5", 250_000, "CPU", "https://t/7600x");
    private static final Product GPU_TOP = producto("Placa de Video Nvidia GeForce RTX 5090 32GB", 9_000_000, "GPU", "https://t/5090");
    private static final Product GPU_MEDIA = producto("Placa de Video Nvidia GeForce RTX 4060 Ti 8GB", 500_000, "GPU", "https://t/4060ti");
    private static final Product GPU_MEDIA_BAJA = producto("Placa de Video Nvidia GeForce RTX 4060 8GB", 400_000, "GPU", "https://t/4060");

    private static List<Product> catalogo(Product... extra) {
        List<Product> l = new ArrayList<>(List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Corsair Vengeance DDR5 32GB (2x16GB) 6000MHz", 120_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 850W 80 Plus Gold ATX 3.1", 150_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme")));
        l.addAll(List.of(extra));
        return l;
    }

    private static TechSpecs specs(Product p) {
        return TechSpecsParser.parse(p.nombre(), p.categoria());
    }

    private PcBuild armar(List<Product> cat, boolean conGpu, Set<String> excluir, Uso uso) {
        return builder.armar(cat, 0, conGpu, excluir, Gama.MEDIA, PreferenciasDeArmado.NINGUNA, uso);
    }

    @Test
    void gamingCpuSeCompararContraLaMejorDeSuGamaYLaMejorDelCatalogo() {
        assertThat(specs(MEDIA_TOP).gama()).isEqualTo(Gama.MEDIA);
        assertThat(specs(MEJOR).gama()).isEqualTo(Gama.ALTA);

        PcBuild build = armar(catalogo(MEJOR, SEGUNDO, MEDIA_TOP, MEDIA_BAJA), false, Set.of(), Uso.GAMING);

        ReferenciaDeRendimiento r = build.referencias().get("cpu");
        assertThat(r.escala()).isEqualTo("gaming");
        assertThat(r.pick().modelo()).isEqualTo(specs(MEDIA_TOP).modelo());
        assertThat(r.pick().puntaje()).isEqualTo(specs(MEDIA_TOP).rendimientoGaming()).isPositive();
        assertThat(r.mejorDeGama().modelo()).isEqualTo(specs(MEDIA_TOP).modelo());
        assertThat(r.mejorDeGama().gama()).isEqualTo(Gama.MEDIA);
        assertThat(r.mejorDelCatalogo().modelo()).isEqualTo(specs(MEJOR).modelo());
        assertThat(r.mejorDelCatalogo().puntaje()).isEqualTo(specs(MEJOR).rendimientoGaming());
        assertThat(build.referencias()).doesNotContainKey("gpu");
    }

    @Test
    void homelabUsaBlender() {
        PcBuild build = armar(catalogo(MEJOR, SEGUNDO, MEDIA_TOP, MEDIA_BAJA), false, Set.of(), Uso.HOMELAB);

        ReferenciaDeRendimiento r = build.referencias().get("cpu");
        assertThat(r.escala()).isEqualTo("blender");
        assertThat(r.pick().puntaje()).isEqualTo(specs(MEDIA_TOP).rendimiento()).isPositive();
        assertThat(r.mejorDelCatalogo().puntaje()).isEqualTo(specs(MEJOR).rendimiento());
    }

    @Test
    void gpuTraeSuPropiaEscala() {
        PcBuild build = armar(catalogo(MEDIA_TOP, GPU_TOP, GPU_MEDIA, GPU_MEDIA_BAJA), true, Set.of(), Uso.GAMING);

        ReferenciaDeRendimiento r = build.referencias().get("gpu");
        assertThat(r.escala()).isEqualTo("gpu");
        assertThat(r.pick().puntaje()).isEqualTo(specs(GPU_MEDIA).rendimiento()).isPositive();
        assertThat(r.mejorDeGama().modelo()).isEqualTo(specs(GPU_MEDIA).modelo());
        assertThat(r.mejorDelCatalogo().modelo()).isEqualTo(specs(GPU_TOP).modelo());
        assertThat(r.mejorDelCatalogo().puntaje()).isEqualTo(specs(GPU_TOP).rendimiento());
    }

    @Test
    void sinBenchmarkElPickVaConCeroYLasReferenciasNulas() {
        Product sinPuntaje = producto("Procesador AMD Ryzen 5 4500 AM4", 100_000, "CPU", "https://t/4500");
        PcBuild build = builder.armar(List.of(
                producto("Motherboard ASUS Prime A520M-K AM4 DDR4", 90_000, "Motherboard", "https://t/mb4"),
                sinPuntaje), 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.GAMING);

        assertThat(specs(sinPuntaje).rendimientoGaming()).isZero();
        ReferenciaDeRendimiento r = build.referencias().get("cpu");
        assertThat(r.pick().puntaje()).isZero();
        assertThat(r.mejorDeGama()).isNull();
        assertThat(r.mejorDelCatalogo()).isNull();
    }

    @Test
    void excluirNoMueveLasReferencias() {
        List<Product> cat = catalogo(MEJOR, SEGUNDO, MEDIA_TOP, MEDIA_BAJA);

        PcBuild build = armar(cat, false, Set.of(MEJOR.url(), MEDIA_TOP.url()), Uso.GAMING);

        ReferenciaDeRendimiento r = build.referencias().get("cpu");
        assertThat(r.mejorDelCatalogo().modelo()).isEqualTo(specs(MEJOR).modelo());
        assertThat(r.mejorDeGama().modelo()).isEqualTo(specs(MEDIA_TOP).modelo());
    }

    @Test
    void sinPickNoHayClave() {
        PcBuild build = armar(catalogo(), false, Set.of(), Uso.GAMING);

        assertThat(build.referencias()).isEmpty();
    }
}
