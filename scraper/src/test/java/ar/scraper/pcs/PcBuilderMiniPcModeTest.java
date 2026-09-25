package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PcBuilder}'s mini-PC mode (D6, pc-builder-homelab T4):
 * {@code Uso.HOMELAB} + {@code tamanioGabinete=MINI} collapses the whole
 * tower into a single "minipc" pick plus a "datos" disk — no mother/cpu/ram/
 * gabinete/fuente/cooler/gpu, even when the catalog has them.
 */
class PcBuilderMiniPcModeTest {

    private final PcBuilder builder = new PcBuilder();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    private static PreferenciasDeArmado conTamanioMini() {
        return new PreferenciasDeArmado(null, null, null, null, null, null, null, TamanioGabinete.MINI, null, null);
    }

    @Test
    @DisplayName("homelab + tamanioGabinete=mini arma sólo minipc + datos, ningún otro slot")
    void miniPcModeArmaSoloMinipcYDatos() {
        List<Product> catalogo = List.of(
                producto("Mini Pc Cx Amd Ryzen 7 6800H 16Gb 480Gb Free", 500_000, "Mini PC", "https://t/minipc"),
                producto("HDD Seagate Ironwolf 4TB NAS", 200_000, "Almacenamiento", "https://t/hdd"),
                // Estos NO deberían participar: el modo mini PC no arma torre.
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conTamanioMini(), Uso.HOMELAB);

        assertThat(build.picks()).extracting(PcPick::slot).containsExactlyInAnyOrder("minipc", "datos");
        assertThat(build.picks()).extracting(PcPick::url).contains("https://t/minipc", "https://t/hdd");
    }

    @Test
    @DisplayName("conGpu=true no agrega un slot gpu en modo mini PC — es una unidad, no componentes sueltos")
    void conGpuNoAgregaSlotGpuEnModoMiniPc() {
        List<Product> catalogo = List.of(
                producto("Mini Pc Cx Amd Ryzen 7 6800H 16Gb 480Gb Free", 500_000, "Mini PC", "https://t/minipc"),
                producto("Asus Dual RTX 4060 8GB", 500_000, "GPU", "https://t/gpu"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), null, conTamanioMini(), Uso.HOMELAB);

        assertThat(build.picks()).extracting(PcPick::slot).doesNotContain("gpu");
    }

    @Test
    @DisplayName("ranking: gama pedida veta (respeta ReglaGama), y a igual gama gana más nivel, luego más RAM")
    void rankingRespetaGamaYPreferNivelYRam() {
        List<Product> catalogo = List.of(
                producto("Mini Pc Cx Amd Ryzen 7 6800H 16Gb 480Gb Free", 500_000, "Mini PC", "https://t/ryzen7"),
                producto("MINI PC ASUS ULTRA 5 225H NUC15CRK BAREBONE S/MEMO S/DISCO", 400_000, "Mini PC",
                        "https://t/ultra5"));

        // Sin gama pedida: gana el de mayor gama (Ryzen 7 -> ALTA sobre Ultra 5 -> MEDIA).
        PcBuild sinGama = builder.armar(catalogo, 0, false, Set.of(), null, conTamanioMini(), Uso.HOMELAB);
        assertThat(pickDe(sinGama, "minipc").url()).isEqualTo("https://t/ryzen7");

        // Pidiendo MEDIA, el de ALTA queda vetado (D6: respeta ReglaGama).
        PcBuild conMedia = builder.armar(catalogo, 0, false, Set.of(), Gama.MEDIA, conTamanioMini(), Uso.HOMELAB);
        assertThat(pickDe(conMedia, "minipc").url()).isEqualTo("https://t/ultra5");
    }

    @Test
    @DisplayName("minipc y datos nunca pisan el mismo producto")
    void minipcYDatosNuncaPisanElMismoProducto() {
        List<Product> catalogo = List.of(
                producto("Mini Pc Cx Amd Ryzen 7 6800H 16Gb 480Gb Free", 500_000, "Mini PC", "https://t/minipc"),
                producto("HDD Seagate Ironwolf 4TB NAS", 200_000, "Almacenamiento", "https://t/hdd"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conTamanioMini(), Uso.HOMELAB);

        assertThat(build.picks()).extracting(PcPick::url).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("gaming con tamanioGabinete=mini NO entra en modo mini PC — el modo es exclusivo de homelab")
    void gamingConTamanioMiniNoEntraEnModoMiniPc() {
        List<Product> catalogo = List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Adata XPG Invader X BTF Black", 90_000, "Gabinete", "https://t/gab"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conTamanioMini(), Uso.GAMING);

        assertThat(build.picks()).extracting(PcPick::slot).doesNotContain("minipc");
        assertThat(build.picks()).extracting(PcPick::slot).contains("mother", "cpu", "ram", "fuente", "almacenamiento");
    }

    private PcPick pickDe(PcBuild build, String slot) {
        return build.picks().stream().filter(p -> p.slot().equals(slot)).findFirst()
                .orElseThrow(() -> new AssertionError("no hay pick para el slot " + slot));
    }
}
