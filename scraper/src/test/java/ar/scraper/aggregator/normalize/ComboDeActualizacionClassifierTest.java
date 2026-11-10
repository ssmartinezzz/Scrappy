package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** CPU + motherboard upgrade kits are their own category. Names are real dev rows (2026-11-10). */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("CategoryClassifier — Combo de Actualización (CPU + motherboard kits)")
class ComboDeActualizacionClassifierTest {

    private static final String COMBO = "Combo de Actualización";

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Kit Mother ASUS PRIME A520M-K + Procesador AMD Ryzen 5 5500 4.2GHz Turbo AM4 + Wraith Stealth Cooler",
        "Kit Mother ASUS PRIME B760M-A D4 + Procesador Intel Core i7 12700F 4.9GHz Turbo Socket (Sin Video",
        "Kit Procesador Intel Core I7 12700 4.9GHz Turbo (Con Video Integrado) + Mother ASUS PRIME H610M",
        "Kit Mother Asrock Z890 PRO RS + Procesador Intel Core Ultra 5 245K 5.2Ghz Turbo Socket 1851",
        "Combo Actualización Amd Ryzen 5 3400g + Mb A520m + Cooler",
        "Combo Ryzen 5 8400f / A620m / Cooler",
        "COMBO AMD RYZEN 7 5700X+B550+16GB 3200",
        "COMBO ACTUALIZACION AMD RYZEN 3 3200G+A520M",
        "COMBO AMD RYZEN 7 9800X3D+B850+32GB DDR5 5200+WATERCOOLER 240MM",
        "Combo Actualización AMD Ryzen 3 3200g + 16GB DDR4 + Mother a520",
        "Combo Actualización Intel I5-12400F + 16gb Ddr4 + H610M",
    })
    @DisplayName("a CPU, a motherboard and a bundle marker make a combo, ahead of the leading-noun rules")
    void combos(String nombre) {
        assertThat(cat(nombre)).isEqualTo(COMBO);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "PC ARMADA AMD RYZEN 5 5600GT+A520+16GB+1TB M.2 NVME+GABINETE KIT",
        "PC AMD RYZEN 7 8700G+A620+16GB+1TB+GAB 6X ARG",
    })
    @DisplayName("a full PC stays PC")
    void pcCompletaSigueSiendoPc(String nombre) {
        assertThat(cat(nombre)).isEqualTo("PC");
    }

    @Test
    @DisplayName("a CPU with its stock cooler is still a CPU, and a lone motherboard still a Motherboard")
    void piezasSueltas() {
        assertThat(cat("Procesador AMD Ryzen 5 5500 4.2GHz Turbo AM4 + Wraith Stealth Cooler")).isEqualTo("CPU");
        assertThat(cat("Procesador AMD Ryzen 7 7700 5.3GHz Turbo + Corsair NAUTILUS 240 RS LCD Water Cooler")).isEqualTo("CPU");
        assertThat(cat("Mother ASUS PRIME A520M-K AM4 DDR4")).isEqualTo("Motherboard");
        assertThat(cat("Motherboard Gigabyte B650M Gaming WiFi AM5 DDR5")).isEqualTo("Motherboard");
        assertThat(cat("Procesador Intel Pentium G4560 (Chipset H110/B150/B250/H170/Z170) LGA1151 KABY LAKE OEM"))
                .isEqualTo("CPU");
        assertThat(cat("Notebook Acer Aspire 3 A315 Ryzen 5 7520U 16GB 512GB 15.6\" Touch W11")).isEqualTo("Notebook");
    }

    @Test
    @DisplayName("CPU plus cooler without a motherboard is left out: it is a CPU kit, not an upgrade")
    void cpuMasCoolerNoEsCombo() {
        assertThat(cat("Combo Ryzen 5 3400g & Cooler")).isNotEqualTo(COMBO);
        assertThat(cat("Combo Ryzen 5 8400f & Cooler Jiushark Jf100rs")).isNotEqualTo(COMBO);
    }

    @Test
    @DisplayName("the category is canonical and resolves to tecnologia")
    void esTecnologia() {
        assertThat(CategoryGroups.canonicalCategories()).contains(COMBO);
        assertThat(CategoryGroups.esCategoriaTech(COMBO)).isTrue();
        assertThat(new RubroResolver(SiteRegistry.forTesting(java.util.Map.of())).resolver("compragamer", COMBO, "")).isEqualTo("tecnologia");
    }
}
