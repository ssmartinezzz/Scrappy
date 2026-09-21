package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PcBuilder}'s 5-arg {@code armar} overload with a requested gama
 * (pc-builder-gama, T3a). The pre-existing 4-arg overload (used by
 * PcsEndpoints, propose_pc and PcBuilderTest's 29 tests) must keep behaving
 * exactly as before — that contract is pinned in PcBuilderTest, untouched.
 */
class PcBuilderGamaTest {

    private final PcBuilder builder = new PcBuilder();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    // ── D1: la gama es filtro duro ───────────────────────────────────────

    @Test
    @DisplayName("gama ALTA picks a high-tier CPU and skips a low-tier one, even if the low-tier is the only stock")
    void gamaAltaVetaCpuDeGamaBaja() {
        List<Product> catalogo = List.of(
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).contains("cpu");
    }

    @Test
    @DisplayName("gama ALTA picks the high-tier CPU when both a low- and a high-tier are in stock")
    void gamaAltaEligeCpuDeGamaAlta() {
        List<Product> catalogo = List.of(
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"),
                producto("Procesador Amd Ryzen 9 7900", 400_000, "CPU", "https://t/r9"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).filteredOn(p -> p.slot().equals("cpu"))
                .extracting(PcPick::url).containsExactly("https://t/r9");
    }

    @Test
    @DisplayName("D2: a CPU whose gama could not be parsed is vetoed when a gama was requested (abstention vetoes)")
    void gamaPedidaVetaCpuSinGamaLegible() {
        List<Product> catalogo = List.of(
                producto("Procesador Generico Sin Marca Reconocible", 100_000, "CPU", "https://t/cpu"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).contains("cpu");
    }

    @Test
    @DisplayName("gama ALTA applies to gpu too")
    void gamaAltaVetaGpuDeGamaBaja() {
        List<Product> catalogo = List.of(
                producto("Placa de Video Asus GeForce GTX 1650 4GB", 150_000, "GPU", "https://t/gtx"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("gpu"));
        assertThat(build.sinCompatible()).contains("gpu");
    }

    @Test
    @DisplayName("gama has no effect on non cpu/gpu slots (e.g. RAM, which has no gama)")
    void gamaNoAfectaSlotsSinGama() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("ram"));
    }

    // ── backward compat: sin gama pedida, comportamiento identico al 4-arg ──

    @Test
    @DisplayName("the 4-arg overload behaves exactly like the 5-arg one with gamaPedida=null")
    void overloadDe4ArgsDelegaSinGamaPedida() {
        List<Product> catalogo = List.of(
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"));

        PcBuild conNull = builder.armar(catalogo, 0, false, Set.of(), null);
        PcBuild de4Args = builder.armar(catalogo, 0, false, Set.of());

        assertThat(de4Args.picks()).extracting(PcPick::url)
                .containsExactlyElementsOf(conNull.picks().stream().map(PcPick::url).toList());
        assertThat(de4Args.sinCompatible()).isEqualTo(conNull.sinCompatible());
    }

    // ── D5: EstimadorDeConsumo reemplaza los pisos de watts fijos ────────

    @Test
    @DisplayName("gama ALTA sin GPU exige 750W: vetoes una fuente de 700W que pasaria el piso viejo de 450W")
    void gamaAltaSubeElPisoDeWattsSinGpu() {
        List<Product> catalogo = List.of(
                producto("Fuente Antec 700W 80 Plus Gold ATX", 100_000, "Fuente", "https://t/700"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("fuente"));
        assertThat(build.sinCompatible()).contains("fuente");
    }

    @Test
    @DisplayName("gama ALTA con GPU exige 1000W")
    void gamaAltaSubeElPisoDeWattsConGpu() {
        List<Product> catalogo = List.of(
                producto("Fuente Antec 850W 80 Plus Gold ATX", 100_000, "Fuente", "https://t/850"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("fuente"));
        assertThat(build.sinCompatible()).contains("fuente");
    }

    @Test
    @DisplayName("gama MEDIA exige solo 550W sin GPU, no 750")
    void gamaMediaPisoEs550SinGpu() {
        List<Product> catalogo = List.of(
                producto("Fuente Antec 600W 80 Plus Bronze ATX", 100_000, "Fuente", "https://t/600"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.MEDIA);

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("fuente"));
    }

    // ── certificacion minima ──────────────────────────────────────────────

    @Test
    @DisplayName("gama ALTA exige certificacion GOLD: vetoes una fuente BRONZE aunque cumpla watts")
    void gamaAltaExigeGold() {
        List<Product> catalogo = List.of(
                producto("Fuente Antec 750W 80 Plus Bronze ATX", 100_000, "Fuente", "https://t/bronze"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("fuente"));
        assertThat(build.sinCompatible()).contains("fuente");
    }

    @Test
    @DisplayName("gama ALTA no vetoes a fuente sin certificacion declarada (abstencion NO veta aca)")
    void gamaAltaNoVetaFuenteSinCertificacionDeclarada() {
        List<Product> catalogo = List.of(
                producto("Fuente Antec 750W Modular ATX", 100_000, "Fuente", "https://t/sincert"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("fuente"));
    }

    // ── T3b-2, D4: el slot cooler sólo se abre con gama ALTA ─────────────

    @Test
    @DisplayName("D4: gama ALTA opens the cooler slot; gama MEDIA/BAJA/null keep it closed entirely")
    void coolerSoloConGamaAlta() {
        List<Product> catalogo = List.of(
                producto("Cooler Cooler Master Hyper 212", 30_000, "Cooler", "https://t/cooler"));

        PcBuild alta = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);
        PcBuild media = builder.armar(catalogo, 0, false, Set.of(), Gama.MEDIA);
        PcBuild baja = builder.armar(catalogo, 0, false, Set.of(), Gama.BAJA);
        PcBuild sinGama = builder.armar(catalogo, 0, false, Set.of());

        assertThat(alta.picks()).anyMatch(p -> p.slot().equals("cooler"));

        assertThat(media.picks()).noneMatch(p -> p.slot().equals("cooler"));
        assertThat(media.sinStock()).doesNotContain("cooler");
        assertThat(media.sinCompatible()).doesNotContain("cooler");
        assertThat(media.mensajes()).doesNotContainKey("cooler");

        assertThat(baja.picks()).noneMatch(p -> p.slot().equals("cooler"));
        assertThat(sinGama.picks()).noneMatch(p -> p.slot().equals("cooler"));
    }

    @Test
    @DisplayName("D4: gama DESCONOCIDA (an unparseable requested tier) also keeps the cooler slot closed")
    void coolerCerradoConGamaDesconocida() {
        List<Product> catalogo = List.of(
                producto("Cooler Cooler Master Hyper 212", 30_000, "Cooler", "https://t/cooler"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.DESCONOCIDA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cooler"));
        assertThat(build.sinStock()).doesNotContain("cooler");
        assertThat(build.sinCompatible()).doesNotContain("cooler");
        assertThat(build.mensajes()).doesNotContainKey("cooler");
    }

    @Test
    @DisplayName("D4: the cooler slot sits right after cpu in pick order, before ram/gabinete/fuente/gpu")
    void coolerVaDespuesDeCpu() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Cooler Cooler Master Hyper 212", 30_000, "Cooler", "https://t/cooler"),
                producto("Memoria RAM Corsair Vengeance DDR5 32GB 6000MHz", 90_000, "RAM", "https://t/ram"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("Fuente Antec 1000W 80 Plus Gold ATX", 150_000, "Fuente", "https://t/fuente"),
                producto("Placa de Video Asus GeForce RTX 4090", 1_500_000, "GPU", "https://t/gpu"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/ssd"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), Gama.ALTA);

        assertThat(build.picks()).extracting(PcPick::slot)
                .containsSubsequence("cpu", "cooler", "ram", "gabinete", "fuente", "gpu", "almacenamiento");
    }

    @Test
    @DisplayName("D6: cooler with no stock is sinStock and its mensaje names the empty category")
    void coolerSinStockTieneMensaje() {
        PcBuild build = builder.armar(List.of(), 0, false, Set.of(), Gama.ALTA);

        assertThat(build.sinStock()).contains("cooler");
        assertThat(build.mensajes()).containsEntry("cooler", "no hay productos en la categoría Cooler");
    }

    // ── T2d, pc-builder-deep-taxonomy: cooler ↔ mother socket ────────────

    @Test
    @DisplayName("T2d: a cooler that only lists AM4 is vetoed against an AM5 mother")
    void coolerVetadoCuandoNoSoportaElSocketDeLaMother() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Cooler CPU Deepcool AG400 AM4 Only", 30_000, "Cooler", "https://t/cooler"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cooler"));
        assertThat(build.sinCompatible()).contains("cooler");
        assertThat(build.mensajes()).containsEntry("cooler", "el cooler no soporta el socket de la motherboard");
    }

    // ── T3b-2, D6: mensajes por slot vacío ────────────────────────────────

    @Test
    @DisplayName("D6: sinCompatible carries the single vetoing rule's motivo when only one rule fires")
    void mensajeSinCompatibleConUnMotivo() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 100_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB 6000MHz", 180_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.sinCompatible()).contains("ram");
        assertThat(build.mensajes()).containsEntry("ram", "la generación de RAM no coincide con la de la motherboard");
    }

    @Test
    @DisplayName("D6: sinCompatible joins distinct motivos in the slot's own rule order when two rules each veto a different candidate")
    void mensajeSinCompatibleConDosMotivosDistintos() {
        List<Product> catalogo = List.of(
                producto("Fuente Antec 400W 80 Plus Gold ATX", 50_000, "Fuente", "https://t/lowwatts"),
                producto("Fuente Antec 800W 80 Plus Bronze ATX", 90_000, "Fuente", "https://t/lowcert"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        assertThat(build.sinCompatible()).contains("fuente");
        assertThat(build.mensajes()).containsEntry("fuente",
                "la fuente no alcanza el piso de watts requerido · la fuente no alcanza la certificación mínima requerida");
    }

    @Test
    @DisplayName("D6: a slot with a pick has no mensaje entry")
    void slotConPickNoTieneMensaje() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB", 10_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("ram"));
        assertThat(build.mensajes()).doesNotContainKey("ram");
    }
}
