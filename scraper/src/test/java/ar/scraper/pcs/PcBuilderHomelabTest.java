package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PcBuilder}'s {@link Uso}-aware overload (D3/D4/D5,
 * pc-builder-homelab T3): a homelab build swaps in a second storage slot and
 * ranks RAM by capacity, without touching a single existing rule. The
 * mini-PC mode (D6) is {@link PcBuilderMiniPcModeTest}.
 */
class PcBuilderHomelabTest {

    private final PcBuilder builder = new PcBuilder();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    private List<Product> catalogoCompleto() {
        return List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"),
                producto("HDD Seagate Ironwolf 4TB NAS", 200_000, "Almacenamiento", "https://t/hdd"));
    }

    // ── refactor contract: GAMING (implícito o explícito) no cambia nada ──

    @Test
    @DisplayName("el 6-arg overload de siempre sigue siendo GAMING, byte a byte")
    void elOverloadDeSiempreSigueSiendoGaming() {
        List<Product> catalogo = catalogoCompleto();

        PcBuild sinUso = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA);
        PcBuild conGamingExplicito =
                builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.GAMING);

        assertThat(conGamingExplicito.picks()).extracting(PcPick::url)
                .containsExactlyElementsOf(sinUso.picks().stream().map(PcPick::url).toList());
        assertThat(conGamingExplicito.sinCompatible()).isEqualTo(sinUso.sinCompatible());
        assertThat(conGamingExplicito.sinStock()).isEqualTo(sinUso.sinStock());
        assertThat(conGamingExplicito.totalEstimado()).isEqualTo(sinUso.totalEstimado());
    }

    @Test
    @DisplayName("GAMING sigue armando un solo slot de almacenamiento — no sistema+datos")
    void gamingArmaUnSoloSlotDeAlmacenamiento() {
        PcBuild build = builder.armar(catalogoCompleto(), 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA,
                Uso.GAMING);

        assertThat(build.picks()).extracting(PcPick::slot).contains("almacenamiento")
                .doesNotContain("sistema", "datos");
    }

    // ── HOMELAB: sistema + datos, sin pisar el mismo producto ────────────

    @Test
    @DisplayName("homelab arma sistema (tech primero) y datos (capacidad primero, HDD preferido)")
    void homelabArmaSistemaYDatos() {
        PcBuild build = builder.armar(catalogoCompleto(), 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA,
                Uso.HOMELAB);

        assertThat(build.picks()).extracting(PcPick::slot).contains("sistema", "datos")
                .doesNotContain("almacenamiento");
        // El NVMe es la mejor tecnología -> gana "sistema"; el HDD tiene más
        // capacidad -> gana "datos" (D4).
        assertThat(build.picks().stream().filter(p -> p.slot().equals("sistema")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/nvme");
        assertThat(build.picks().stream().filter(p -> p.slot().equals("datos")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/hdd");
    }

    @Test
    @DisplayName("con un solo disco en el catálogo, sistema lo elige y datos queda sinStock — nunca el mismo producto dos veces")
    void conUnSoloDiscoDatosQuedaSinStock() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.HOMELAB);

        assertThat(build.picks().stream().filter(p -> p.slot().equals("sistema")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/nvme");
        assertThat(build.sinStock()).contains("datos");
        // Ningún pick repite url — la invariante general, no sólo para storage.
        assertThat(build.picks()).extracting(PcPick::url).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("T11: un disco externo no le gana el slot datos a un SSD interno, aunque tenga más capacidad")
    void unDiscoExternoNoLeGanaElSlotDatosAUnSsdInterno() {
        // Nombres reales de la dev DB (T8): el externo tenía más GB (1TB)
        // que el SSD interno (512GB) y ganaba "datos" porque la capacidad
        // corría antes que la tecnología en el eje ALMACENAMIENTO_DATOS.
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("Disco Duro Externo 1Tb Seagate Portable Drive", 80_000, "Almacenamiento", "https://t/externo"),
                producto("HD HDD 4TB WD BLUE SATA III 3.5\"", 200_000, "Almacenamiento", "https://t/hdd"),
                producto("HD SSD 512GB LEXAR NQ100 SATA III 2.5\"", 60_000, "Almacenamiento", "https://t/ssd"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA,
                Uso.HOMELAB);

        // "sistema" prioriza tecnología conocida -> el SSD interno.
        assertThat(build.picks().stream().filter(p -> p.slot().equals("sistema")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/ssd");
        // "datos" prioriza capacidad ENTRE conocidos -> el HDD de 4TB, nunca
        // el externo de 1TB (tecnología abstenida, va último pase lo que pase).
        assertThat(build.picks().stream().filter(p -> p.slot().equals("datos")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/hdd");
    }

    @Test
    @DisplayName("con dos discos, cada slot de storage elige uno distinto")
    void conDosDiscosCadaSlotEligeUnoDistinto() {
        PcBuild build = builder.armar(catalogoCompleto(), 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA,
                Uso.HOMELAB);
        assertThat(build.picks()).extracting(PcPick::url).doesNotHaveDuplicates();
    }

    // ── RAM homelab: capacidad manda ─────────────────────────────────────

    // Las dos RAM son DDR5 (compatibles con la mother AM5/DDR5 de abajo) — si
    // una fuera DDR4, ReglaDdr la vetaría y el test dejaría de medir el eje
    // de ranking para pasar a medir el veto por accidente.
    @Test
    @DisplayName("homelab elige la RAM de mayor capacidad, aunque sea más lenta")
    void homelabEligeLaRamDeMayorCapacidad() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 16GB 6000MHz", 60_000, "RAM", "https://t/rapida"),
                producto("Memoria RAM Kingston Fury DDR5 64GB 4800MHz", 150_000, "RAM", "https://t/grande"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.HOMELAB);

        assertThat(build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/grande");
    }

    @Test
    @DisplayName("gaming, en el mismo catálogo, sigue eligiendo la RAM más rápida — no cambió")
    void gamingSigueEligiendoLaRamMasRapida() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 16GB 6000MHz", 60_000, "RAM", "https://t/rapida"),
                producto("Memoria RAM Kingston Fury DDR5 64GB 4800MHz", 150_000, "RAM", "https://t/grande"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.GAMING);

        assertThat(build.picks().stream().filter(p -> p.slot().equals("ram")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/rapida");
    }

    // ── vetos existentes intactos en homelab ─────────────────────────────

    @Test
    @DisplayName("los vetos existentes (DDR pedida) siguen corriendo en homelab, sin cambios")
    void losVetosExistentesSiguenCorriendoEnHomelab() {
        List<Product> catalogo = List.of(
                producto("Memoria RAM Corsair Vengeance DDR4 64GB", 60_000, "RAM", "https://t/ram"));
        PreferenciasDeArmado prefs = new PreferenciasDeArmado("DDR5", null, null, null, null, null);

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, prefs, Uso.HOMELAB);

        assertThat(build.sinCompatible()).contains("ram");
        assertThat(build.mensajes().get("ram")).contains("DDR5");
    }

    // ── GPU en homelab, sólo con conGpu ──────────────────────────────────

    @Test
    @DisplayName("homelab sin conGpu no arma gpu")
    void homelabSinConGpuNoArmaGpu() {
        PcBuild build = builder.armar(catalogoCompleto(), 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA,
                Uso.HOMELAB);
        assertThat(build.picks()).extracting(PcPick::slot).doesNotContain("gpu");
    }

    @Test
    @DisplayName("homelab con conGpu arma gpu")
    void homelabConConGpuArmaGpu() {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoCompleto());
        catalogo.add(producto("Asus Dual RTX 4060 8GB", 500_000, "GPU", "https://t/gpu"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.HOMELAB);
        assertThat(build.picks()).extracting(PcPick::slot).contains("gpu");
    }

    // ── cuotas: homelab reparte presupuesto (D5) ─────────────────────────

    @Test
    @DisplayName("con presupuesto acotado, sistema y datos arman cada uno el suyo, sin repetir producto")
    void conPresupuestoAcotadoSistemaYDatosArmanSinRepetir() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"),
                producto("HDD Seagate Ironwolf 4TB NAS", 190_000, "Almacenamiento", "https://t/hdd"));

        PcBuild build = builder.armar(catalogo, 1_500_000, false, Set.of(), null, PreferenciasDeArmado.NINGUNA,
                Uso.HOMELAB);

        assertThat(build.sinStock()).doesNotContain("sistema", "datos");
        assertThat(build.picks()).extracting(PcPick::url).doesNotHaveDuplicates();
    }

    // ── T13: la mother sólo se elige entre plataformas con CPU elegible ──

    @Test
    @DisplayName("T13: en la torre homelab, gama BAJA también descarta la mother AM5 sin CPU compatible")
    void gamaBajaEnHomelabTambienRestringeLaMother() {
        List<Product> catalogo = List.of(
                producto("Motherboard MSI A620M-E PRO DDR5 AM5", 100_000, "Motherboard", "https://t/am5"),
                producto("Motherboard MSI PRO B760M-A WIFI DDR4", 90_000, "Motherboard", "https://t/lga1700"),
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.BAJA, PreferenciasDeArmado.NINGUNA,
                Uso.HOMELAB);

        assertThat(build.picks().stream().filter(p -> p.slot().equals("mother")).findFirst().orElseThrow().url())
                .isEqualTo("https://t/lga1700");
        assertThat(build.sinCompatible()).doesNotContain("cpu", "mother");
    }
}
