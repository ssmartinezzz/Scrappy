package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `close-category-vocabulary` — el test que hace que `categoria` sea un
 * dominio CERRADO, que es la condición que faltaba para poder darle una FK.
 *
 * <p>Hasta ahora {@link CategoryClassifier} tenía una rama que devolvía la
 * PRIMERA PALABRA del breadcrumb de la tienda cuando no matcheaba ningún
 * keyword. Con eso, el vocabulario dependía de lo que escribiera cada tienda:
 * una FK a `categoria(id)` habría degenerado en get-or-create por cada
 * breadcrumb nuevo — la misma basura, ahora con IDs.</p>
 *
 * <p>Los casos de abajo NO son inventados: son los valores que la base de
 * producción tenía fuera del canon, con el nombre real del producto que los
 * generó.</p>
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("categoria is a closed vocabulary")
@DisplayName("CategoryClassifier — el vocabulario de categoría es cerrado")
class CategoryVocabularyIsClosedTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @ParameterizedTest(name = "[{index}] \"{1}\" ({0}) -> {2}")
    @CsvSource({
        // breadcrumb crudo,        nombre real del producto,                     esperado
        "'Abrigos',                 'Trench - Camel',                             Campera",
        "'Coat',                    'Coat Duffel Negro',                          Campera",
        "'Bufandon',                'Bufandón Foal Negro',                        Bufanda",
        "'Mini',                    'Mini Morral Lindor Goma Militar',            Bolso",
        "'Neceser',                 'Neceser Tromso Goma Negro',                  Bolso",
        "'Porta',                   'Porta Celular URL PU Negro',                 Otros",
        // richer-category-taxonomy: sigue sin inventar categoría desde el
        // breadcrumb "Cooling" — pero ya no cae en Otros, porque `Cable` entró
        // al canon y un adaptador ES un adaptador. La aserción de este test es
        // que el resultado esté en el vocabulario cerrado, y lo está.
        "'Cooling',                 'ADAPTADOR COOLERMASTER 90 3X8 PIN PCIE',     Cable",
        "'Tarjetas',                'Gift Cards VCP',                             Otros",
        "'VCP > Lo que sea nuevo',  'Producto sin keyword conocido',              Otros"
    })
    @DisplayName("Los breadcrumbs que antes inventaban categorías ahora mapean o caen en Otros")
    void breadcrumbsQueInventabanCategorias(String raw, String nombre, String esperado) {
        assertThat(classifier.normalizarCategoria(raw, nombre)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("Ninguna entrada, por rara que sea, se sale del vocabulario canónico")
    void ningunaEntradaSeSaleDelCanon() {
        List<String> breadcrumbsHostiles = List.of(
            "Categoria Inventada Por La Tienda", "Wintersale > Nuevo", "?????", "Sale 2026",
            "Freres > Lo Nuevo", "Cooling", "Mini", "Porta", "Tarjetas", "Pc", "Indumentaria",
            "", "   ", ">>>", "a", "Ropa de Cama", "Deco"
        );

        for (String raw : breadcrumbsHostiles) {
            for (String nombre : List.of("Producto Cualquiera", "", "XYZ 123")) {
                String resultado = classifier.normalizarCategoria(raw, nombre);
                assertThat(CategoryGroups.canonicalCategories())
                        .as("categoria('%s', '%s') = '%s' tiene que estar en el canon", raw, nombre, resultado)
                        .contains(resultado);
            }
        }
    }

    @Test
    @DisplayName("Todo alias apunta a una categoría que existe en el canon")
    void todoAliasApuntaAlCanon() {
        CategoryAliases.todos().forEach((alias, destino) ->
                assertThat(CategoryGroups.canonicalCategories())
                        .as("el alias '%s' apunta a '%s', que no está en el canon", alias, destino)
                        .contains(destino));
    }

    @Test
    @DisplayName("Almacenamiento y Otros entraron al canon")
    void elCanonIncluyeLasCategoriasNuevas() {
        assertThat(CategoryGroups.canonicalCategories()).contains("Almacenamiento", "Otros");
    }
}
