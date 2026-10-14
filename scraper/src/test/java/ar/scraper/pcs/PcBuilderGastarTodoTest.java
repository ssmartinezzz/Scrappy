package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Presupuesto 1M, gama ALTA, sin GPU: mother cuota 171 428, cpu cuota 285 714. */
class PcBuilderGastarTodoTest {

    private static final double PRESUPUESTO = 1_000_000;
    private static final String A620 = "https://t/a620";
    private static final String A620_OUTLET = "https://t/a620-outlet";
    private static final String B840 = "https://t/b840";
    private static final String X870 = "https://t/x870";
    private static final String X870_OUTLET = "https://t/x870-outlet";
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

    private static String urlDe(PcBuild build, String slot) {
        return build.picks().stream().filter(p -> p.slot().equals(slot)).map(PcPick::url)
                .findFirst().orElse("");
    }

    private PcBuild armar(List<Product> catalogo, double presupuesto, ModoPresupuesto modo) {
        return builder.armar(catalogo, presupuesto, false, Set.of(), Gama.ALTA, PreferenciasDeArmado.NINGUNA,
                Uso.GAMING, modo);
    }

    private static Product cpu() {
        return producto("Procesador Amd Ryzen 9 9950X3D", 280_000, "CPU", R9);
    }

    private static Product mother(String nombre, double precio, String url) {
        return producto(nombre, precio, "Motherboard", url);
    }

    /** X870 at 200k does not fit next to the CPU inside mother+cpu cuotas (456k), so Ahorrar skips it. */
    private static List<Product> catalogoBase() {
        return List.of(
                mother("Motherboard Gigabyte A620M H DDR5 AM5", 80_000, A620),
                mother("Motherboard Asus Rog Strix X870-E Gaming DDR5 AM5", 200_000, X870),
                cpu());
    }

    @Test
    @DisplayName("with leftover, Gastar todo upgrades the board to the gama's target tier and keeps the CPU")
    void sobranteSubeLaMotherAlTierDeLaGama() {
        PcBuild build = armar(catalogoBase(), PRESUPUESTO, ModoPresupuesto.GASTAR_TODO);

        assertThat(urlDe(build, "mother")).isEqualTo(X870);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }

    @Test
    @DisplayName("Ahorrar keeps the cheap board on the same fixture")
    void ahorrarMantieneLaMotherBarata() {
        PcBuild build = armar(catalogoBase(), PRESUPUESTO, ModoPresupuesto.AHORRAR);

        assertThat(urlDe(build, "mother")).isEqualTo(A620);
        assertThat(urlDe(build, "cpu")).isEqualTo(R9);
    }

    @Test
    @DisplayName("a null mode is Ahorrar")
    void modoNuloEsAhorrar() {
        PcBuild build = armar(catalogoBase(), PRESUPUESTO, null);

        assertThat(urlDe(build, "mother")).isEqualTo(A620);
    }

    @Test
    @DisplayName("an equal-axes non-outlet board beats the cheaper OUTLET one in Gastar todo only")
    void unaMotherNormalGanaleAlOutletEnGastarTodo() {
        List<Product> catalogo = List.of(
                mother("OUTLET Motherboard Gigabyte A620M H DDR5 AM5", 80_000, A620_OUTLET),
                mother("Motherboard Msi A620M-E DDR5 AM5", 120_000, A620),
                cpu());

        assertThat(urlDe(armar(catalogo, PRESUPUESTO, ModoPresupuesto.GASTAR_TODO), "mother")).isEqualTo(A620);
        assertThat(urlDe(armar(catalogo, PRESUPUESTO, ModoPresupuesto.AHORRAR), "mother")).isEqualTo(A620_OUTLET);
    }

