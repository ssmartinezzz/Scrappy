package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code KW_COMIDA} carried a bare {@code "mani"}, and {@link GarmentTaxonomy#anyMatch}
 * is a plain {@code contains} over the space-padded title — so any word with those
 * four letters inside it matched. Two live examples from the catalog:
 * "Medias adidas Titulares Ale<b>mani</b>a 26" and "Banda Elástica DRB Con
 * <b>Mani</b>jas Simple", both filed as "Alimentos".
 *
 * <p>The file's own convention for whole-word keywords is a padded literal — line 285
 * of GarmentTaxonomy already spells this one {@code " mani "} in a different list.
 * This test pins both directions so the bare form cannot come back.</p>
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("Word boundaries")
@DisplayName("CategoryClassifier — 'mani' must be a whole word")
class CategoryClassifierManiBoundaryTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String categoriaDe(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @Test
    @DisplayName("a word that merely contains 'mani' is not food")
    void alemaniaNoEsComida() {
        assertThat(categoriaDe("Medias adidas Titulares Alemania 26 Unisex"))
                .isNotEqualTo("Alimentos");
        assertThat(categoriaDe("Camiseta adidas Titular Alemania 1994 De Hombre"))
                .isNotEqualTo("Alimentos");
        assertThat(categoriaDe("Banda Elástica DRB Con Manijas Simple"))
                .isNotEqualTo("Alimentos");
    }

    @Test
    @DisplayName("the German kit still classifies as the garment it is")
    void laCamisetaDeAlemaniaSigueSiendoRemera() {
        // Not just "not food" — the accidental food match was shadowing the real
        // answer, so assert the garment actually comes back.
        assertThat(categoriaDe("Camiseta adidas Titular Alemania 1994 De Hombre"))
                .isEqualTo("Remera");
        assertThat(categoriaDe("Medias adidas Titulares Alemania 26 Unisex"))
                .isEqualTo("Medias");
    }

    @Test
    @DisplayName("actual peanut products are still food")
    void elManiDeVerdadSigueSiendoComida() {
        assertThat(categoriaDe("Mani tostado con sal 500g")).isEqualTo("Alimentos");
        assertThat(categoriaDe("Maní japonés 200g")).isEqualTo("Alimentos");
    }
}
