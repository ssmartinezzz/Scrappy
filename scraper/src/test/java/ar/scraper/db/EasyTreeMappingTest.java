package ar.scraper.db;

import ar.scraper.aggregator.NormalizerService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.catalog.UpsertStats;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.SitioCategoriaNodo;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Epic("Persistence")
@Feature("Site category tree")
@Story("Curated leaf mapping and renormalization")
@DisplayName("Easy tree mapping — V72 seed and renormalization of mapped leaves on a real database")
class EasyTreeMappingTest extends PostgresTestBase {

    private static final String V72 = "/db/migration/V72__sitio_categoria_mapeo_hojas_residuales.sql";

    private static final List<SitioCategoriaNodo> ARBOL = List.of(
            new SitioCategoriaNodo(14, null, "Plomería", false),
            new SitioCategoriaNodo(400, 14, "Depósitos y repuestos de estanque", false),
            new SitioCategoriaNodo(16, null, "Herramientas", false),
            new SitioCategoriaNodo(462, 16, "Bolsos y mochilas", false),
            new SitioCategoriaNodo(5, null, "Organización y Limpieza", false),
            new SitioCategoriaNodo(218, 5, "Aromatizantes y Antihumedad", false));

    private JdbcTemplate jdbc;
    private ProductRepository productos;
    private NormalizerService normalizador;
    private ResultAggregator agregador;

    @BeforeEach
    void wire() throws IOException {
        jdbc = new JdbcTemplate(dataSource());
        PlatformTransactionManager tm = TestTransactions.manager(dataSource());
        DataSource conTransaccion = TestTransactions.aware(dataSource());
        SiteRegistry siteRegistry = new SiteRegistry(new JdbcSiteSource(conTransaccion));
        SitioCategoriaRepository arbol = new SitioCategoriaRepository(conTransaccion);
        productos = new ProductRepository(conTransaccion, siteRegistry, new RubroResolver(siteRegistry), tm, arbol);
        normalizador = new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(),
                new BrandExtractor(), new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                new RubroResolver(siteRegistry), new GymratTagger(), new SportResolver(), siteRegistry, arbol);
        agregador = new ResultAggregator(normalizador, mock(PythonRunner.class), mock(MlEnricher.class),
                mock(SenalEnricher.class), mock(FinanciacionEnricher.class), mock(MlOutputPort.class),
                mock(CategoriaStatsPort.class), productos);
        jdbc.execute(migracion());
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(EasyTreeMappingTest.class.getResourceAsStream(V72))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Product delScrape(String url, String nombre, String hoja, int origenId) {
        return Product.builder()
                .sitio("Easy").nombre(nombre).precio(12000.0).url(url).imagenUrl("").categoria(hoja)
                .genero("").talles(List.of()).sitioCategoriaOrigenId(origenId).build();
    }

    private String categoriaGuardada(String url) {
        return jdbc.queryForObject("SELECT categoria FROM productos WHERE url = ?", String.class, url);
    }

