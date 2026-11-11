package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Taxonomy fix: Vincha, Bandana, Traje Deportivo, Funko is always Juguete, LEGO subcategory")
class TaxonomiaGymratJuguetesTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subcategorias = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria(null, nombre);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Vincha Deportiva Nike Negra|Vincha",
        "Vincha Elastica Kappa|Vincha",
        "Bandana Estampada Paisley|Bandana",
        "Bandana Nike Negra|Bandana",
        "Bermuda Bandana Colors|Bermuda",
        "Amiibo Kirby Air Riders - Bandana Waddle Dee & Winged Star|Figura Coleccionable",
        "Plush:Little Buddy Kirby Bandana Waddle Dee 4.5 Plush|Peluche",
        "Soporte Vincha Cabeza Magnetico SJCAM S10 Arnes Cabeza POV Vlog|Cámara",
        "Gorra Trucker Negra|Gorra",
        "Vincha Wilson Bandana|Vincha",
        "Vinchas Under Armour|Vincha",
    })
    void vinchaYBandana(String nombre, String esperada) {
        assertThat(cat(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Tracksuit Stripe Mocha|Traje Deportivo",
        "Catsuit Kappa Authentic Vetra|Traje Deportivo",
        "Conjunto Puma Teamrise Tracksuit|Traje Deportivo",
        "Conjunto adidas 3-Stripes Tracksuit|Traje Deportivo",
        "CAMPERA TRACKSUIT NEGRA|Campera",
        "PANTALÓN TRACKSUIT MARRÓN|Pantalón",
        "Pantalón Reebok Vector Tracksuit|Pantalón",
        "Traje de Baño Entero|Malla",
        "Traje Sastre Hombre|Traje",
    })
    void trajeDeportivo(String nombre, String esperada) {
        assertThat(cat(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Funko Pop! Llavero The Flash",
        "Mochila Funko POP Villanos",
        "Funko Pop! One Piece Luffy Mini-Backpack MOCHILA",
        "Funko Pop! Alien 3 - Ripley & The Runner",
        "Funko Pop! Marvel Spider-Man",
        "Funko Plush Stitch 20cm",
        "Peluche Funko Pop Grogu",
        "Remera Funko Pop Batman",
        "Mochila Funko/Sanrio: Hello Kitty Mini-Backpack",
    })
    void funkoEsSiempreJuguete(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Juguete");
        assertThat(subcategorias.resolver(nombre, "Juguete")).as("sub_categoria").isEmpty();
        assertThat(CategoryGroups.esCategoriaJuguetes(cat(nombre))).as("rubro juguetes").isTrue();
    }

    @Test
    void nuevasCategoriasEstanEnElCanonDeIndumentaria() {
        assertThat(CategoryGroups.canonicalCategories()).contains("Vincha", "Bandana", "Traje Deportivo");
        assertThat(CategoryGroups.esIndumentariaOCalzado("Vincha")).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "LEGO Classic Caja Creativa 10696|lego",
        "Lego Futbol Equipo Barcelona|lego",
        "LEGO: Cuentos Fantasticos Sobre Dragones|lego",
        "Juguete Lego City Runner|lego",
        "Legolas Figura Hobbit|",
    })
    void legoEsSubcategoriaDeJuguete(String nombre, String esperada) {
        assertThat(subcategorias.resolver(nombre, "Juguete")).isEqualTo(esperada == null ? "" : esperada);
    }

    @Test
    void legoVideojuegoYLibroNoCambian() {
        assertThat(subcategorias.resolver("LEGO Star Wars Skywalker Saga PS5", "Videojuego")).isEmpty();
        assertThat(subcategorias.resolver("LEGO Harry Potter Guia Visual", "Libro")).isEmpty();
    }
}
