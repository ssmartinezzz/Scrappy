package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * normalize-db-schema-fks-1nf, slice A.1 (V4).
 *
 * <p>Covers spec "db-referential-integrity" — Catalog-wipe contract:
 * {@code DELETE /api/db/productos} MUST return 409 with the blocking
 * favourite count and delete nothing when a {@code favoritos} row still
 * references a live product (no {@code ?force=} override), and MUST
 * otherwise succeed and cascade. Exercises the real
 * {@link DbAdminEndpoints}/{@link DatabaseService} write path against a real
 * Postgres via {@link PostgresTestBase} — only {@link ScraperService} and
 * {@link ResultAggregator} are mocked (same seam as
 * {@code ApiControllerStatusScrapeTest}), so the FK RESTRICT surfaces exactly
 * as it would in production.</p>
 */
@Epic("REST API")
@Feature("Sitios / Config / Wiring")
@Story("DELETE /api/db/productos — protected by live favoritos")
@DisplayName("DbAdminEndpoints.limpiarProductos — 409 when favoritos block the wipe")
class CatalogWipeProtectedFavoritosTest extends PostgresTestBase {

    private DatabaseService db;
    private ScraperService service;
    private ResultAggregator aggregator;
    private DbAdminEndpoints endpoints;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        service = mock(ScraperService.class);
        aggregator = mock(ResultAggregator.class);
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        endpoints = new DbAdminEndpoints(service, db, aggregator);
    }

    private Product producto(String url) {
        return new Product("Sitio", "Producto", 1000.0, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    private int contar(String tabla) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + tabla);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("Wipe blocked by favourites: 409 with count, nothing deleted")
    void wipeBlockedByFavoritesReturns409AndDeletesNothing() throws Exception {
        String url = "https://site.com/wipe-blocked";
        db.upsertProductos(List.of(producto(url)));
        db.guardarFavorito(yo(), url, "Sitio", "Producto");

        var resp = endpoints.limpiarProductos();

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).contains("1");

        assertThat(contar("productos")).isEqualTo(1);
        assertThat(contar("precio_historico")).isEqualTo(1);
        assertThat(contar("favoritos")).isEqualTo(1);

        verify(service, never()).clearLastResult();
        verifyNoInteractions(aggregator);
    }

    @Test
    @DisplayName("Wipe with no favourites: succeeds and cascades")
    void wipeSucceedsAndCascadesWhenNoFavoritesExist() throws Exception {
        String url = "https://site.com/wipe-clean";
        db.upsertProductos(List.of(producto(url)));

        var resp = endpoints.limpiarProductos();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(contar("productos")).isZero();
        assertThat(contar("precio_historico")).isZero();
    }

    /**
     * The owner every personal read and write is scoped by since slice 8.
     *
     * <p>A method rather than a field: {@code PostgresTestBase} truncates between
     * tests, so a cached id would point at a row that no longer exists. Seeding is
     * idempotent, so calling it repeatedly costs three cheap queries and is always
     * correct.</p>
     */
    private UUID yo() {
        return UsuarioDePrueba.yo(dataSource());
    }
}
