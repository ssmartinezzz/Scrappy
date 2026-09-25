package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PcBuilder} — one pick per slot, best-effort, with hard compatibility
 * vetoes that only fire when both sides of a rule parsed. See
 * odd/tasks/pc-builder.md for the design this pins.
 */
class PcBuilderTest {

    private final PcBuilder builder = new PcBuilder();

    // Sin scoreP: desde T3b el ranking no lee baseMlScore (un percentil de
    // PRECIO) en ningun slot, asi que un MlScore con score elegido a mano no
    // decide nada y dejarlo sugeriria que si.
    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    // ── happy path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("assembles every slot from a compatible catalog, ATX-class case included")
    void armaTodosLosSlotsConCatalogoCompatible() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/ssd"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).hasSize(6); // no GPU slot: conGpu=false
        assertThat(build.sinStock()).isEmpty();
        assertThat(build.sinCompatible()).isEmpty();
        assertThat(build.picks()).extracting(PcPick::slot)
                .containsExactly("mother", "cpu", "ram", "gabinete", "fuente", "almacenamiento");
        assertThat(build.totalEstimado()).isEqualTo(250_000 + 400_000 + 180_000 + 120_000 + 90_000 + 60_000);
        PcPick mother = build.picks().get(0);
        assertThat(mother.specs().socket()).isEqualTo("AM5");
        assertThat(mother.specs().formFactor()).isEqualTo("MATX");
    }

    @Test
    @DisplayName("conGpu=false never touches the GPU slot: not a pick, not sinStock, not sinCompatible")
    void sinConGpuNoTocaElSlotDeGpu() {
        PcBuild build = builder.armar(List.of(), 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("gpu"));
        assertThat(build.sinStock()).doesNotContain("gpu");
        assertThat(build.sinCompatible()).doesNotContain("gpu");
    }

    @Test
    @DisplayName("conGpu=true adds the gpu slot")
    void conGpuAgregaElSlot() {
        List<Product> catalogo = List.of(
                producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/gpu"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of());

        assertThat(build.picks()).extracting(PcPick::slot).contains("gpu");
    }

    // ── socket veto (cpu.socket vs mother.socket) ───────────────────────

    @Test
    @DisplayName("socket veto fires when cpu and mother sockets both parsed and differ")
    void socketVetaCuandoDifierenYAmbosParsearon() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 100_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).contains("cpu");
    }

    @Test
    @DisplayName("socket veto abstains when the CPU name states no socket")
    void socketAbstieneCuandoCpuNoDeclaraSocket() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 100_000, "Motherboard", "https://t/mb"),
                producto("Procesador Generico Sin Socket", 400_000, "CPU", "https://t/cpu"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).doesNotContain("cpu");
    }

    @Test
    @DisplayName("socket veto abstains when the motherboard name states no socket")
    void socketAbstieneCuandoMotherNoDeclaraSocket() {
        List<Product> catalogo = List.of(
                producto("Motherboard XYZ Chipset Desconocido", 100_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).doesNotContain("cpu");
    }

    @Test
    @DisplayName("T12: un Athlon 3000G sin socket explícito ahora se lee AM4 y el veto lo saca de una mother AM5")
    void athlon3000gSeDerivaAAm4YSeVetaContraMotherAm5() {
        // Antes de T12, "Outlet Procesador Amd Athlon 3000g" abstenía el
        // socket, así que ReglaSocket no vetaba nada y ganaba una mother AM5
        // incompatible por ser lo único disponible — mismo bug en gaming y en
        // homelab (medido en la dev DB, T8/T12).
        List<Product> catalogo = List.of(
                producto("Motherboard MSI A620M-E PRO DDR5 AM5", 150_000, "Motherboard", "https://t/mb"),
                producto("Outlet Procesador Amd Athlon 3000g", 50_000, "CPU", "https://t/athlon"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).contains("cpu");
    }

    @Test
    @DisplayName("T12: con otra CPU AM5 compatible en el pool, el slot cae en ella en vez del Athlon vetado")
    void athlon3000gVetadoElSlotCaeEnOtraCpuCompatible() {
        List<Product> catalogo = List.of(
                producto("Motherboard MSI A620M-E PRO DDR5 AM5", 150_000, "Motherboard", "https://t/mb"),
                producto("Outlet Procesador Amd Athlon 3000g", 50_000, "CPU", "https://t/athlon"),
                producto("Procesador Amd Ryzen 5 8500G Am5", 200_000, "CPU", "https://t/ryzen"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks().stream().filter(p -> p.slot().equals("cpu")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/ryzen");
        assertThat(build.sinCompatible()).doesNotContain("cpu");
    }

    @Test
    @DisplayName("T17: un Xeon E5 v3 sin socket explícito ahora se lee LGA2011-3 y el veto lo saca de una mother AM5")
    void xeonE5V3SeDerivaALga20113YSeVetaContraMotherAm5() {
        // Mismo defecto que el Athlon de T12: sin socket legible, ReglaSocket
        // abstenía y el Xeon ganaba el slot cpu de una mother AM5 por ser el
        // único candidato (nombre real medido, T8d).
        List<Product> catalogo = List.of(
                producto("Motherboard MSI A620M-E PRO DDR5 AM5", 150_000, "Motherboard", "https://t/mb"),
                producto("Procesador Intel Xeon  E5-2699 V3 Oem", 45_746, "CPU", "https://t/xeon"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("cpu"));
        assertThat(build.sinCompatible()).contains("cpu");
    }

    // ── ddr veto (ram.ddr vs motherDdr) ──────────────────────────────────

    @Test
    @DisplayName("ddr veto fires when ram and motherDdr both parsed and differ")
    void ddrVetaCuandoDifierenYAmbosParsearon() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 100_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("ram"));
        assertThat(build.sinCompatible()).contains("ram");
    }

    @Test
    @DisplayName("ddr veto abstains when the RAM name states no ddr generation")
    void ddrAbstieneCuandoRamNoDeclaraDdr() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 100_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Kingston 16GB", 60_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("ram"));
        assertThat(build.sinCompatible()).doesNotContain("ram");
    }

    @Test
    @DisplayName("ddr veto abstains when motherDdr cannot be derived (LGA1700 is a mixed platform)")
    void ddrAbstieneCuandoMotherDdrEsIndeterminado() {
        List<Product> catalogo = List.of(
                producto("Motherboard MSI PRO Z790 Gaming Edge 1700", 100_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Corsair Vengeance DDR5 32GB 6000MHz", 90_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("ram"));
        assertThat(build.sinCompatible()).doesNotContain("ram");
    }

    // ── ddr derivation from socket ───────────────────────────────────────

    @Test
    @DisplayName("motherDdr derives DDR5 from an AM5 socket when the board states no ddr")
    void motherDdrDerivaDdr5DeAm5() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asrock A620AM-B EVO WIFI", 100_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Corsair Vengeance DDR4 32GB 3200MHz", 70_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        // A620 -> AM5 -> derived motherDdr DDR5; a DDR4 stick must be vetoed.
        assertThat(build.picks()).noneMatch(p -> p.slot().equals("ram"));
        assertThat(build.sinCompatible()).contains("ram");
    }

    @Test
    @DisplayName("motherDdr derives DDR4 from an AM4 socket when the board states no ddr")
    void motherDdrDerivaDdr4DeAm4() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime A520M-K AM4", 80_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM Corsair Vengeance DDR5 32GB 6000MHz", 90_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        // A520 -> AM4 -> derived motherDdr DDR4; a DDR5 stick must be vetoed.
        assertThat(build.picks()).noneMatch(p -> p.slot().equals("ram"));
        assertThat(build.sinCompatible()).contains("ram");
    }

    // ── SODIMM veto (T2c, pc-builder-deep-taxonomy) ─────────────────────

    @Test
    @DisplayName("SODIMM ram is vetoed even when it is the only candidate in the pool")
    void sodimmEsVetadaAunSiendoElUnicoCandidato() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 100_000, "Motherboard", "https://t/mb"),
                producto("Memoria RAM SODIMM Kingston DDR4 16GB Notebook", 40_000, "RAM", "https://t/ram"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("ram"));
        assertThat(build.sinCompatible()).contains("ram");
        assertThat(build.mensajes().get("ram")).contains("SODIMM");
    }

    // ── form factor veto (gabinete.formFactor vs mother.formFactor) ─────

    @Test
    @DisplayName("form-factor veto fires when the case is smaller than the board (mini-ITX case, MATX board)")
    void formFactorVetaCuandoElGabineteEsMasChicoQueLaPlaca() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Gabinete Cooler Master Masterbox NR200P Mini ITX", 90_000, "Gabinete", "https://t/gab"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("gabinete"));
        assertThat(build.sinCompatible()).contains("gabinete");
    }

    @Test
    @DisplayName("form-factor veto does not fire when the case is the same class as the board")
    void formFactorNoVetaCuandoSonDeLaMismaClase() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Gabinete Cooler Master MasterBox Q300L MATX", 70_000, "Gabinete", "https://t/gab"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("gabinete"));
    }

    @Test
    @DisplayName("form-factor veto does not fire when the case is bigger than the board")
    void formFactorNoVetaCuandoElGabineteEsMasGrande() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gab"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("gabinete"));
    }

    @Test
    @DisplayName("form-factor veto abstains when the case name states no form factor")
    void formFactorAbstieneCuandoElGabineteNoDeclaraForma() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Gabinete NZXT H510", 70_000, "Gabinete", "https://t/gab"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("gabinete"));
    }

    @Test
    @DisplayName("form-factor veto abstains when the motherboard name states no form factor")
    void formFactorAbstieneCuandoElMotherNoDeclaraForma() {
        List<Product> catalogo = List.of(
                producto("Motherboard XYZ Chipset Desconocido AM5", 250_000, "Motherboard", "https://t/mb"),
                producto("Gabinete Cooler Master Masterbox NR200P Mini ITX", 90_000, "Gabinete", "https://t/gab"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("gabinete"));
    }

    // ── watts veto (fuente.watts vs the build's minimum) ─────────────────

    @Test
    @DisplayName("watts veto fires when the PSU is below the no-GPU floor (450W)")
    void wattsVetaCuandoLaFuenteNoAlcanzaElPisoSinGpu() {
        List<Product> catalogo = List.of(producto("Fuente Antec 400W ATX", 40_000, "Fuente", "https://t/fte"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).noneMatch(p -> p.slot().equals("fuente"));
        assertThat(build.sinCompatible()).contains("fuente");
    }

    @Test
    @DisplayName("watts veto does not fire when the PSU meets the no-GPU floor")
    void wattsNoVetaCuandoLaFuenteAlcanzaElPisoSinGpu() {
        List<Product> catalogo = List.of(producto("Fuente Antec 450W ATX", 45_000, "Fuente", "https://t/fte"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("fuente"));
    }

    @Test
    @DisplayName("watts veto abstains when the PSU name states no wattage")
    void wattsAbstieneCuandoLaFuenteNoDeclaraPotencia() {
        List<Product> catalogo = List.of(producto("Fuente Antec Modular 80 Plus Bronze", 45_000, "Fuente", "https://t/fte"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("fuente"));
    }

    @Test
    @DisplayName("conGpu=true raises the watts floor to 650W, vetoing a PSU that clears the no-GPU floor")
    void conGpuSubeElPisoDeWatts() {
        List<Product> catalogo = List.of(producto("Fuente Antec 500W ATX", 50_000, "Fuente", "https://t/fte"));

        PcBuild sinGpu = builder.armar(catalogo, 0, false, Set.of());
        PcBuild conGpu = builder.armar(catalogo, 0, true, Set.of());

        assertThat(sinGpu.picks()).anyMatch(p -> p.slot().equals("fuente"));
        assertThat(conGpu.picks()).noneMatch(p -> p.slot().equals("fuente"));
        assertThat(conGpu.sinCompatible()).contains("fuente");
    }

    // ── budget filter + cheapest fallback ────────────────────────────────

    @Test
    @DisplayName("budget filter excludes an unaffordable higher-ranked candidate, picking the affordable one")
    void presupuestoDescartaElCandidatoMejorRankeadoSiNoAlcanza() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 1_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 5 5600 Am4", 1_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Barata", 10_000, "RAM", "https://t/ram-barata"),
                producto("Memoria RAM Corsair Vengeance DDR4 32GB Cara", 15_000, "RAM", "https://t/ram-cara"));

        // RAM's quota at its turn is its own share plus everything the two
        // cheap slots before it left over: (.1714+.2857+.1429)*25000 - 2000 =
        // 13000. The 15000 stick ranks better (EjesTecnicos.RAM: 32GB > 16GB,
        // same DDR and no MHz stated on either) but does not fit.
        PcBuild build = builder.armar(catalogo, 25_000, false, Set.of());

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-barata");
    }

    @Test
    @DisplayName("when nothing fits the remaining budget, the cheapest compatible candidate wins regardless of rank")
    void presupuestoCaeAlMasBaratoCuandoNadaAlcanza() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 1_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 5 5600 Am4", 1_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Barata", 10_000, "RAM", "https://t/ram-barata"),
                producto("Memoria RAM Corsair Vengeance DDR4 32GB Cara", 15_000, "RAM", "https://t/ram-cara"));

        // RAM's quota at its turn: (.1714+.2857+.1429)*7000 - 2000 = 2200.
        // Neither stick fits; the cheaper one wins even though the 32GB one ranks
        // better, because once nothing is affordable rank stops mattering and
        // price alone decides (D5).
        PcBuild build = builder.armar(catalogo, 7_000, false, Set.of());

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-barata");
    }

    // ── reparto por cuotas (D3, pc-builder-top-tier) ─────────────────────

    @Test
    @DisplayName("un slot caro no le vacia la caja al que viene despues: la fuente sigue certificada")
    void unSlotCaroNoSeComeElPresupuestoDeLosSiguientes() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 50_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 5 5600 Am4", 100_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR4 32GB Cara", 850_000, "RAM", "https://t/ram-cara"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB", 100_000, "RAM", "https://t/ram"),
                producto("Gabinete Corsair 4000D ATX", 30_000, "Gabinete", "https://t/gab"),
                producto("Fuente Antec 750W 80 Plus Gold ATX", 200_000, "Fuente", "https://t/fuente-gold"),
                producto("Fuente Generica 500W", 20_000, "Fuente", "https://t/fuente-generica"),
                producto("SSD Kingston NV2 1TB", 30_000, "Almacenamiento", "https://t/ssd"));

        // Greedy, la RAM de 850.000 entra (quedaban 850.000) y deja la caja en
        // cero: el slot fuente cae al fallback y elige la generica sin
        // certificar. Con cuotas la RAM ve 450.000, no entra, y la fuente
        // llega con 548.600 — el caso que reporto el usuario.
        PcBuild build = builder.armar(catalogo, 1_000_000, false, Set.of());

        PcPick fuente = build.picks().stream().filter(p -> p.slot().equals("fuente")).findFirst().orElseThrow();
        assertThat(fuente.specs().certificacion()).isEqualTo(Certificacion.GOLD);
        assertThat(build.picks()).extracting(PcPick::url).doesNotContain("https://t/ram-cara");
    }

    @Test
    @DisplayName("lo que un slot no gasta se arrastra al siguiente, que puede pasarse de su propia cuota")
    void loNoGastadoSeArrastraAlSlotSiguiente() {
        List<Product> catalogo = List.of(
                producto("Motherboard Asus Prime B550M-A DDR4 AM4", 1_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 5900 Am4", 40_000, "CPU", "https://t/cpu-caro"),
                producto("Procesador Amd Ryzen 3 5300 Am4", 5_000, "CPU", "https://t/cpu-barato"));

        // La cuota propia del cpu es 28.570, menos que los 40.000 del Ryzen 9;
        // entra igual porque la mother dejo 16.140 sin gastar.
        PcBuild build = builder.armar(catalogo, 100_000, false, Set.of());

        PcPick cpu = build.picks().stream().filter(p -> p.slot().equals("cpu")).findFirst().orElseThrow();
        assertThat(cpu.url()).isEqualTo("https://t/cpu-caro");
    }

    @Test
    @DisplayName("sin presupuesto no hay cuotas: gana el mejor de cada slot, cueste lo que cueste")
    void sinPresupuestoNoHayCuotas() {
        List<Product> catalogo = List.of(
                producto("Placa de Video Nvidia GeForce RTX 5080 16GB", 10_000_000, "GPU", "https://t/gpu-top"),
                producto("Placa de Video Nvidia GeForce RTX 5060 8GB", 100_000, "GPU", "https://t/gpu-media"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of());

        PcPick gpu = build.picks().stream().filter(p -> p.slot().equals("gpu")).findFirst().orElseThrow();
        assertThat(gpu.url()).isEqualTo("https://t/gpu-top");
    }

    // ── excluir fallback ──────────────────────────────────────────────────

    @Test
    @DisplayName("excluir removes an already-shown candidate when another one remains")
    void excluirSacaElCandidatoYaMostradoSiQuedaOtro() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Uno", 10_000, "RAM", "https://t/ram-1"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Dos", 10_000, "RAM", "https://t/ram-2"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of("https://t/ram-1"));

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-2");
    }

    @Test
    @DisplayName("excluir falls back to the full pool when exclusion would empty the slot")
    void excluirCaeAlPoolCompletoSiVaciariaElSlot() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Unica", 10_000, "RAM", "https://t/ram-unica"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of("https://t/ram-unica"));

        assertThat(build.picks()).anyMatch(p -> p.slot().equals("ram") && p.url().equals("https://t/ram-unica"));
    }

    // ── sinStock vs sinCompatible ─────────────────────────────────────────

    @Test
    @DisplayName("a slot with no candidate at all is reported as sinStock, not sinCompatible")
    void slotSinCandidatosVaASinStock() {
        PcBuild build = builder.armar(List.of(), 0, false, Set.of());

        assertThat(build.sinStock()).contains("mother", "cpu", "ram", "gabinete", "fuente", "almacenamiento");
        assertThat(build.sinCompatible()).isEmpty();
    }

    @Test
    @DisplayName("a slot whose only candidates are all vetoed is reported as sinCompatible, not sinStock")
    void slotConCandidatosTodosVetadosVaASinCompatible() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 5 5600 Am4", 100_000, "CPU", "https://t/cpu"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        assertThat(build.sinCompatible()).contains("cpu");
        assertThat(build.sinStock()).doesNotContain("cpu");
    }

    // ── ranking order ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ranking prefers more RAM capacity within the same DDR generation, even at a higher price")
    void rankingPrefiereMasCapacidadEnRam() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Barata", 10_000, "RAM", "https://t/ram-barata"),
                producto("Memoria RAM Corsair Vengeance DDR4 32GB Cara", 20_000, "RAM", "https://t/ram-cara"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-cara");
    }

    // T18: test que fijaba el desempate asc sin presupuesto — cambio de
    // comportamiento pedido por el usuario (2026-09-25). Sin presupuesto es
    // el modo "top top" (D3/D4, fase 8): entre dos candidatos empatados en
    // TODO el eje técnico, ahora gana el más CARO, no el más barato — antes
    // de T18, presupuesto=0 no tenía ningún efecto sobre el desempate de
    // precio, así que esta misma aserción pedía el más barato. El nombre y
    // el armado (sin presupuesto) no cambiaron; sólo el resultado esperado.
    @Test
    @DisplayName("T18: sin presupuesto, un empate en DDR/MHz/GB (EjesTecnicos.RAM) desempata por precio DESC")
    void rankingEmpataEnTecnologiaYDesempataPorPrecio() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Cara", 20_000, "RAM", "https://t/ram-cara"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Barata", 10_000, "RAM", "https://t/ram-barata"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-cara");
    }

    @Test
    @DisplayName("T18: CON presupuesto, el mismo empate en RAM sigue desempatando por precio ASC — sin cambios")
    void conPresupuestoElEmpateEnRamSigueDesempatandoPorPrecioAsc() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Cara", 20_000, "RAM", "https://t/ram-cara"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB Barata", 10_000, "RAM", "https://t/ram-barata"));

        PcBuild build = builder.armar(catalogo, 999_999, false, Set.of());

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-barata");
    }

    // ── T18: targets medidos contra la dev DB (odd/tasks/pc-builder-homelab.md) ──

    @Test
    @DisplayName("T18: sin presupuesto, dos AIO 420mm empatados en radiador desempatan por precio DESC")
    void sinPresupuestoDosAioDe420mmEmpatanYGanaElMasCaro() {
        List<Product> catalogo = List.of(
                producto("Cooler CPU Be Quiet! SILENT LOOP 3 420mm Water Cooler", 244_809, "Cooler",
                        "https://t/silent-loop-3"),
                producto("Water Cooler CPU Gamdias Chione E4 420 Negro", 99_990, "Cooler",
                        "https://t/gamdias-chione"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.ALTA);

        PcPick cooler = build.picks().stream().filter(p -> p.slot().equals("cooler")).findFirst().orElseThrow();
        assertThat(cooler.url()).isEqualTo("https://t/silent-loop-3");
    }

    @Test
    @DisplayName("T18: sin presupuesto y tipoCooler=aire, dos doble-torre empatados desempatan por precio DESC")
    void sinPresupuestoConAirePedidoDosDobleTorreEmpatanYGanaElMasCaro() {
        PreferenciasDeArmado aire =
                new PreferenciasDeArmado(null, null, null, null, null, null, null, null, TipoCooler.AIRE, null);
        List<Product> catalogo = List.of(
                producto("Cooler CPU Be Quiet! DARK ROCK PRO 5", 225_500, "Cooler", "https://t/dark-rock-pro-5"),
                producto("Cooler CPU ID-Cooling FROZN A620 PRO SE", 81_350, "Cooler", "https://t/frozn-a620"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, aire);

        PcPick cooler = build.picks().stream().filter(p -> p.slot().equals("cooler")).findFirst().orElseThrow();
        assertThat(cooler.url()).isEqualTo("https://t/dark-rock-pro-5");
    }

    @Test
    @DisplayName("ranking ties on technology and price break by url ascending")
    void rankingEmpataTodoYDesempataPorUrl() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 16GB B", 10_000, "RAM", "https://t/ram-b"),
                producto("Memoria RAM Corsair Vengeance DDR4 16GB A", 10_000, "RAM", "https://t/ram-a"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of());

        PcPick ram = build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow();
        assertThat(ram.url()).isEqualTo("https://t/ram-a");
    }
}
