package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each site is persisted by {@code upsertParcial} as it finishes, so the run's final upsert finds
 * nothing left to change: run 40 logged "0 nuevos" after writing 616 new rows.
 */
@Epic("Persistence")
@Feature("Upsert")
@Story("A per-site upsert reports what it changed")
@DisplayName("upsertParcial — devuelve lo que cambió")
class UpsertParcialStatsTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
    }

    @Test
    @DisplayName("counts new rows, price changes and unchanged rows")
    void cuentaNuevosCambiosYSinCambio() {
        UpsertStats primera = db.upsertParcial(List.of(producto("https://t/a", 1000), producto("https://t/b", 1000)));

        assertThat(primera.nuevos()).isEqualTo(2);

        UpsertStats segunda = db.upsertParcial(List.of(
                producto("https://t/a", 2000), producto("https://t/b", 1000), producto("https://t/c", 500)));

        assertThat(segunda.nuevos()).isEqualTo(1);
        assertThat(segunda).isEqualTo(new UpsertStats(1, 1, 1, 0));
    }

    @Test
    @DisplayName("an empty batch changes nothing")
    void unBatchVacioNoCambiaNada() {
        UpsertStats stats = db.upsertParcial(List.of());

        assertThat(stats.nuevos()).isZero();
        assertThat(stats).isEqualTo(new UpsertStats(0, 0, 0, 0));
    }

    private Product producto(String url, double precio) {
        return Product.builder()
                .sitio("Freres")
                .nombre("Producto")
                .precio(precio)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("http://img.example/x.jpg")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of("M"))
                .ml(Product.MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .build();
    }
}
