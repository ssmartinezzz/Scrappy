package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A top is a sport top, lingerie or a garment: three categories, and Musculosa keeps only the tanks. */
@DisplayName("Top taxonomy — Top Deportivo / Top / Corpino / Musculosa")
class TopTaxonomyTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria(null, nombre);
    }

    @ParameterizedTest(name = "{0} -> Top Deportivo")
    @CsvSource(delimiter = '|', value = {
        "Top adidas Optime Workout De Mujer",
        "Top Deportivo Mujer Vaypol",
        "Sports Bra Nike Dri-Fit",
        "Corpino Deportivo Alto Impacto",
        "Top Under Armour Halo Run Tank",
        "Top Mujer Training Sin Costuras",
        "Top Puma Mujer Negro",
        "Top Soporte Medio Yoga"
    })
    void sportTops(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Top Deportivo");
    }

    @ParameterizedTest(name = "{0} -> Top")
    @CsvSource(delimiter = '|', value = {
        "TOP COZY",
        "Top Margot Crudo",
        "Top Midway Satinado Mujer",
        "CROPTOP 4REAL NEGRO",
        "CROPTOP TRAINING DEPT. BLANCO"
    })
    void fashionTops(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Top");
    }

    @ParameterizedTest(name = "{0} -> Corpino")
    @CsvSource(delimiter = '|', value = {
        "Top encaje negro",
        "Top Lenceria Puntilla Mujer",
        "Top Interior Algodon",
        "Top Bralette Blanco",
        "Bras Cruzado"
    })
    void lingerieTops(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Corpino");
    }

    @ParameterizedTest(name = "{0} -> Musculosa")
    @CsvSource(delimiter = '|', value = {
        "Musculosa Nike Dri-Fit",
        "Tank Top Mujer Gris",
        "Remera Sin Mangas Hombre",
        "Camiseta de Tirantes Basica"
    })
    void tanksStayMusculosa(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Musculosa");
    }

    @Test
    void nonGarmentsWithTopStayOutOfTheGarmentBlock() {
        assertThat(cat("Pancake Protein Top")).isEqualTo("Pancake Proteico");
        assertThat(cat("MRS TASTE BBQ Salsa Top Chef")).isEqualTo("Alimentos");
        assertThat(cat("Muñeca Pinypon Top Girls")).isNotIn("Top", "Top Deportivo", "Musculosa", "Corpino");
    }

    @Test
    void topCuelloStaysARemera() {
        assertThat(cat("Top Cuello Alto Manga Corta")).isEqualTo("Remera");
    }
}