    @Test
    @DisplayName("an outlet board is still chosen when it is the only one")
    void elOutletSeElijeSiEsLaUnica() {
        List<Product> catalogo = List.of(
                mother("OUTLET Motherboard Gigabyte A620M H DDR5 AM5", 80_000, A620_OUTLET),
                cpu());

        assertThat(urlDe(armar(catalogo, PRESUPUESTO, ModoPresupuesto.GASTAR_TODO), "mother"))
                .isEqualTo(A620_OUTLET);
    }

    @Test
    @DisplayName("an outlet upgrade is chosen when it is the only kind of board that fits")
    void elOutletSeElijeSiLosOtrosNoEntran() {
        List<Product> catalogo = List.of(
                mother("OUTLET Motherboard Gigabyte A620M H DDR5 AM5", 80_000, A620_OUTLET),
                mother("OUTLET Motherboard Asus Rog Strix X870-E Gaming DDR5 AM5", 150_000, X870_OUTLET),
                mother("Motherboard Asus Rog Strix X870-E Gaming DDR5 AM5", 900_000, X870),
                cpu());

        assertThat(urlDe(armar(catalogo, PRESUPUESTO, ModoPresupuesto.GASTAR_TODO), "mother"))
                .isEqualTo(X870_OUTLET);
    }

    @Test
    @DisplayName("a board that breaks the already-picked RAM is never chosen")
    void nuncaRompeLaRamYaElegida() {
        List<Product> catalogo = List.of(
                mother("Motherboard Gigabyte A620M H DDR4 AM5", 80_000, A620),
                mother("Motherboard Gigabyte X870 Aorus DDR5 AM5", 200_000, X870),
                producto("Memoria RAM Kingston Fury 16GB DDR4 3200MHz", 60_000, "RAM", "https://t/ram4"),
                cpu());

        PcBuild build = armar(catalogo, PRESUPUESTO, ModoPresupuesto.GASTAR_TODO);

        assertThat(urlDe(build, "ram")).isEqualTo("https://t/ram4");
        assertThat(urlDe(build, "mother")).isEqualTo(A620);
    }

    @Test
    @DisplayName("a board that breaks the already-picked case form factor is never chosen")
    void nuncaRompeElGabineteYaElegido() {
        List<Product> catalogo = List.of(
                mother("Motherboard Gigabyte A620M H DDR5 AM5 Micro ATX", 80_000, A620),
                mother("Motherboard Asus Rog Strix X870-E Gaming DDR5 AM5 E-ATX", 200_000, X870),
                producto("Gabinete Gamer Mini Tower Micro ATX Sin Fuente", 50_000, "Gabinete", "https://t/gab"),
                cpu());

        PcBuild build = armar(catalogo, PRESUPUESTO, ModoPresupuesto.GASTAR_TODO);

        assertThat(urlDe(build, "gabinete")).isEqualTo("https://t/gab");
        assertThat(urlDe(build, "mother")).isEqualTo(A620);
    }

    @Test
    @DisplayName("the total never exceeds the budget")
    void elTotalNoPasaElPresupuesto() {
        PcBuild build = armar(catalogoBase(), 400_000, ModoPresupuesto.GASTAR_TODO);

        assertThat(build.totalEstimado()).isLessThanOrEqualTo(400_000);
    }

    @Test
    @DisplayName("a board above the leftover is not bought")
    void noCompraUnaMotherQueNoEntraEnElSobrante() {
        PcBuild build = armar(catalogoBase(), 360_000, ModoPresupuesto.GASTAR_TODO);

        assertThat(urlDe(build, "mother")).isEqualTo(A620);
        assertThat(build.totalEstimado()).isLessThanOrEqualTo(360_000);
    }

    @Test
    @DisplayName("without a budget Gastar todo is identical to Ahorrar")
    void sinPresupuestoEsIgualQueAhorrar() {
        PcBuild gastar = armar(catalogoBase(), 0, ModoPresupuesto.GASTAR_TODO);
        PcBuild ahorrar = armar(catalogoBase(), 0, ModoPresupuesto.AHORRAR);

        assertThat(gastar).isEqualTo(ahorrar);
    }
}
