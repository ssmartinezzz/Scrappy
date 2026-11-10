package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Shin guards are sports accessories whatever else the name says; a tubeless sealant injector is a bike part. */
class CanilleraYSellanteTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria(null, nombre);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Canilleras Nike Mercurial Lite",
        "Canilleras adidas Medias con Niños",
        "Canillera Puma Ultra Flex",
        "Canilleras Boxer Adulto",
        "Canilleras Topper Sneaker Pro"
    })
    void canillerasAreAccesorioDeportivo(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Accesorio Deportivo");
    }

    @Test
    void noSecondaryNounStealsACanillera() {
        assertThat(cat("Canilleras adidas Medias con Niños")).isNotEqualTo("Medias");
        assertThat(cat("Canilleras Nike Mercurial Lite")).isNotIn("Botines", "Zapatilla", "Sneaker");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Inyector De Sellante Top Mega Tubeless",
        "Sellante Tubeless 250ml",
        "Inyector Sellante Tubeless Presta"
    })
    void tubelessSealantIsABikePart(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Repuesto de Bicicleta");
    }

    @Test
    void constructionSealantIsNotABikePart() {
        assertThat(cat("Sellante Poliuretano Sikaflex 300ml")).isNotEqualTo("Repuesto de Bicicleta");
    }
}
