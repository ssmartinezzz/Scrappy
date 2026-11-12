package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Normalization")
@Feature("Category")
@DisplayName("El Mundo del Juguete: a LEGO set on a toy leaf is LEGO, not Juguete")
class ElMundoLegoLeafTest {

    private static final int HOJA_JUGUETE = 1;
    private static final int HOJA_LIBROS = 2;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "elmundodeljuguete", new SiteRegistry.Sitio("Elmundodeljuguete", "elmundodeljuguete",
                        "vtex", false, null, "db", false, "juguetes")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        // V78 maps the 'Lego' and 'Sets de Ladrillos y Bloques' leaves to Juguete and 'Libros' to Libro.
        when(arbol.categoriaPorOrigen("Elmundodeljuguete"))
                .thenReturn(Map.of(HOJA_JUGUETE, "Juguete", HOJA_LIBROS, "Libro"));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), new RubroResolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String nombre, int hoja) {
        Product p = Product.builder().sitio("Elmundodeljuguete").nombre(nombre).url("https://x/" + nombre)
                .sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("real LEGO sets on a toy leaf are LEGO, in the toys rubro")
    void legoSetsOnAToyLeafAreLego() {
        for (String nombre : List.of("Lego City Jet Vs Car 259 Piezas", "Lego Pokemon Eevee 587 Piezas",
                "Lego Botanicals Petite Sunny Bouquet  373 Piezas",
                "Lego Star Wars Siege of Mandalore Battle Pack 116 Piezas")) {
            Product p = normalizar(nombre, HOJA_JUGUETE);
            assertThat(p.categoria()).as(nombre).isEqualTo("LEGO");
            assertThat(p.rubro()).as(nombre).isEqualTo("juguetes");
        }
    }

    @Test
    @DisplayName("a football-themed LEGO set carries no sport sub-category")
    void footballLegoHasNoSportSubcategory() {
        Product p = normalizar("Lego Fenomenos del Futbol Highlights Edition Kylian Mbappe 490 Piezas", HOJA_JUGUETE);
        assertThat(p.categoria()).isEqualTo("LEGO");
        assertThat(p.subCategoria()).isEmpty();
    }

    @Test
    @DisplayName("LEGO activity books on the Libros leaf stay Libro")
    void legoBooksStayBooks() {
        for (String nombre : List.of("Libro Lego Construye Y Pega El Espacio", "Lego: Construye y Pega",
                "Lego: Misión Galáctica", "Libro Lego Recreate Tarjetas Con Actividades De Magia")) {
            assertThat(normalizar(nombre, HOJA_LIBROS).categoria()).as(nombre).isEqualTo("Libro");
        }
    }

    @Test
    @DisplayName("a non-LEGO toy on a toy leaf stays Juguete")
    void otherToysStayJuguete() {
        assertThat(normalizar("Muñeco The Avengers Titan Hero Iron Man", HOJA_JUGUETE).categoria())
                .isEqualTo("Juguete");
    }
}
