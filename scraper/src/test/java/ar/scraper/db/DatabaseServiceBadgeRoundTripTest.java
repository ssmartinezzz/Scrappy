package ar.scraper.db;

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
 * badges-oportunidades-revamp — comma-delimited multi-badge join/split
 * round-trip (design D1), migrated to {@link PostgresTestBase} per
 * decouple-services-postgres Batch 4 task 4.6.
 *
 * <p>The original {@code DatabaseServiceBadgeMigrationTest} also covered
 * {@code backfillBadgeKeys()} — the idempotent startup migration from the 7
 * old Spanish snake_case badge keys to the new English keys. That method was
 * REMOVED in Batch 1 of {@code decouple-services-postgres}: the Postgres
 * baseline (Flyway {@code V1__baseline.sql}) ships with current badge keys
 * from day one, there is no legacy SQLite data carried over by this infra
 * swap, so those 4 tests have no Postgres-relevant analog and were retired
 * rather than migrated (see {@code sdd/decouple-services-postgres/apply-progress}).
 * Only the badge-agnostic multi-value join/split round-trip — genuine
 * business logic, unaffected by the Postgres swap — is preserved here.</p>
 */
@Epic("Persistence")
@Feature("Badge taxonomy")
@Story("Multi-value ml_badge join/split round-trip")
@DisplayName("DatabaseService — multi-value ml_badge join/split round-trip")
class DatabaseServiceBadgeRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product productoConBadges(String url, List<String> badges) {
        Product.MlScore ml = new Product.MlScore(
                80, badges, true, "estable", 20, 0.5, "standard");
        return new Product("Sitio", "Producto multi-badge", 15000.0, null, url,
                "http://img.example/x.jpg", "Remeras", "unisex", List.of(), ml, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    void upsertAndCargarProductosRoundTripsMultiValueBadges() {
        Product p = productoConBadges("https://site.com/multi", List.of("verified_deal", "trending"));

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).ml().badges()).containsExactly("verified_deal", "trending");
        assertThat(cargados.get(0).ml().badge()).isEqualTo("verified_deal");
    }

    @Test
    void upsertAndCargarProductosRoundTripsSingleBadge() {
        Product p = productoConBadges("https://site.com/single-badge", List.of("all_time_low"));

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).ml().badges()).containsExactly("all_time_low");
    }

    @Test
    void upsertAndCargarProductosRoundTripsNoBadges() {
        Product p = productoConBadges("https://site.com/no-badge", List.of());

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).ml().badges()).isEmpty();
        assertThat(cargados.get(0).ml().badge()).isEmpty();
    }
}
