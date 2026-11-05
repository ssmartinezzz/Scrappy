package ar.scraper.db;

import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.catalog.UpsertStats;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.model.Product;
import ar.scraper.model.SitioCategoriaNodo;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Site category tree")
@Story("Tree, links and products persist in one transaction")
@DisplayName("sitio_categoria — tree upsert, product links and atomicity with the site's products")
class SitioCategoriaPersistenceTest extends PostgresTestBase {

    private static final String SITIO = "Easy";
    private static final String SITIO_KEY = "easy";

    private static final List<SitioCategoriaNodo> ARBOL = List.of(
            new SitioCategoriaNodo(2, null, "Baños y Cocinas", false),
            new SitioCategoriaNodo(18, 2, "Sanitarios", false),
            new SitioCategoriaNodo(133, 18, "Inodoros", false),
            new SitioCategoriaNodo(3, null, "Electrodomésticos", false),
            new SitioCategoriaNodo(29, 3, "Instalaciones de electrodomésticos", true));

    @AfterEach
    void dropFailureTrigger() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("DROP TRIGGER IF EXISTS fallar_vinculo_de_prueba ON productos");
        jdbc.execute("DROP FUNCTION IF EXISTS fallar_vinculo_de_prueba()");
    }

    private SitioCategoriaRepository repositorioDeArbol() {
        return new SitioCategoriaRepository(dataSource());
    }

    private SitioCategoriaRepository arbolEnTransaccion() {
        return new SitioCategoriaRepository(TestTransactions.aware(dataSource()));
    }

    private ProductRepository repositorioDeProductos() {
        return repositorioDeProductos(arbolEnTransaccion());
    }

    private ProductRepository repositorioDeProductos(SitioCategoriaPort arbol) {
        PlatformTransactionManager tm = TestTransactions.manager(dataSource());
        DataSource conTransaccion = TestTransactions.aware(dataSource());
        SiteRegistry siteRegistry = new SiteRegistry(new JdbcSiteSource(conTransaccion));
        return new ProductRepository(conTransaccion, siteRegistry, new RubroResolver(siteRegistry), tm, arbol);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private static Product producto(String url, Integer origenId) {
        return Product.builder()
                .sitio(SITIO).nombre("Inodoro con Depósito 3003 Vessanti").precio(210320.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Sanitario").genero("")
                .talles(List.of()).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .marca("").rubro("hogar").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).sitioCategoriaOrigenId(origenId).build();
    }

    private Long idDelNodo(int origenId) {
        return jdbc().queryForObject(
                "SELECT id FROM sitio_categoria WHERE sitio_key = ? AND origen_id = ?", Long.class, SITIO_KEY, origenId);
    }

    private Long padreDelNodo(int origenId) {
        return jdbc().queryForObject(
                "SELECT padre_id FROM sitio_categoria WHERE sitio_key = ? AND origen_id = ?", Long.class, SITIO_KEY, origenId);
    }

    private Long vinculoDelProducto(String url) {
        return jdbc().queryForObject("SELECT sitio_categoria_id FROM productos WHERE url = ?", Long.class, url);
    }

    private int cantidadDeNodos() {
        return jdbc().queryForObject("SELECT count(*) FROM sitio_categoria WHERE sitio_key = ?", Integer.class, SITIO_KEY);
    }

    private int cantidadDeProductos() {
        return jdbc().queryForObject("SELECT count(*) FROM productos", Integer.class);
    }

    @Test
    @DisplayName("upserting a tree stores every node and resolves each parent link")
    void upsertStoresNodesAndParentLinks() {
        int cantidad = repositorioDeArbol().upsertArbol(SITIO, ARBOL);

        assertThat(cantidad).isEqualTo(5);
        assertThat(cantidadDeNodos()).isEqualTo(5);
        assertThat(padreDelNodo(2)).isNull();
        assertThat(padreDelNodo(3)).isNull();
        assertThat(padreDelNodo(18)).isEqualTo(idDelNodo(2));
        assertThat(padreDelNodo(133)).isEqualTo(idDelNodo(18));
        assertThat(padreDelNodo(29)).isEqualTo(idDelNodo(3));
    }

    @Test
    @DisplayName("the service flag is stored per node")
    void serviceFlagIsStored() {
        repositorioDeArbol().upsertArbol(SITIO, ARBOL);

        assertThat(jdbc().queryForList(
                "SELECT origen_id FROM sitio_categoria WHERE sitio_key = ? AND es_servicio", Integer.class, SITIO_KEY))
                .containsExactly(29);
    }

    @Test
    @DisplayName("upserting the same tree again keeps the ids and adds nothing")
    void upsertIsIdempotent() {
        repositorioDeArbol().upsertArbol(SITIO, ARBOL);
        Long idAntes = idDelNodo(133);

        repositorioDeArbol().upsertArbol(SITIO, ARBOL);

        assertThat(cantidadDeNodos()).isEqualTo(5);
        assertThat(idDelNodo(133)).isEqualTo(idAntes);
        assertThat(padreDelNodo(133)).isEqualTo(idDelNodo(18));
    }

    @Test
    @DisplayName("a renamed, reparented and reflagged node is updated in place")
    void changedNodeIsUpdatedInPlace() {
        repositorioDeArbol().upsertArbol(SITIO, ARBOL);
        Long idAntes = idDelNodo(133);

        repositorioDeArbol().upsertArbol(SITIO, List.of(
                new SitioCategoriaNodo(2, null, "Baños y Cocinas", false),
                new SitioCategoriaNodo(18, 2, "Sanitarios", false),
                new SitioCategoriaNodo(133, 2, "Inodoros y bidets", true)));

        assertThat(idDelNodo(133)).isEqualTo(idAntes);
        assertThat(padreDelNodo(133)).isEqualTo(idDelNodo(2));
        assertThat(jdbc().queryForObject("SELECT nombre FROM sitio_categoria WHERE origen_id = 133", String.class))
                .isEqualTo("Inodoros y bidets");
        assertThat(jdbc().queryForObject("SELECT es_servicio FROM sitio_categoria WHERE origen_id = 133", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("a node that loses its parent in the new tree becomes a root")
    void nodeWithoutParentBecomesRoot() {
        repositorioDeArbol().upsertArbol(SITIO, ARBOL);

        repositorioDeArbol().upsertArbol(SITIO, List.of(new SitioCategoriaNodo(133, null, "Inodoros", false)));

        assertThat(padreDelNodo(133)).isNull();
    }

    @Test
    @DisplayName("an unknown site, a null or empty tree and blank names store nothing")
    void unknownSiteAndEmptyTreesStoreNothing() {
        assertThat(repositorioDeArbol().upsertArbol("no-existe", ARBOL)).isZero();
        assertThat(repositorioDeArbol().upsertArbol(null, ARBOL)).isZero();
        assertThat(repositorioDeArbol().upsertArbol(SITIO, null)).isZero();
        assertThat(repositorioDeArbol().upsertArbol(SITIO, List.of())).isZero();
        assertThat(repositorioDeArbol().upsertArbol(SITIO, List.of(new SitioCategoriaNodo(7, null, "", false))))
                .isZero();

        assertThat(cantidadDeNodos()).isZero();
    }

    @Test
    @DisplayName("upsertParcial stores the tree and links each product to its leaf")
    void productsAreLinkedToTheirLeaf() {
        UpsertStats stats = repositorioDeProductos().upsertParcial(SITIO,
                List.of(producto("http://easy.test/inodoro", 133), producto("http://easy.test/instalacion", 29)), ARBOL);

        assertThat(stats.nuevos()).as("0 is the signature of a swallowed SQL error").isEqualTo(2);
        assertThat(vinculoDelProducto("http://easy.test/inodoro")).isEqualTo(idDelNodo(133));
        assertThat(vinculoDelProducto("http://easy.test/instalacion")).isEqualTo(idDelNodo(29));
        assertThat(cantidadDeNodos()).isEqualTo(5);
    }

    @Test
    @DisplayName("a product sent again with another leaf is re-linked")
    void productIsRelinkedToAnotherLeaf() {
        ProductRepository repositorio = repositorioDeProductos();
        UpsertStats primera = repositorio.upsertParcial(SITIO, List.of(producto("http://easy.test/inodoro", 133)), ARBOL);
        assertThat(primera.nuevos()).isEqualTo(1);

        repositorio.upsertParcial(SITIO, List.of(producto("http://easy.test/inodoro", 18)), ARBOL);

        assertThat(vinculoDelProducto("http://easy.test/inodoro")).isEqualTo(idDelNodo(18));
    }

    @Test
    @DisplayName("a product whose leaf is not in the tree has no link, and loses a previous one")
    void unmatchedLeafGivesNullLink() {
        ProductRepository repositorio = repositorioDeProductos();
        UpsertStats primera = repositorio.upsertParcial(SITIO,
                List.of(producto("http://easy.test/inodoro", 133), producto("http://easy.test/huerfano", 9999)), ARBOL);
        assertThat(primera.nuevos()).isEqualTo(2);
        assertThat(vinculoDelProducto("http://easy.test/inodoro")).isEqualTo(idDelNodo(133));
        assertThat(vinculoDelProducto("http://easy.test/huerfano")).isNull();

        repositorio.upsertParcial(SITIO, List.of(producto("http://easy.test/inodoro", 9999)), ARBOL);

        assertThat(vinculoDelProducto("http://easy.test/inodoro")).isNull();
    }

    @Test
    @DisplayName("a product without a leaf keeps the link it already had")
    void productWithoutLeafKeepsItsLink() {
        ProductRepository repositorio = repositorioDeProductos();
        UpsertStats primera = repositorio.upsertParcial(SITIO, List.of(producto("http://easy.test/inodoro", 133)), ARBOL);
        assertThat(primera.nuevos()).isEqualTo(1);

        repositorio.upsertParcial(SITIO, List.of(producto("http://easy.test/inodoro", null)), List.of());

        assertThat(vinculoDelProducto("http://easy.test/inodoro")).isEqualTo(idDelNodo(133));
    }

    @Test
    @DisplayName("re-upserting the tree never overwrites the curated categoria mapping of a leaf")
    void upsertPreservesTheCuratedMapping() {
        repositorioDeArbol().upsertArbol(SITIO, ARBOL);
        jdbc().update("UPDATE sitio_categoria SET categoria = 'Sanitario' WHERE sitio_key = ? AND origen_id = 133", SITIO_KEY);

        repositorioDeArbol().upsertArbol(SITIO, List.of(
                new SitioCategoriaNodo(18, 2, "Sanitarios", false),
                new SitioCategoriaNodo(133, 18, "Inodoros y bidets", false)));

        assertThat(jdbc().queryForObject(
                "SELECT categoria FROM sitio_categoria WHERE sitio_key = ? AND origen_id = 133", String.class, SITIO_KEY))
                .isEqualTo("Sanitario");
        assertThat(repositorioDeArbol().categoriaPorOrigen(SITIO)).containsExactly(Map.entry(133, "Sanitario"));
    }

    @Test
    @DisplayName("categoriaPorOrigen lists only the mapped leaves of that site")
    void categoryByOriginListsOnlyMappedLeaves() {
        repositorioDeArbol().upsertArbol(SITIO, ARBOL);
        jdbc().update("UPDATE sitio_categoria SET categoria = 'Sanitario' WHERE sitio_key = ? AND origen_id = 133", SITIO_KEY);

        assertThat(repositorioDeArbol().categoriaPorOrigen(SITIO)).containsExactly(Map.entry(133, "Sanitario"));
        assertThat(repositorioDeArbol().categoriaPorOrigen("no-existe")).isEmpty();
        assertThat(repositorioDeArbol().categoriaPorOrigen(null)).isEmpty();
    }

    private void fallarAlVincular() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE FUNCTION fallar_vinculo_de_prueba() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'link failed on purpose'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fallar_vinculo_de_prueba BEFORE UPDATE OF sitio_categoria_id ON productos "
                + "FOR EACH ROW EXECUTE FUNCTION fallar_vinculo_de_prueba()");
    }

    @Test
    @DisplayName("a failure after the products were written rolls back the tree, the mapping change and the products")
    void failureRollsBackTreeMappingAndProducts() {
        repositorioDeArbol().upsertArbol(SITIO, List.of(new SitioCategoriaNodo(133, null, "Nombre anterior", false)));
        jdbc().update("UPDATE sitio_categoria SET categoria = 'Sanitario' WHERE sitio_key = ? AND origen_id = 133", SITIO_KEY);
        fallarAlVincular();

        UpsertStats stats = repositorioDeProductos().upsertParcial(SITIO,
                List.of(producto("http://easy.test/inodoro", 133), producto("http://easy.test/instalacion", 29)), ARBOL);

        assertThat(stats.nuevos()).isZero();
        assertThat(cantidadDeProductos()).as("products").isZero();
        assertThat(cantidadDeNodos()).as("tree nodes").isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT nombre FROM sitio_categoria WHERE origen_id = 133", String.class))
                .as("node renamed by the failed run").isEqualTo("Nombre anterior");
        assertThat(jdbc().queryForObject("SELECT categoria FROM sitio_categoria WHERE origen_id = 133", String.class))
                .as("mapping").isEqualTo("Sanitario");
    }

    @Test
    @DisplayName("without the failure the same call commits the tree and the products together")
    void sameCallCommitsWhenNothingFails() {
        UpsertStats stats = repositorioDeProductos().upsertParcial(SITIO,
                List.of(producto("http://easy.test/inodoro", 133), producto("http://easy.test/instalacion", 29)), ARBOL);

        assertThat(stats.nuevos()).isEqualTo(2);
        assertThat(cantidadDeProductos()).isEqualTo(2);
        assertThat(cantidadDeNodos()).isEqualTo(5);
    }

    @Test
    @DisplayName("a failure while storing the tree leaves no product behind")
    void treeFailureLeavesNoProducts() {
        SitioCategoriaPort arbolQueFalla = new SitioCategoriaPort() {
            @Override
            public int upsertArbol(String sitio, List<SitioCategoriaNodo> arbol) {
                arbolEnTransaccion().upsertArbol(sitio, arbol);
                throw new IllegalStateException("tree failed on purpose");
            }

            @Override
            public Map<Integer, String> categoriaPorOrigen(String sitio) {
                return Map.of();
            }
        };

        UpsertStats stats = repositorioDeProductos(arbolQueFalla).upsertParcial(SITIO,
                List.of(producto("http://easy.test/inodoro", 133)), ARBOL);

        assertThat(stats.nuevos()).isZero();
        assertThat(cantidadDeProductos()).isZero();
        assertThat(cantidadDeNodos()).isZero();
    }
}
