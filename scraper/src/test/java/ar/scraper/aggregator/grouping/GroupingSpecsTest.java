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
        assertSplit("Mousepad y Mat",
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
        assertGroups("Mousepad y Mat", "Mousepad Raptor Ultra Glide", "", "Mousepad Raptor Ultra Glide XL", "");
    }

    @Test
    void splitsVersionsWrittenDifferently() {
        assertSplit("Zapatilla", "Zapatillas Puma Caven 2.0", "Puma",
                "Zapatillas Urbanas Puma Caven III de Hombre", "Puma");
        assertSplit("Botines", "Botines adidas Copa Pure IV Club FG De Hombre", "adidas",
                "Botines adidas Copa Pure III Club Fg", "adidas");
    }

    @Test
    void splitsKidsStatedAsBabyAndAdults() {
        assertSplit("Zapatilla", "Zapatillas Urbanas adidas Grand Court 3.0 de Bebés", "adidas",
                "Zapatillas adidas Grand Court 3.0", "adidas");
    }

    @Test
    void splitsModelNumbersThroughTheCrossCheck() {
        assertSplit("Zapatilla", "Zapatillas New Balance 80", "New Balance",
                "Zapatillas New Balance 370", "New Balance");
    }

    @Test
    void splitsEditionWords() {
        assertSplit("Botines", "Mercurial Vapor 17 Elite", "Nike",
                "Botines Nike Mercurial Vapor 17 Club FG De Hombre", "Nike");
        assertSplit("Mouse", "Mouse Razer Viper V3 PRO Wireless Black", "Razer",
                "Mouse Razer Viper V3 PRO SE Hyperspeed Wireless Black", "Razer");
    }

    @Test
    void neverGroupsBrandOnlyNames() {
        assertSplit("Buzo", "Buzo Lacoste", "Lacoste", "Buzo Lacoste", "Lacoste");
    }

    @Test
    void keepsCpuTogetherDespiteGhzAndCoreCounts() {
        assertGroups("Procesador", "Procesador AMD Ryzen 5 9600X 5.4GHz Turbo AM5 - No Incluye Cooler", "AMD",
                "Procesador AMD Ryzen 5 9600X 6/12 5.4GHz AM5 (No incluye cooler)", "AMD");
    }

    @Test
    void keepsPsuTogetherDespiteAtxAndPcieRevisions() {
        assertGroups("Fuente", "Fuente Corsair 750W Cybenetics Gold RM750e Full Modular ATX 3.1 PCIe 5.1 S/C", "Corsair",
                "Fuente Corsair 750W Cybenetics Gold Full Modular RM750E ATX PCIe 5.1", "Corsair");
    }

    @Test
    void keepsVersionTogetherWhenOneWritesDecimalAndOtherInteger() {
        assertGroups("Zapatilla", "Zapatillas Urbanas adidas Lite Racer 4.0 de Mujer", "adidas",
                "Zapatillas adidas Lite Racer Adapt 4", "adidas");
    }

    @Test
    void keepsSsdTogetherDespiteInchesAndSataRevision() {
        assertGroups("Disco", "Disco Ssd Adata Su650 512gb 2.5\" Sata 3.0", "Adata",
                "Disco SSD Adata SU650 512GB SATA 3 2.5", "Adata");
    }

    @Test
    void keepsHyphenatedCodeTogetherAndBrandOnlyDoesNotKillIt() {
        assertGroups("Zapatilla", "Zapatillas Salomon XT-6", "Salomon", "Zapatillas Salomon Xt 6", "Salomon");
    }

    @Test
    void keepsSameModelAcrossSites() {
        assertGroups("Zapatilla", "Zapatillas adidas Supernova Rise 2", "adidas",
                "Zapatillas adidas Supernova Rise 2", "adidas");
    }

    @Test
    void splitsFootballBootSoles() {
        assertSplit("Botines", "Botines adidas Copa Pure IV Club FG De Hombre", "adidas",
                "Botines adidas Copa Pure IV Club Tf", "adidas");
        assertSplit("Botines", "Botines adidas Predator Club FG De Hombre", "adidas",
                "Botines adidas Predator Club In", "adidas");
        assertSplit("Botines", "Botines Umbro Speciali Classic FG De Hombre", "Umbro",
                "Botines Umbro Speciali Classic In", "Umbro");
        assertSplit("Botines", "Botines adidas F50 Hyperfast Club FG Unisex", "adidas",
                "Botines Multitapón adidas F50 Hyperfast Club", "adidas");
    }

    @Test
    void keepsBootsWithOverlappingOrSynonymSoles() {
        assertGroups("Botines", "Botines adidas Goletto IX FG/MG de Hombre", "adidas",
                "Botines adidas Goletto IX Fg", "adidas");
        assertGroups("Botines", "Botines adidas F50 Messi League Pasto Artificial Unisex", "adidas",
                "Botines adidas F50 Messi League Tf", "adidas");
    }

    @Test
    void splitsSizesWhenACodeSitsBeforeTheLetter() {
        assertSplit("Mousepad y Mat", "Mouse Pad Razer Gigantus V2 L", "", "Mouse Pad Razer Gigantus V2 XXL", "");
        assertSplit("Mousepad y Mat", "Mouse Pad Razer Gigantus V2 Large 450x400mm", "", "Mouse Pad Razer Gigantus V2 XXL", "");
    }

    @Test
    void splitsMenFromWomen() {
        assertSplit("Zapatilla Running", "Zapatillas Running Under Armour Charged Assert 10 de Hombre", "Under Armour",
                "Zapatillas de Running Charged Assert 10 para Mujer", "Under Armour");
    }

    @Test
    void keepsGenderlessAndUnisexWithAGender() {
        assertGroups("Zapatilla", "Zapatillas Under Armour Charged Assert 10", "Under Armour",
                "Zapatillas Running Under Armour Charged Assert 10 de Hombre", "Under Armour");
        assertGroups("Botines", "Botines adidas F50 Hyperfast Club FG Unisex", "adidas",
                "Botines adidas F50 Hyperfast Club FG de Hombre", "adidas");
    }

    @Test
    void splitsPowerSupplyRatingsAndHardwareLines() {
        assertSplit("Fuente", "Fuente Asus 750W 80 Plus Bronze Prime", "", "Fuente Asus Prime 750w 80 Plus Gold", "");
        assertSplit("Cooler", "Cooler CPU ASUS TUF LC III 240 ARGB Water Cooler White", "",
                "CPU Water Cooler 240mm ASUS ROG STRIX LC III 240 ARGB White", "");
    }

    private static Product conGenero(Product p, String genero) {
        return p.toBuilder().genero(genero).build();
    }

    @Test
    void theGeneroFieldSpeaksWhenTheNameIsSilent() {
        assertThat(multi(conGenero(product("A", "Pantalón Puma Evostripe", "Puma", "Pantalón", 1), "mujer"),
                product("B", "Pantalón Puma Evostripe De Hombre", "Puma", "Pantalón", 1))).isEmpty();
        assertThat(multi(conGenero(product("A", "Pantalón Puma Evostripe", "Puma", "Pantalón", 1), "unisex"),
                product("B", "Pantalón Puma Evostripe De Hombre", "Puma", "Pantalón", 1))).hasSize(1);
        assertThat(multi(conGenero(product("A", "Zapatillas Puma Caven III Plus Unisex", "Puma", "Zapatilla", 1), "hombre"),
                product("B", "Zapatillas Puma Caven III Plus de Mujer", "Puma", "Zapatilla", 1))).hasSize(1);
    }

    @Test
    void theGeneroFieldResolvesAMenAndWomenTie() {
        List<ProductGroup> grupos = multi(
                conGenero(product("A", "Pantalón Puma Evostripe", "Puma", "Pantalón", 1), "hombre"),
                product("B", "Pantalón Puma Evostripe De Hombre", "Puma", "Pantalón", 2),
                product("B", "Pantalón Puma Evostripe De Mujer", "Puma", "Pantalón", 3));
        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).getProductos()).extracting(Product::nombre)
                .containsExactlyInAnyOrder("Pantalón Puma Evostripe", "Pantalón Puma Evostripe De Hombre");
    }

    @Test
    void splitsTwoLetterSubModelsAndLighting() {
        assertSplit("Zapatilla Running", "Zapatillas adidas Duramo Sl 2", "adidas",
                "Zapatillas Running adidas Duramo RC 2 de Hombre", "adidas");
        assertSplit("Botines", "Botin de Rugby adidas Kakari Sg", "adidas", "Botines De Rugby adidas Kakari RS De Hombre", "adidas");
        assertSplit("Zapatilla", "Zapatillas Puma Suede Classic", "Puma", "Zapatillas Urbanas Puma Suede XL de Hombre", "Puma");
        assertSplit("Cooler", "Cooler CPU Corsair ICUE TITAN LINK 240 RX RGB White Water Cooler", "",
                "Water Cooler Corsair ICUE Link Titan 240 RX LCD 2X 240MM White", "");
    }

    @Test
    void keepsTheSameSubModelAcrossSites() {
        assertGroups("Zapatilla Running", "Zapatillas adidas Duramo SL 2", "adidas",
                "Zapatillas Running adidas Duramo SL 2 de Hombre", "adidas");
    }
}
