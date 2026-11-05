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
import ar.scraper.model.SitioCategoriaNodo;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Category")
@Story("Site tree is an abstention fallback")
@DisplayName("Category reconciliation — name, tree, raw, alias, weight, Otros")
class CategoryReconciliationOrderTest {

    private static final String NOMBRE_VETADO = "Vanitory Colgante 52x48.5x36 Cm Melamina Blanco Alba Amube";
    private static final String NOMBRE_VETADO_CON_PESO = "Dispenser Doble Jabón Líquido Blanco Crystal Rock 500 Ml";
    private static final String NOMBRE_CON_CATEGORIA = "Cemento 25 Kg Loma Negra";

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String categoria(String raw, String nombre, String arbol) {
        return classifier.normalizarCategoria(raw, nombre, arbol);
    }

    @Test
    @DisplayName("1. a name that classifies beats the tree and the raw category")
    void nameBeatsTheTree() {
        assertThat(categoria("Pintura", NOMBRE_CON_CATEGORIA, "Grifería")).isEqualTo("Cemento");
    }

    @Test
    @DisplayName("2. when the name abstains the tree beats the raw category")
    void treeBeatsRaw() {
        assertThat(categoria("Cemento", NOMBRE_VETADO, "Vanitory")).isEqualTo("Vanitory");
        assertThat(categoria("Mini", NOMBRE_VETADO, "Vanitory")).isEqualTo("Vanitory");
    }

    @Test
    @DisplayName("3. without a tree category the raw category classifies")
    void rawClassifiesWithoutTree() {
        assertThat(categoria("Cemento", NOMBRE_VETADO, null)).isEqualTo("Cemento");
        assertThat(categoria("Cemento", NOMBRE_VETADO, "")).isEqualTo("Cemento");
        assertThat(categoria("Cemento", NOMBRE_VETADO, "   ")).isEqualTo("Cemento");
    }

    @Test
    @DisplayName("4. a raw category the classifier cannot read is resolved through the alias table")
    void aliasResolvesRawTheClassifierCannotRead() {
        assertThat(categoria("Mini", NOMBRE_VETADO, null)).isEqualTo("Bolso");
    }

    @Test
    @DisplayName("5. with nothing else, a weight or volume at the end of the name is Alimentos")
    void weightIsTheLastResort() {
        assertThat(categoria(null, NOMBRE_VETADO_CON_PESO, null)).isEqualTo("Alimentos");
        assertThat(categoria("", NOMBRE_VETADO_CON_PESO, null)).isEqualTo("Alimentos");
    }

    @Test
    @DisplayName("6. with nothing at all the category is Otros")
    void otrosIsTheEnd() {
        assertThat(categoria(null, NOMBRE_VETADO, null)).isEqualTo("Otros");
        assertThat(categoria("", NOMBRE_VETADO, "")).isEqualTo("Otros");
        assertThat(categoria("Indumentaria", NOMBRE_VETADO, null)).isEqualTo("Otros");
    }

    @Test
    @DisplayName("the tree also beats the alias table and the weight fallback")
    void treeBeatsAliasAndWeight() {
        assertThat(categoria("Mini", NOMBRE_VETADO_CON_PESO, "Accesorio de Baño")).isEqualTo("Accesorio de Baño");
        assertThat(categoria(null, NOMBRE_VETADO_CON_PESO, "Accesorio de Baño")).isEqualTo("Accesorio de Baño");
    }

    @Test
    @DisplayName("the raw category beats the weight fallback")
    void rawBeatsWeight() {
        assertThat(categoria("Cemento", NOMBRE_VETADO_CON_PESO, null)).isEqualTo("Cemento");
    }

    private static final Map<Integer, String> HOJAS_MAPEADAS = Map.of(
            400, "Sanitario",
            146, "Accesorio de Baño",
            70, "Pastina");

    private NormalizerService normalizador() {
        SiteRegistry.Sitio easy = new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "config", false,
                "construccion");
        SiteRegistry siteRegistry = SiteRegistry.forTesting(Map.of("easy", easy));
        SitioCategoriaPort arbol = new SitioCategoriaPort() {
            @Override
            public int upsertArbol(String sitio, List<SitioCategoriaNodo> nodos) {
                return 0;
            }

            @Override
            public Map<Integer, String> categoriaPorOrigen(String sitio) {
                return HOJAS_MAPEADAS;
            }
        };
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                new RubroResolver(siteRegistry), new GymratTagger(), new SportResolver(), siteRegistry, arbol);
    }

    private static Product producto(String url, String nombre, String categoria, Integer origenId) {
        return Product.builder()
                .sitio("Easy").nombre(nombre).precio(10000.0).url(url).imagenUrl("").categoria(categoria)
                .genero("").talles(List.of()).sitioCategoriaOrigenId(origenId).build();
    }

    @Test
    @DisplayName("normalizing the persisted result again changes no category and no rubro on a mapped leaf")
    void renormalizeIsIdempotentOnMappedLeaves() {
        NormalizerService normalizador = normalizador();
        List<Product> delScrape = List.of(
                producto("http://easy.test/aro", "Aro de Conexión para Inodoro Ideal",
                        "Depósitos y repuestos de estanque", 400),
                producto("http://easy.test/toallero", "Dispenser Doble Jabón Líquido Blanco Crystal Rock",
                        "Accesorios de Baños", 146),
                producto("http://easy.test/cemento", NOMBRE_CON_CATEGORIA, "Pastinas y Accesorios", 70),
                producto("http://easy.test/sin-hoja", NOMBRE_CON_CATEGORIA, "Cementos", 9999));

        List<Product> primera = normalizador.normalizar(delScrape);
        List<Product> segunda = normalizador.normalizar(primera);

        assertThat(primera).extracting(Product::categoria)
                .containsExactly("Sanitario", "Accesorio de Baño", "Cemento", "Cemento");
        assertThat(segunda).extracting(Product::categoria).containsExactlyElementsOf(
                primera.stream().map(Product::categoria).toList());
        assertThat(segunda).extracting(Product::rubro).containsExactlyElementsOf(
                primera.stream().map(Product::rubro).toList());
    }

    @Test
    @DisplayName("a mapped leaf is applied only to products that carry its origin id")
    void treeAppliesOnlyToProductsWithAnOriginId() {
        List<Product> resultado = normalizador().normalizar(List.of(
                producto("http://easy.test/con-hoja", NOMBRE_VETADO, "Vanitorys", 146),
                producto("http://easy.test/sin-id", NOMBRE_VETADO, "Otros", null),
                producto("http://easy.test/hoja-sin-mapeo", NOMBRE_VETADO, "Otros", 9999)));

        assertThat(resultado).extracting(Product::categoria)
                .containsExactly("Accesorio de Baño", "Otros", "Otros");
    }
}
