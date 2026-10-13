package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Category")
@DisplayName("CategoryClassifier — construccion and hogar vocabulary")
class ConstruccionHogarCategoryClassifierTest {

    private static final String FIXTURE = "/fixtures/rubros-construccion-hogar/names.tsv";

    private final CategoryClassifier classifier = new CategoryClassifier();

    static Stream<Arguments> realNames() throws IOException {
        List<Arguments> rows = new ArrayList<>();
        try (InputStream in = ConstruccionHogarCategoryClassifierTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) throw new IOException(FIXTURE + " not found on the classpath");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", 3);
                rows.add(Arguments.of(c[0], c[2], c[1]));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "[{0}] \"{1}\" -> {2}")
    @MethodSource("realNames")
    @DisplayName("every real product name of the three stores lands in its expected category")
    void realNamesLandInTheirCategory(String site, String nombre, String esperado) {
        assertThat(classifier.normalizarCategoria(null, nombre)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("the fixture is the whole catalog, not a sample")
    void fixtureIsTheWholeCatalog() throws IOException {
        assertThat(realNames().count()).isGreaterThanOrEqualTo(170);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "'Funda de Silla Pana',                       Funda",
            "'Manta Polar Tejida',                        Manta",
            "'Almohadón Pana Mostaza',                    Almohadón",
            "'Cartucho Adhesivo Sellador 280 ml',         Adhesivo",
            "'Adhesivo Polímero Híbrido Gris 300 ml',     Adhesivo",
            "'Pack de 12 Servilletas Tusor 30x30 Cm',     Mantel",
    })
    @DisplayName("the mis-routes measured before this change")
    void measuredMisroutes(String nombre, String esperado) {
        assertThat(classifier.normalizarCategoria(null, nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" keeps {1}")
    @CsvSource({
            "'Funda Sobre para Laptop',                                    Notebook",
            "'Funda Celular Samsung Flip Wallet A3 2016 black',            Billetera",
            "'Alfombra Para Silla Gamer GXT 715 Trust',                    Silla",
            "'ALFOMBRA GAMER COOLERMASTER FLOOR MAT FM510',                Cooler",
            "'Alfombrilla de escritorio Xl',                               'Mat Escritorio'",
            "'Lámpara de Piso Led',                                        Iluminación",
            "'Zapatillas Nike Terra Manta',                                Zapatilla",
            "'Combo ASUS Placa de video RTX 5070 12GB PRIME + Fuente ASUS Prime 750W', GPU",
            "'PLACA DE RED ETHERNET TP-LINK TX201 2.5 GIGABIT PCIE',       Red",
            "'Funda para Silla Gamer Cooler Master',                       Silla",
            "'Chomba adidas Performance Estampado Texturado Hombre',       Chomba",
            "'Alfombra Thermaltake Sim Para Cockpits y Simulador Black',   Otros",
    })
    @DisplayName("look-alike products of other rubros keep their category")
    void lookAlikesAreNotStolen(String nombre, String esperado) {
        assertThat(classifier.normalizarCategoria(null, nombre)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("football boots for artificial turf are not artificial turf")
    void bootsForTurfStayBoots() {
        assertThat(classifier.normalizarCategoria(null,
                "Botines adidas con Lengüeta Plegable y Cordones Elásticos para Césped Artificial PREDATOR Niños"))
                .isEqualTo("Botines");
    }

    @Test
    @DisplayName("the new categories are canonical and grouped by rubro")
    void newCategoriesAreCanonical() {
        assertThat(CategoryGroups.categoriasConstruccion()).containsExactlyInAnyOrder(
                "Placa Decorativa", "Vinilo Decorativo", "Piso", "Césped Sintético",
                "Adhesivo", "Moldura", "Planta Artificial");
        assertThat(CategoryGroups.categoriasHogar()).containsExactlyInAnyOrder(
                "Almohadón", "Funda", "Manta", "Mantel", "Alfombra", "Respaldo");
        assertThat(CategoryGroups.canonicalCategories())
                .containsAll(CategoryGroups.categoriasConstruccion())
                .containsAll(CategoryGroups.categoriasHogar());
    }
}
