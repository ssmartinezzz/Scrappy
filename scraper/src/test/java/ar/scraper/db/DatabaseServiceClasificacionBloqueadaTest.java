package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 5 (task 5.1).
 *
 * <p>{@link DatabaseService#cargarClasificacionBloqueada()} is the read-side
 * of the lock (design D3/D4 Data Flow) — a single per-run read
 * ({@code WHERE bloqueado_por IS NOT NULL}) that
 * {@code ResultAggregator.aplicarBloqueos} applies in memory, since the SQL
 * enforcement alone leaves the in-memory {@code lastResult} snapshot
 * (served by {@code GET /api/data}/{@code GET /api/mejores}) stale until a
 * restart.</p>
 */
@Epic("Persistence")
@Feature("Manual classification lock")
@Story("Read-side lock map for the in-memory pipeline fix")
@DisplayName("DatabaseService — cargarClasificacionBloqueada")
class DatabaseServiceClasificacionBloqueadaTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url) {
        return new Product("freres", "Producto", 15000.0, null, url, "http://img.example/x.jpg",
                "Remeras", "hombre", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }

    private void lockProduct(String url, String actor, String categoria, String subCategoria,
                              String marca, String genero, String rubro) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET categoria=?, sub_categoria=?, marca=?, genero=?, rubro=?, "
                             + "bloqueado_por=?, bloqueado_at=?::timestamptz WHERE url=?")) {
            ps.setString(1, categoria);
            ps.setString(2, subCategoria);
            ps.setString(3, marca);
            ps.setString(4, genero);
            ps.setString(5, rubro);
            ps.setString(6, actor);
            ps.setString(7, "2026-07-28 12:00:00");
            ps.setString(8, url);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("returns only locked products, keyed by url, with their locked classification")
    void returnsOnlyLockedProductsKeyedByUrl() throws Exception {
        String urlBloqueado = "https://site.com/bloqueado";
        String urlLibre = "https://site.com/libre";
        db.upsertProductos(List.of(producto(urlBloqueado), producto(urlLibre)));
        lockProduct(urlBloqueado, "local", "Buzo", "urbano", "Adidas", "mujer", "indumentaria");

        Map<String, ClasificacionBloqueada> bloqueos = db.cargarClasificacionBloqueada();

        assertThat(bloqueos).containsOnlyKeys(urlBloqueado);
        ClasificacionBloqueada c = bloqueos.get(urlBloqueado);
        assertThat(c.categoria()).isEqualTo("Buzo");
        assertThat(c.subCategoria()).isEqualTo("urbano");
        assertThat(c.marca()).isEqualTo("Adidas");
        assertThat(c.genero()).isEqualTo("mujer");
        assertThat(c.rubro()).isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("returns an empty map when no product is locked")
    void returnsEmptyMapWhenNoProductIsLocked() throws Exception {
        db.upsertProductos(List.of(producto("https://site.com/sin-bloqueo")));

        Map<String, ClasificacionBloqueada> bloqueos = db.cargarClasificacionBloqueada();

        assertThat(bloqueos).isEmpty();
    }
}
