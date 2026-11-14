package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ar.scraper.aggregator.grouping.GroupingTestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Aggregation & Grouping")
@Feature("Grouping")
@Story("Spec guard")
@DisplayName("GroupingService — names stating different specs never group")
class GroupingSpecsTest {

    private final GroupingService grouping = new GroupingService(new ProductIdentity(), new JaccardSimilarity());

    private List<ProductGroup> multi(Product... ps) {
        return grouping.agrupar(List.of(ps), true);
    }

    private void assertSplit(String catA, String nameA, String brandA, String nameB, String brandB) {
        assertThat(multi(product("A", nameA, brandA, catA, 1), product("B", nameB, brandB, catA, 1))).isEmpty();
    }

    private void assertGroups(String cat, String nameA, String brandA, String nameB, String brandB) {
        assertThat(multi(product("A", nameA, brandA, cat, 1), product("B", nameB, brandB, cat, 1))).hasSize(1);
    }

    @Test
    void splitsMousepadSizes() {
        assertSplit("Mousepad",
                "Mouse Pad Raptor Ultra Glide Antideslizante Impermeable S", "",
                "MOUSEPAD RAPTOR ULTRA GLIDE ANTIDESLIZANTE IMPERMEABLE XL (900x400x3MM)", "");
    }

    @Test
    void splitsGpuModelNumbersAndTiers() {
        assertSplit("GPU", "Placa de Video MSI GeForce RTX 5080 16GB GDDR7 VENTUS 3X OC", "MSI",
                "Placa de Video MSI RTX 5060 Ti Ventus 3X 16GB GDDR7", "MSI");
        assertSplit("GPU", "Placa de Video ASUS DUAL GeForce RTX 5060 8GB GDDR7 OC", "ASUS",
                "Placa de Video Asus RTX 5060 Ti Dual 8GB GDDR7 OC", "Asus");
    }

    @Test
    void splitsWheyWeights() {
        assertSplit("Proteina", "GOLD NUTRITION 100% Whey Protein 2 LB", "",
                "Whey Protein 100% 5lbs Gold Nutrition Proteína Chocolate", "");
    }

    @Test
    void splitsRamFrequencies() {
        assertSplit("Memoria RAM", "Memoria RAM Corsair 8GB DDR4 3200 MHz Vengeance LPX", "Corsair",
                "Memoria Ram Corsair Vengeance LPX Black 8GB 3000 Mhz DDR4", "Corsair");
    }

    @Test
    void splitsBackpackVolumes() {
        assertSplit("Mochila", "Mochila Asics Daypack Performance 25 L", "",
                "Mochila Asics 8L Performance", "");
    }

    @Test
    void splitsPowerSupplyRevisions() {
        assertSplit("Fuente", "Fuente 650W Cooler Master MWE V2 80 PLUS Bronze", "",
                "Fuente Cooler Master 650W 80 Plus Bronze MWE V3", "");
    }

    @Test
    void keepsHeadsetWithSkuTogether() {
        assertGroups("Auricular", "AURICULAR WIRELESS LOGITECH ZONE 300 WHITE 981-001416", "",
                "Auricular Logitech Wireless Zone 300 White", "");
    }

    @Test
    void keepsSsdTogetherDespiteSpeedAndFormFactor() {
        assertGroups("Disco", "Disco Solido SSD M.2 ADATA 1TB Legend 860 6000MB/s NVMe PCIe Gen4 x4", "",
                "HD SSD 1TB ADATA LEGEND 860 M.2 NVME GEN4 6000MB/S 2280", "");
    }

    @Test
    void keepsEquivalentVolumesTogether() {
        assertGroups("Botella", "Nike Tr Renew 24 Oz", "", "Botella Nike Tr Renew 700ml", "");
    }

    @Test
    void ignoresFanCounts() {
        assertGroups("Gabinete", "Gabinete Msi Mag Forge M100a Acrilico Matx Fan Front Rgb X3", "",
                "Gabinete Msi Mag Forge M100a 4x Fan Rgb M-atx", "");
    }

    @Test
    void abstainsWhenOneSideIsSilent() {
        assertGroups("Mousepad", "Mousepad Raptor Ultra Glide", "", "Mousepad Raptor Ultra Glide XL", "");
    }
}
