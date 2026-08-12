package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * normalize-db-schema-fks-1nf, slice A.1 (V4).
 *
 * <p>Covers spec "db-referential-integrity" — FK topology on
 * {@code productos(url)}: {@code precio_historico}/{@code precios_externos}
 * CASCADE, {@code favoritos} RESTRICT, {@code agent_reclassify_audit}
 * deliberately unbound (design D8). Exercises real Postgres FK enforcement
 * via {@link PostgresTestBase}, no mocks.</p>
 */
@Epic("Persistence")
@Feature("Referential integrity")
@Story("FK topology on productos(url) — V4")
@DisplayName("V4 migration — FK topology on productos(url)")
class ForeignKeyTopologyTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url) {
        return new Product("Sitio", "Producto", 1000.0, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    private void rawDeleteProducto(String url) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM productos WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        }
    }

    private int contar(String tabla, String columna, String url) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + tabla + " WHERE " + columna + "=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertarAuditoria(String url) throws SQLException {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO agent_reclassify_audit (url, applied_at) VALUES ('"
                    + url.replace("'", "''") + "', '2026-08-11 12:00:00')");
        }
    }

    @Test
    @DisplayName("Deleting a product cascades precio_historico and precios_externos")
    void deletingProductCascadesDerivedData() throws Exception {
        String url = "https://site.com/fk-cascade";
        db.upsertProductos(List.of(producto(url)));
        assertThat(contar("precio_historico", "url", url)).isEqualTo(1);

        db.guardarPreciosExternos(url, "MercadoLibre", List.of(
                Map.of("titulo", "Externo", "precio", 999.0, "url", "http://ext.example", "condicion", "new")));
        assertThat(contar("precios_externos", "producto_url", url)).isEqualTo(1);

        rawDeleteProducto(url);

        assertThat(contar("precio_historico", "url", url)).isZero();
        assertThat(contar("precios_externos", "producto_url", url)).isZero();
    }

    @Test
    @DisplayName("Deleting a favourited product is rejected by RESTRICT")
    void deletingFavoritedProductIsRejected() throws Exception {
        String url = "https://site.com/fk-restrict";
        db.upsertProductos(List.of(producto(url)));
        db.guardarFavorito(url, "Sitio", "Producto");

        assertThatThrownBy(() -> rawDeleteProducto(url)).isInstanceOf(SQLException.class);

        assertThat(contar("productos", "url", url)).isEqualTo(1);
        assertThat(contar("favoritos", "url", url)).isEqualTo(1);
    }

    @Test
    @DisplayName("Audit rows are never FK-bound — they survive product deletion")
    void auditRowsSurviveProductDeletion() throws Exception {
        String url = "https://site.com/fk-audit-untouched";
        db.upsertProductos(List.of(producto(url)));
        insertarAuditoria(url);
        assertThat(contar("agent_reclassify_audit", "url", url)).isEqualTo(1);

        rawDeleteProducto(url);

        assertThat(contar("agent_reclassify_audit", "url", url)).isEqualTo(1);
    }
}
