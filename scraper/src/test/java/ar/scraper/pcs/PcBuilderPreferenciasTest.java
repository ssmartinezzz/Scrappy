package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PcBuilder}'s 6-arg {@code armar} overload with requested technical
 * preferences (D1-D3, pc-builder-deep-taxonomy T4b). The existing 4-arg and
 * 5-arg overloads (used by PcsEndpoints, propose_pc, PcBuilderTest and
 * PcBuilderGamaTest) must keep behaving exactly as before — pinned by
 * {@link #ningunaEsIdenticaAlOverloadDe5Args()}.
 */
class PcBuilderPreferenciasTest {

    private final PcBuilder builder = new PcBuilder();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    @Test
    @DisplayName("D9-independent: DDR4 requested + AM5-only mothers -> mother in sinCompatible with the DDR motivo")
    void ddrPedidaVetaMotherDerivadaDeUnSocketQueNoCoincide() {
        List<Product> catalogo = List.of(
                producto("Motherboard Gigabyte B650M AM5", 150_000, "Motherboard", "https://t/b650m"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado("DDR4", null, null, null, null, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("mother");
        assertThat(build.mensajes().get("mother")).contains("DDR4");
    }

    @Test
    @DisplayName("ddr requested vetoes a ram whose own ddr differs, mensaje names the requested ddr")
    void ddrPedidaVetaRamQueNoCoincide() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB", 60_000, "RAM", "https://t/ram"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado("DDR5", null, null, null, null, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("ram");
        assertThat(build.mensajes().get("ram")).contains("DDR5");
    }

    @Test
    @DisplayName("marcaCpu requested vetoes a cpu of the other brand, mensaje names the requested marca")
    void marcaCpuPedidaVetaCpuDeOtraMarca() {
        List<Product> catalogo = List.of(
                producto("Procesador Intel Core i7 12700", 400_000, "CPU", "https://t/i7"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, "AMD", null, null, null, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("cpu");
        assertThat(build.mensajes().get("cpu")).contains("AMD");
    }

    @Test
    @DisplayName("marcaCpu requested also vetoes a mother whose derived-from-socket marcaChip is the other brand")
    void marcaCpuPedidaTambienVetaMotherDeOtraMarca() {
        List<Product> catalogo = List.of(
                producto("Motherboard Gigabyte B650M AM5 DDR5", 150_000, "Motherboard", "https://t/b650m"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, "INTEL", null, null, null, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("mother");
        assertThat(build.mensajes().get("mother")).contains("INTEL");
    }

    @Test
    @DisplayName("marcaGpu requested vetoes a gpu of the other brand, mensaje names the requested marca")
    void marcaGpuPedidaVetaGpuDeOtraMarca() {
        List<Product> catalogo = List.of(
                producto("Placa de Video Sapphire Radeon RX 7800 XT 16GB", 700_000, "GPU", "https://t/rx"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, null, "NVIDIA", null, null, null);

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("gpu");
        assertThat(build.mensajes().get("gpu")).contains("NVIDIA");
    }

    @Test
    @DisplayName("tipoAlmacenamiento requested vetoes a candidate of a different technology")
    void tipoAlmacenamientoPedidoVetaOtraTecnologia() {
        List<Product> catalogo = List.of(
                producto("SSD Kingston A400 480GB", 60_000, "Almacenamiento", "https://t/ssd"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, null, null, TipoAlmacenamiento.NVME, null, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("almacenamiento");
        assertThat(build.mensajes().get("almacenamiento")).contains("NVME");
    }

    @Test
    @DisplayName("D2: ramDual TRUE vetoes a ram with no readable module count (abstention vetoes)")
    void ramDualPedidoVetaModuloUnicoOAbstencion() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR5 16GB 6000MHz", 90_000, "RAM", "https://t/ram"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, null, null, null, true, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("ram");
    }

    @Test
    @DisplayName("wifi TRUE vetoes a mother whose name doesn't declare wifi (assertion, not abstention)")
    void wifiPedidoVetaMotherSinWifi() {
        List<Product> catalogo = List.of(
                producto("Motherboard Gigabyte B650M AM5", 150_000, "Motherboard", "https://t/b650m"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, null, null, null, null, true);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs);

        assertThat(build.sinCompatible()).contains("mother");
        assertThat(build.mensajes().get("mother")).contains("wifi");
    }

    @Test
    @DisplayName("NINGUNA (6-arg call) produces the identical build to the 5-arg overload on the same pool")
    void ningunaEsIdenticaAlOverloadDe5Args() {
        List<Product> catalogo = List.of(
                producto("Motherboard Gigabyte B650M AM5 DDR5", 150_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 7 7700", 350_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 32GB 6000MHz", 100_000, "RAM", "https://t/ram"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gab"),
                producto("Fuente Antec 750W 80 Plus Gold ATX", 100_000, "Fuente", "https://t/fuente"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/ssd"));

        PcBuild conNingunaExplicita = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA);
        PcBuild de5Args = builder.armar(catalogo, 0, false, Set.of(), null);

        assertThat(conNingunaExplicita.picks()).extracting(PcPick::url)
                .containsExactlyElementsOf(de5Args.picks().stream().map(PcPick::url).toList());
        assertThat(conNingunaExplicita.sinCompatible()).isEqualTo(de5Args.sinCompatible());
        assertThat(conNingunaExplicita.sinStock()).isEqualTo(de5Args.sinStock());
    }
}
