package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The final upsert used to serialise the whole catalogue into one JSON String (run 26 died with an
 * OutOfMemoryError there). It now sends fixed-size batches inside the same transaction; results
 * and stats must not depend on the batch size.
 */
@DisplayName("ProductRepository.upsertProductos — batched, streamed payload")
class ProductRepositoryBatchedUpsertTest extends PostgresTestBase {

    private final AtomicInteger upsertRunCalls = new AtomicInteger();
    private final AtomicInteger vincularCalls = new AtomicInteger();

    private ProductRepository repo(int filasPorLote) {
        DataSource contando = contando(TestTransactions.aware(dataSource()));
        PlatformTransactionManager tm = TestTransactions.manager(dataSource());
        SiteRegistry registry = new SiteRegistry(new JdbcSiteSource(contando));
        return new ProductRepository(contando, registry, new RubroResolver(registry), tm,
                new SitioCategoriaRepository(contando), filasPorLote);
    }

    /** Counts the statements that matter without changing what they do. */
    private DataSource contando(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, a) -> {
                    Object r = m.invoke(real, a);
                    if (!m.getName().equals("getConnection")) return r;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("prepareStatement") && ca[0] instanceof String sql) {
                                    if (sql.contains("sp_upsert_run")) upsertRunCalls.incrementAndGet();
                                    if (sql.contains("sp_vincular_sitio_categoria")) vincularCalls.incrementAndGet();
                                }
                                try {
                                    return cm.invoke(r, ca);
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                });
    }

    @Test
    @DisplayName("seven rows in batches of three make three sp_upsert_run calls and three links")
    void sendsOneCallPerBatch() {
        UpsertStats stats = repo(3).upsertProductos(productos("Easy", "https://lote/c", 7, 1000));

        assertThat(stats.nuevos()).isEqualTo(7);
        assertThat(upsertRunCalls).hasValue(3);
        assertThat(vincularCalls).hasValue(3);
    }

    @Test
    @DisplayName("a catalogue that fits one batch is still one call")
    void oneBatchIsOneCall() {
        UpsertStats stats = repo(100).upsertProductos(productos("Easy", "https://uno/c", 7, 1000));

        assertThat(stats.nuevos()).isEqualTo(7);
        assertThat(upsertRunCalls).hasValue(1);
    }

    @Test
    @DisplayName("a batch size smaller than the input gives the same stats and rows as one batch, duplicate URL across batches included")
    void sameResultAsOneBatch() {
        List<Product> conDuplicado = new ArrayList<>(productos("Easy", "https://eq/x", 8, 1000));
        // Same URL in the first and the last batch, different price: the later one must win, as it
        // does inside a single call.
        conDuplicado.add(producto("Easy", "https://eq/x0", 2500));
        List<Product> espejo = new ArrayList<>(productos("Freres", "https://eq2/x", 8, 1000));
        espejo.add(producto("Freres", "https://eq2/x0", 2500));

        UpsertStats lotes = repo(3).upsertProductos(conDuplicado);
        UpsertStats unico = repo(1000).upsertProductos(espejo);

        assertThat(lotes.nuevos()).isEqualTo(8);
        assertThat(lotes).isEqualTo(unico);
        assertThat(filas("Easy", "https://eq/")).isEqualTo(filas("Freres", "https://eq2/"));
        assertThat(filas("Easy", "https://eq/")).hasSize(8).containsEntry("x0", 2500.0);
    }

    @Test
    @DisplayName("an empty catalogue changes nothing and does not fail")
    void emptyCatalogue() {
        assertThat(repo(3).upsertProductos(List.of())).isEqualTo(new UpsertStats(0, 0, 0, 0));
    }

    /** URL is the key across sites, so the mirror uses another prefix; the suffix is what compares. */
    private Map<String, Double> filas(String sitio, String prefijo) {
        Map<String, Double> m = new java.util.TreeMap<>();
        new JdbcTemplate(dataSource()).query(
                "SELECT url, precio FROM productos WHERE sitio = ?",
                rs -> { m.put(rs.getString(1).substring(prefijo.length()), rs.getDouble(2)); }, sitio);
        return m;
    }

    private static List<Product> productos(String sitio, String prefijo, int n, double precio) {
        List<Product> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(producto(sitio, prefijo + i, precio + i));
        return l;
    }

    private static Product producto(String sitio, String url, double precio) {
        return Product.builder()
                .sitio(sitio).nombre("Producto " + url.substring(url.lastIndexOf('/') + 1)).precio(precio)
                .url(url).imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Nike").rubro("indumentaria")
                .gymrat(false).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).sitioCategoriaOrigenId(5).build();
    }
}
