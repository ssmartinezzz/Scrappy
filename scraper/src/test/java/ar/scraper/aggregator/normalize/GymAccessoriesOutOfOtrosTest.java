package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GymAccessoriesOutOfOtrosTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @Test
    void tanksAndStringersAreMusculosas() {
        for (String n : new String[]{"\"Kraft\" tank", "Morley Tank", "Stringer \"Real Athletes\"",
                "Back Day Stringer", "Tank Oversize Cóndor", "Air Tank", "Girl Tank",
                "Compression Tank Turtleneck", "MUSCULOS OVERSIZE SIGNAL CHOCOLATE"}) {
            assertThat(cat(n)).as(n).isEqualTo("Musculosa");
        }
    }

    @Test
    void boxyLongSleeveAndSlimFitAreRemeras() {
        for (String n : new String[]{"Boxy Mesh \"22\"", "Boxy \"Club\"", "Boxy Fit Boxing",
                "Bravío Boxy Fit", "Mkf Long Sleeve", "Compression Long Sleeve Turtleneck",
                "Slim Fit \"Ascend\"", "Track V-Neck"}) {
            assertThat(cat(n)).as(n).isEqualTo("Remera");
        }
    }

    @Test
    void croptopsAndBrasAreCorpinos() {
        for (String n : new String[]{"CROPTOP 4REAL NEGRO", "CROPTOP TRAINING DEPT. BLANCO", "Bras Cruzado"}) {
            assertThat(cat(n)).as(n).isEqualTo("Corpino");
        }
    }

    @Test
    void bandanasAreHeadwear() {
        assertThat(cat("Dealer Bandana")).isEqualTo("Gorra");
        assertThat(cat("Monky Bandana")).isEqualTo("Gorra");
    }

    @Test
    void nonApparelStaysInOtros() {
        for (String n : new String[]{"Gift Card MKF $50000", "Gift Bag", "Sticker Planc",
                "Monky\\Llavero", "Sparks Parfum", "Sistema de Parches", "SALUDO VIRTUAL"}) {
            assertThat(cat(n)).as(n).isEqualTo("Otros");
        }
    }

    @Test
    void namesThatOnlyLookSimilarDoNotMove() {
        assertThat(cat("Think Tank Mochila Photo")).isEqualTo("Mochila");
        assertThat(cat("Tanque de agua 500 litros")).isNotEqualTo("Musculosa");
        assertThat(cat("Impresora Epson EcoTank L3250")).isEqualTo("Impresión");
        assertThat(cat("Jean Slim Fit Azul")).isEqualTo("Jean");
        assertThat(cat("Pantalon Slim Fit Negro")).isEqualTo("Pantalón");
        assertThat(cat("Remera Boxy Oversize Negra")).isEqualTo("Remera");
        assertThat(cat("Brasilia Duffel Bag")).isNotEqualTo("Corpino");
    }
}
