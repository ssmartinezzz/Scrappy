package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un combo ("Kit Mother ... + Procesador ...", "Mini PC ... + Monitor 22"")
 * trae otro componente adentro. Sin presupuesto el desempate por precio es
 * desc (T18), así que el combo ganaba justo por ser más caro — y el armado
 * compraba el procesador dos veces. Sólo entra si es lo único del slot.
 */
class PcBuilderCombosTest {

    private final PcBuilder builder = new PcBuilder();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    private String urlDe(PcBuild b, String slot) {
        return b.picks().stream().filter(p -> p.slot().equals(slot)).findFirst().orElseThrow().url();
    }

    @Test
    @DisplayName("Sin presupuesto, un kit mother + procesador no le gana a una mother suelta")
    void kitMotherMasProcesadorNoGana() {
        List<Product> catalogo = List.of(
                producto("Kit Mother Asrock Z890 TAICHI WIFI + Procesador Intel Core Ultra 9 285K LGA1851", 1_930_287, "Motherboard", "https://t/kit"),
                producto("Motherboard Asrock Z890 Pro RS WIFI LGA1851 DDR5", 400_000, "Motherboard", "https://t/mb"),
                producto("Micro Intel Core ULTRA 9 285K 3.7Ghz LGA1851", 1_305_600, "CPU", "https://t/cpu"));

        PcBuild b = builder.armar(catalogo, 0, false, Set.of());

        assertThat(urlDe(b, "mother")).isEqualTo("https://t/mb");
    }

    @Test
    @DisplayName("Sin presupuesto, un mini PC con monitor y kit no le gana al mini PC solo")
    void miniPcConMonitorNoGana() {
        var mini = new PreferenciasDeArmado(null, null, null, null, null, null, null,
                TamanioGabinete.MINI, null, null);
        List<Product> catalogo = List.of(
                producto("Mini PC CX AMD Ryzen 7 6800H 16GB 480GB + Monitor 22\" + Kit Tec/Mouse", 1_259_089, "Mini PC", "https://t/combo"),
                producto("Mini PC CX AMD Ryzen 7 6800H 16GB 512GB Free", 1_099_999, "Mini PC", "https://t/mini"),
                producto("HD HDD 4TB WD BLUE SATA III 3.5\"", 356_510, "Almacenamiento", "https://t/hdd"));

        PcBuild b = builder.armar(catalogo, 0, false, Set.of(), null, mini, Uso.HOMELAB);

        assertThat(urlDe(b, "minipc")).isEqualTo("https://t/mini");
    }

    @Test
    @DisplayName("Si el combo es lo único del slot, entra igual: un armado incompleto es peor")
    void comboSoloEntraSiEsLoUnico() {
        List<Product> catalogo = List.of(
                producto("Kit Mother Asrock Z890 TAICHI WIFI + Procesador Intel Core Ultra 9 285K LGA1851", 1_930_287, "Motherboard", "https://t/kit"));

        PcBuild b = builder.armar(catalogo, 0, false, Set.of());

        assertThat(urlDe(b, "mother")).isEqualTo("https://t/kit");
    }

    @Test
    @DisplayName("Un '+' que no suma otro componente no es un combo: 80 Plus, Wraith incluido")
    void masQueNoEsCombo() {
        assertThat(PcBuilder.esCombo("Fuente Asrock 850W 80 + Gold")).isFalse();
        assertThat(PcBuilder.esCombo("Procesador AMD Ryzen 5 8500G AM5 + Wraith Stealth Cooler")).isFalse();
        assertThat(PcBuilder.esCombo("Gabinete Kolink + Fuente 500W")).isTrue();
        assertThat(PcBuilder.esCombo("Placa de Video RTX 3060 + Fuente 650W")).isTrue();
        assertThat(PcBuilder.esCombo("Combo Actualización Intel Core i3 14100 8GB")).isTrue();
    }
}
