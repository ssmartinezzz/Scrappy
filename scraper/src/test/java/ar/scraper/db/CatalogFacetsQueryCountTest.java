package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `catalog-facets-perf`, T1 — the RED that catches a regression back to eight
 * separate scans.
 *
 * <p>Counts every statement {@link CatalogQueryRepository#facetas()} prepares
 * directly against {@code productos} (excluding the two child-table JOINs,
 * which stay separate by design). Before this change it was 8 — one per
 * facet. One {@code GROUPING SETS} pass makes it 1.</p>
 */
@Epic("Persistence")
@Feature("Catalog query")
@Story("Facets scan productos once, not once per facet")
@DisplayName("/api/facets — un solo scan de productos, no ocho")
class CatalogFacetsQueryCountTest extends PostgresTestBase {

    private AtomicInteger scansSobreProductos;
    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        scansSobreProductos = new AtomicInteger();
        DataSource counting = countingDataSource(dataSource(), scansSobreProductos);
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(counting, db.siteRegistry());
        db.upsertProductos(List.of(
                producto("https://s.com/1", "Remera", "Freres", "Remera", "hombre", "Nike", "Basicas"),
                producto("https://s.com/2", "Zapatilla", "VCP", "Zapatilla", "mujer", "Puma", "Running")
        ));
    }

    @Test
    @DisplayName("facetas() prepara UNA sola sentencia directa contra productos")
    void unaSolaSentenciaContraProductos() {
        repo.facetas();

        assertThat(scansSobreProductos.get())
                .as("sentencias directas contra productos dentro de facetas() "
                        + "(GROUPING SETS reemplaza los 8 contar() por 1)")
                .isEqualTo(1);
    }

    // ─── counting DataSource: dynamic proxy so we don't hand-implement 40 JDBC methods ──

    private static DataSource countingDataSource(DataSource delegate, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
                        Connection real = delegate.getConnection();
                        return countingConnection(real, counter);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static Connection countingConnection(Connection delegate, AtomicInteger counter) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                // Direct scans of productos, not the child-table JOINs (talle/badge) —
                // those stay as their own two queries by design.
                if (sql.contains("FROM productos") && !sql.contains("JOIN productos")) {
                    counter.incrementAndGet();
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private Product producto(String url, String nombre, String sitio, String categoria,
                             String genero, String marca, String subCategoria) {
        Product.MlScore ml = new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard");
        return new Product(sitio, nombre, 1000, null, url, "http://img.example/x.jpg",
                categoria, genero, List.of("M"), ml, marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, subCategoria,
                new Product.VisualAttrs("regular", "", "", ""));
    }
}
