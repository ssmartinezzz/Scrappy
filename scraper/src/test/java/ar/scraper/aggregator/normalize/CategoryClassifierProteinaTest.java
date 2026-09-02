package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Proteína" bucket used to be the widest net in {@code clasificarNutricion}
 * and it ran near the top, so it swallowed 61 of its own 201 rows (30%, measured
 * against the live catalog on 2026-09-02). Every name asserted here is a real
 * catalog row, not an invented one.
 *
 * <p>Three token families caused it, and two of them are BRANDS that happen to
 * contain a protein word: "MYPROTEIN"/"The Protein Lab" (17 rows) and
 * "Natural Whey" (13 rows, a supplement brand that sells no whey at all in this
 * catalog). The third was {@code "concentrate"}, which matched "Ultra
 * Concentrate" on fat burners.</p>
 */
@Epic("Normalization")
@Feature("Category")
@DisplayName("CategoryClassifier — the protein bucket: narrowing and split")
class CategoryClassifierProteinaTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String clasificar(String nombre) {
        return classifier.normalizarCategoria(null, nombre);
    }

    @Nested
    @DisplayName("A brand name is not a product noun")
    class MarcasQueNombranProteina {

        @Test
        @DisplayName("MYPROTEIN Shaker is a bottle, not a protein")
        void shakerDeMyproteinNoEsProteina() {
            Allure.parameter("nombre", "MYPROTEIN Shaker Deportivo Pro 600ml");
            assertThat(clasificar("MYPROTEIN Shaker Deportivo Pro 600ml")).isNotEqualTo("Proteína");
        }

        @Test
        void vitaminasDeMyproteinSonVitaminas() {
            assertThat(clasificar("MYPROTEIN Vitamins D3 180 softgels")).isEqualTo("Vitaminas");
        }

        @Test
        void magnesioDeMyproteinEsMagnesio() {
            assertThat(clasificar("Myprotein Zinc & Magnesio 90 cápsulas")).isEqualTo("Magnesio");
        }

        @Test
        void omega3DeTheProteinLabEsVitaminas() {
            assertThat(clasificar("The Protein Lab Omega 3 2000 Mg 60 Cápsulas Sin Sabor"))
                    .isEqualTo("Vitaminas");
        }

        @Test
        @DisplayName("Stripping the brand must not cost the brand its real whey")
        void wheyDeMyproteinSigueSiendoProteina() {
            assertThat(clasificar("MYPROTEIN Impact Whey Protein 1kg")).isEqualTo("Proteína");
        }

        @Test
        void magnesioDeNaturalWheyEsMagnesio() {
            assertThat(clasificar("Suplemento En Polvo Natural Whey Suplementos Citrato De Magnesio En Sachet De 250g"))
                    .isEqualTo("Magnesio");
        }

        @Test
        void colagenoDeNaturalWheyEsColageno() {
            assertThat(clasificar("Suplemento en polvo Natural Whey Antiage Colágeno x 250g"))
                    .isEqualTo("Colágeno");
        }

        @Test
        void taurinaDeNaturalWheyNoEsProteina() {
            assertThat(clasificar("Taurina Pura 250 Gramos Natural Whey")).isNotEqualTo("Proteína");
        }

        @Test
        void vitaminaCDeNaturalWheyEsVitaminas() {
            assertThat(clasificar("Vitamina C Ácido Ascórbico En Polvo 250 Gr Natural Whey Suplementos"))
                    .isEqualTo("Vitaminas");
        }
    }

    @Nested
    @DisplayName("\"concentrate\" describes a dose, not a protein")
    class ConcentrateNoEsSenalDeProteina {

        @Test
        void quemadorUltraConcentrateEsQuemador() {
            assertThat(clasificar("NUTREX Lipo6 Black Hers Ultra Concentrate 60 Cápsulas"))
                    .isEqualTo("Quemadores");
        }

        @Test
        void hmbUltraConcentratedNoEsProteina() {
            assertThat(clasificar("GOLD NUTRITION HMB Ultra Concentrated 60 Cápsulas"))
                    .isNotEqualTo("Proteína");
        }

        @Test
        @DisplayName("Dropping the token must not cost a real concentrated whey")
        void wheyConcentradaSigueSiendoProteina() {
            assertThat(clasificar("Ena 100% Whey Protein. Proteina Concentrada")).isEqualTo("Proteína");
        }
    }

    @Nested
    @DisplayName("A bar is a bar, whatever it is made of")
    class BarrasYSnacks {

        @Test
        @DisplayName("\"barrita de proteína\" is 13 rows and matched no bar keyword")
        void barritasDeProteinaSonBarra() {
            assertThat(clasificar("MRS TASTE Caja Barritas de Proteína 12 unidades 540 g"))
                    .isEqualTo("Barra Proteica");
        }

        @Test
        void barrasDeProteinaEnPluralSonBarra() {
            assertThat(clasificar("B3ST Barras Proteina MIX SABORES 16 barras 60gr c/u 20GR PROTEINA"))
                    .isEqualTo("Barra Proteica");
        }

        @Test
        void barraDeWheyProteinEsBarra() {
            assertThat(clasificar("BULL BAR 60GR Barra de Whey Protein - 1 Unid"))
                    .isEqualTo("Barra Proteica");
        }

        @Test
        void alfajorProteicoEsSnack() {
            assertThat(clasificar("SIGMA Alfajor Proteico Whey 15g – caja 12unid 63gr c/u"))
                    .isEqualTo("Snack Proteico");
        }
    }

    @Nested
    @DisplayName("A gainer says \"gainer\" without saying \"mass\"")
    class Gainers {

        @Test
        void gainerPeladoEsGainer() {
            assertThat(clasificar("DULKRE SPORT GAINER WHEY PROTEIN BANANA 1.5KG")).isEqualTo("Gainer");
        }

        @Test
        void trueMassEsGainer() {
            assertThat(clasificar("Suplemento En Polvo Bsn True-mass 1200 Proteína En Bolsa De 4.65kg"))
                    .isEqualTo("Gainer");
        }
    }
}