    @Test
    @DisplayName("V72 seeds nineteen leaves of Easy, each pointing at an existing categoria")
    void v72SeedsTheResidualLeaves() {
        Map<Integer, String> mapeo = new SitioCategoriaRepository(dataSource()).categoriaPorOrigen("Easy");

        assertThat(mapeo).hasSize(19)
                .containsEntry(179, "Mueble")
                .containsEntry(218, "Artículo de Limpieza")
                .containsEntry(400, "Sanitario")
                .containsEntry(462, "Caja de Herramientas")
                .containsEntry(515, "Accesorio de Herramienta")
                .containsEntry(532, "Herraje");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sitio_categoria c "
                + "LEFT JOIN categoria k ON k.nombre = c.categoria "
                + "WHERE c.categoria IS NOT NULL AND k.nombre IS NULL", Integer.class)).isZero();
    }

    @Test
    @DisplayName("replaying V72 over an existing tree keeps the nodes and only sets the mapping")
    void v72KeepsExistingNodes() throws IOException {
        List<Product> delScrape = List.of(delScrape("http://easy.test/a",
                "Soporte de Ducha Cromo 9x5x5 Cm Pluvius", "Depósitos y repuestos de estanque", 400));
        UpsertStats stats = productos.upsertParcial("Easy", normalizador.normalizar(delScrape), ARBOL);
        assertThat(stats.nuevos()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT padre_id IS NOT NULL FROM sitio_categoria WHERE origen_id = 400",
                Boolean.class)).as("before replaying V72").isTrue();

        jdbc.execute(migracion());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sitio_categoria WHERE origen_id = 400", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT padre_id IS NOT NULL FROM sitio_categoria WHERE origen_id = 400",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("an Easy product in a V72 leaf gets the leaf category when its name abstains, and the name wins otherwise")
    void treeCategoryAppliesWhenTheNameAbstains() {
        List<Product> delScrape = List.of(
                delScrape("http://easy.test/soporte", "Soporte de Ducha Cromo 9x5x5 Cm Pluvius",
                        "Depósitos y repuestos de estanque", 400),
                delScrape("http://easy.test/porta-martillo", "Porta Martillo Y Tenaza Klokan", "Bolsos y mochilas", 462),
                delScrape("http://easy.test/aromatizador", "Aromatizador Automático Limón 9 Grs Glade",
                        "Aromatizantes y Antihumedad", 218),
                delScrape("http://easy.test/canio", "Caño Bajada PVC para Depósito a Cadena Ideal",
                        "Depósitos y repuestos de estanque", 400));

        UpsertStats stats = productos.upsertParcial("Easy", normalizador.normalizar(delScrape), ARBOL);

        assertThat(stats.nuevos()).as("0 is the signature of a swallowed SQL error").isEqualTo(4);
        assertThat(categoriaGuardada("http://easy.test/soporte")).isEqualTo("Sanitario");
        assertThat(categoriaGuardada("http://easy.test/porta-martillo")).isEqualTo("Caja de Herramientas");
        assertThat(categoriaGuardada("http://easy.test/aromatizador")).isEqualTo("Artículo de Limpieza");
        assertThat(categoriaGuardada("http://easy.test/canio")).isEqualTo("Caño");
    }

    @Test
    @DisplayName("renormalizing the catalog twice changes no category on mapped leaves, and no row is rewritten")
    void renormalizeIsIdempotentOnMappedLeaves() {
        List<Product> delScrape = List.of(
                delScrape("http://easy.test/soporte", "Soporte de Ducha Cromo 9x5x5 Cm Pluvius",
                        "Depósitos y repuestos de estanque", 400),
                delScrape("http://easy.test/porta-martillo", "Porta Martillo Y Tenaza Klokan", "Bolsos y mochilas", 462),
                delScrape("http://easy.test/aromatizador", "Aromatizador Automático Limón 9 Grs Glade",
                        "Aromatizantes y Antihumedad", 218),
                delScrape("http://easy.test/canio", "Caño Bajada PVC para Depósito a Cadena Ideal",
                        "Depósitos y repuestos de estanque", 400));
        UpsertStats stats = productos.upsertParcial("Easy", normalizador.normalizar(delScrape), ARBOL);
        assertThat(stats.nuevos()).as("0 is the signature of a swallowed SQL error").isEqualTo(4);

        Map<String, Integer> primera = agregador.renormalizarCatalogo();
        Map<String, Integer> segunda = agregador.renormalizarCatalogo();

        assertThat(primera.get("totalRevisados")).isEqualTo(4);
        assertThat(primera.get("categoriaCambiada")).isZero();
        assertThat(segunda.get("totalRevisados")).isEqualTo(4);
        assertThat(segunda.get("categoriaCambiada")).isZero();
        assertThat(segunda.get("escriturasAplicadas")).isZero();
        assertThat(categoriaGuardada("http://easy.test/soporte")).isEqualTo("Sanitario");
        assertThat(categoriaGuardada("http://easy.test/porta-martillo")).isEqualTo("Caja de Herramientas");
        assertThat(categoriaGuardada("http://easy.test/aromatizador")).isEqualTo("Artículo de Limpieza");
    }
}
