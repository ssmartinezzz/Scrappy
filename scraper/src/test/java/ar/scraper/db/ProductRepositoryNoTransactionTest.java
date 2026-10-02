package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * With the database down a transaction cannot even be opened. The write path keeps answering
 * with its sentinel instead of throwing, so the caller is not aborted (user decision, 2026-09-30).
 */
@Epic("Persistence")
@Feature("Transactions")
@Story("The upsert sentinel survives a database that is down")
@DisplayName("ProductRepository — no transaction can open")
class ProductRepositoryNoTransactionTest {

    private static DataSource unreachable() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw new SQLException("database down");
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ProductRepository repository() {
        DataSource raw = unreachable();
        PlatformTransactionManager tm = TestTransactions.manager(raw);
        SiteRegistry siteRegistry = new SiteRegistry(new JdbcSiteSource(raw));
        return new ProductRepository(raw, siteRegistry, new RubroResolver(siteRegistry), tm);
    }

    private static Product producto() {
        return Product.builder()
                .sitio("Freres")
                .nombre("Producto")
                .precio(1000.0)
                .precioOriginal(null)
                .url("https://t/a")
                .imagenUrl("http://img.example/x.jpg")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of("M"))
                .build();
    }

    @Test
    @DisplayName("upsertProductos returns 0 nuevos in both overloads")
    void upsertProductosReturnsTheSentinel() {
        ProductRepository repo = repository();

        assertThat(repo.upsertProductos(List.of(producto()))).isEqualTo(new UpsertStats(0, 0, 0, 0));
        assertThat(repo.upsertProductos(List.of(producto()), null)).isEqualTo(new UpsertStats(0, 0, 0, 0));
    }

    @Test
    @DisplayName("upsertParcial does not throw")
    void upsertParcialDoesNotThrow() {
        ProductRepository repo = repository();

        assertThatCode(() -> repo.upsertParcial(List.of(producto()))).doesNotThrowAnyException();
    }
}
