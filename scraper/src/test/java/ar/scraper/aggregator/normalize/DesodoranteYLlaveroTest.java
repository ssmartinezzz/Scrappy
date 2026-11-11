package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real dev-catalogue names (2026-11-11): deodorants leave Perfume, keychains leave Otros/Juguete/Figura. */
class DesodoranteYLlaveroTest {

    private final CategoryClassifier clasificador = new CategoryClassifier();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Desodorante Kevingston 27 160 ML|Desodorante",
            "Desodorante Racing Team 160ml|Desodorante",
            "Antitranspirante Roll On Mujer 50 ml|Desodorante",
            "Desodorante Fragancia Citrus 150 ml|Desodorante",
            "Perfume Mujer Eau de Parfum 100ml|Perfume",
            "Body Splash Vainilla 200 ml|Perfume",
            "Llavero Insigne Full Black|Llavero",
            "Funko Pop! Llavero: The Flash|Juguete",
            "Nes Controller Keychain PYRAMID - Llavero|Llavero",
            "POKEMON RUBBER REEL KEYCHAIN - Pikachu|Llavero",
            "LLAVERO DE CUERO FLOWER|Llavero",
            "Monky\\Llavero|Llavero",
            "Multitool Mario - Llavero, destapador y destornillador!|Llavero",
            "Mochila con llavero|Mochila",
            "Mini Camara Digital Dellorean D55 Pantalla Rebatible Llavero Retro|Cámara",
            "PIN SCHNAUZER|Bijouterie",
            "Pins Esmaltados Gatito|Bijouterie"
    })
    void classifiesTheNewCategories(String nombre, String esperada) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} is not Bijouterie")
    @CsvSource(delimiter = '|', value = {
            "Cable HDMI 24 pin 1.5 m",
            "Conector 24 pin Macho"
    })
    void aPinThatDoesNotLeadIsNotJewelry(String nombre) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isNotEqualTo("Bijouterie");
    }

    @Test
    void desodoranteIsPerfumeriaAndLlaveroBelongsToNoRubroGroup() {
        assertThat(CategoryGroups.canonicalCategories()).contains("Desodorante", "Llavero");
        assertThat(CategoryGroups.esCategoriaPerfumeria("Desodorante")).isTrue();
        assertThat(CategoryGroups.esCategoriaPerfumeria("Llavero")).isFalse();
        assertThat(CategoryGroups.esIndumentariaOCalzado("Llavero")).isFalse();
    }

    @Test
    void desodoranteGetsNoPerfumeSubcategory() {
        assertThat(new SubcategoryResolver().resolver("Desodorante Body Splash 160 ML", "Desodorante")).isEmpty();
    }
}
