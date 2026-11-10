package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Swim shorts are Malla, not Short or Calzoncillos. Names are real dev rows unless noted (2026-11-10). */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("CategoryClassifier — swim shorts and trunks are Malla")
class SwimShortsAreMallaTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Swim Short Walker Black",
        "Swim Short Peaches Navy",
        "Swim Short Basis Green",
        "Swim Trunk Hombre Estampado",
        "Short de Baño Hombre Estampado",
        "Shorts de Baño Mujer Lisos",
        "Short de Bano Speedo Endurance",
        "Boxer de Baño Hombre Speedo",
        "Bermuda de Baño Hombre Palmeras",
        "Trajes de Baño adidas BÓXERS SOLID SWIM de Hombre",
        "Traje de Baño Entero Mujer",
    })
    @DisplayName("swim short, swim trunk, short/boxer/bermuda de baño and trajes de baño are Malla")
    void mallas(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Malla");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Set de Accesorios de Baño",
        "Cortina de Baño Poliéster 180x200",
        "Organizador de Baño Colgante",
    })
    @DisplayName("bathroom items are not clothing and stay out of Malla")
    void banoNoEsRopa(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Malla");
    }

    @Test
    @DisplayName("plain shorts and plain boxers keep their category")
    void lasPrendasComunesNoCambian() {
        assertThat(cat("Short Deportivo Hombre Running")).isEqualTo("Short");
        assertThat(cat("Short Nike Dri-FIT Entrenamiento")).isEqualTo("Short");
        assertThat(cat("Boxer Algodón Hombre Negro")).isEqualTo("Calzoncillos");
        assertThat(cat("Bermuda Cargo Hombre")).isEqualTo("Bermuda");
    }
}
