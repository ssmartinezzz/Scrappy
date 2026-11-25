package ar.scraper.db;

import ar.scraper.testsupport.TestRubros;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Persistence")
@Feature("Catalogue reads")
@Story("The full and the per-URL catalogue reads are one streamed, consistent read")
@DisplayName("ProductRepository — cargarProductos / cargarProductosPorUrl")
class ProductRepositoryCargaPorUrlTest extends PostgresTestBase {

    private ProductRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        repo = repositoryOver(dataSource());
        jdbc = new JdbcTemplate(dataSource());
    }

    private static ProductRepository repositoryOver(DataSource raw) {
        PlatformTransactionManager tm = TestTransactions.manager(raw);
        DataSource aware = TestTransactions.aware(raw);
        SiteRegistry registry = new SiteRegistry(new JdbcSiteSource(aware));
        return new ProductRepository(aware, registry, TestRubros.resolver(registry), tm,
                new SitioCategoriaRepository(aware));
    }

    private static Product producto(String url, double precio, List<String> talles, List<String> badges) {
        return Product.builder().sitio("Sitio").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(talles)
                .ml(new Product.MlScore(80, badges, true, "estable", 20, 0.5, "standard"))
                .rubro("indumentaria").senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).build();
    }

    private void sembrar(Product... ps) {
        assertThat(repo.upsertProductos(List.of(ps)).nuevos()).isEqualTo(ps.length);
    }

    @Test
    @DisplayName("returns only the active rows of the requested URLs, by price, with talles and badges in order")
    void porUrl() {
        sembrar(producto("https://t/a", 300, List.of("XL", "S", "M"), List.of("trending", "verified_deal")),
                producto("https://t/b", 100, List.of("L"), List.of("all_time_low")),
                producto("https://t/c", 200, List.of("M"), List.of()),
                producto("https://t/d", 50, List.of("M"), List.of()));
        jdbc.update("UPDATE productos SET activo = false WHERE url = 'https://t/c'");

        List<Product> r = repo.cargarProductosPorUrl(List.of("https://t/a", "https://t/b", "https://t/c"));

        assertThat(r).extracting(Product::url).containsExactly("https://t/b", "https://t/a");
        Product a = r.get(1);
        assertThat(a.talles()).containsExactly("XL", "S", "M");
        assertThat(a.ml().badges()).containsExactly("trending", "verified_deal");
        assertThat(r.get(0).talles()).containsExactly("L");
    }

    @Test
    @DisplayName("a requested URL the catalogue does not hold is simply absent; duplicates do not duplicate rows")
    void urlDesconocidaYRepetida() {
        sembrar(producto("https://t/a", 10, List.of("M"), List.of()));

        List<Product> r = repo.cargarProductosPorUrl(
                List.of("https://t/a", "https://t/a", "https://t/nope"));

        assertThat(r).extracting(Product::url).containsExactly("https://t/a");
    }

    @Test
    @DisplayName("empty or null input reads nothing and returns an empty list")
    void vacio() {
        sembrar(producto("https://t/a", 10, List.of("M"), List.of()));

        assertThat(repo.cargarProductosPorUrl(List.of())).isEmpty();
        assertThat(repo.cargarProductosPorUrl(null)).isEmpty();
    }

    @Test
    @DisplayName("URLs with quotes, commas and braces travel as data in the array parameter")
    void urlsConCaracteresDeArray() {
        String rara = "https://t/a?x='1',\"2\"&y={3}";
        sembrar(producto(rara, 10, List.of("M"), List.of()));

        assertThat(repo.cargarProductosPorUrl(List.of(rara))).extracting(Product::url).containsExactly(rara);
    }

    @Test
    @DisplayName("a large URL set works in one statement")
    void muchasUrls() {
        sembrar(producto("https://t/a", 10, List.of("M"), List.of()));
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) urls.add("https://t/missing/" + i);
        urls.add("https://t/a");

        assertThat(repo.cargarProductosPorUrl(urls)).extracting(Product::url).containsExactly("https://t/a");
    }

    @Test
    @DisplayName("per-URL rows equal the matching rows of the full load, field for field")
    void igualQueLaCargaCompleta() {
        sembrar(producto("https://t/a", 300, List.of("S", "M"), List.of("trending")),
                producto("https://t/b", 100, List.of("L"), List.of()),
                producto("https://t/c", 200, List.of("M"), List.of("all_time_low", "trending")));

        List<Product> completo = repo.cargarProductos();
        List<Product> parcial = repo.cargarProductosPorUrl(Set.of("https://t/a", "https://t/c"));

        assertThat(parcial).containsExactlyElementsOf(
                completo.stream().filter(p -> !p.url().equals("https://t/b")).toList());
    }

    @Test
    @DisplayName("a failed read THROWS, while the full load keeps its log-and-return contract")
    void fallaLanza() {
        ProductRepository roto = repositoryOver(unreachable());

        assertThatThrownBy(() -> roto.cargarProductosPorUrl(List.of("https://t/a"))).isInstanceOf(RuntimeException.class);
        assertThat(roto.cargarProductos()).isEmpty();
    }

    @Test
    @DisplayName("a SQL error mid-read throws too, not a partial list")
    void errorDeSqlLanza() {
        sembrar(producto("https://t/a", 10, List.of("M"), List.of()));
        jdbc.execute("ALTER TABLE producto_badge RENAME TO producto_badge_x");
        try {
            assertThatThrownBy(() -> repo.cargarProductosPorUrl(List.of("https://t/a")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE producto_badge_x RENAME TO producto_badge");
        }
    }

    @Test
    @DisplayName("reads inside an outer transaction and sees its own uncommitted writes")
    void dentroDeOtraTransaccion() {
        sembrar(producto("https://t/a", 10, List.of("M"), List.of()));
        TransactionTemplate outer = new TransactionTemplate(TestTransactions.manager(dataSource()));

        // A second repository instance would open another connection; the outer one is the bound one.
        List<Product> r = outer.execute(s -> {
            repo.upsertParcial(List.of(producto("https://t/b", 5, List.of("S"), List.of())));
            return repo.cargarProductosPorUrl(List.of("https://t/a", "https://t/b"));
        });

        assertThat(r).extracting(Product::url).containsExactly("https://t/b", "https://t/a");
    }

    @Test
    @DisplayName("the read runs read-only at REPEATABLE READ with a cursor fetch size")
    void snapshotYCursor() {
        sembrar(producto("https://t/a", 10, List.of("M"), List.of()));
        List<String> eventos = new ArrayList<>();

        repositoryOver(recording(dataSource(), eventos)).cargarProductos();

        assertThat(eventos).contains("isolation=" + Connection.TRANSACTION_REPEATABLE_READ,
                "readOnly=true", "autoCommit=false", "fetchSize=" + ProductRepository.FETCH_SIZE);
    }

    private static DataSource unreachable() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw new SQLException("database down");
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataSource recording(DataSource real, List<String> eventos) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = method.invoke(real, args);
                    if (!method.getName().equals("getConnection")) return result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (p, m, a) -> {
                                switch (m.getName()) {
                                    case "setTransactionIsolation" -> eventos.add("isolation=" + a[0]);
                                    case "setReadOnly" -> eventos.add("readOnly=" + a[0]);
                                    case "setAutoCommit" -> eventos.add("autoCommit=" + a[0]);
                                    default -> { }
                                }
                                Object o = m.invoke(result, a);
                                if (!m.getName().equals("prepareStatement")) return o;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (q, sm, sa) -> {
                                            if (sm.getName().equals("setFetchSize")) eventos.add("fetchSize=" + sa[0]);
                                            return sm.invoke(o, sa);
                                        });
                            });
                });
    }
}
