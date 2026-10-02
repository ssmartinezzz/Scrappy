package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V42}: a product row needs a visible {@code nombre} and {@code sitio}. The negative cases
 * insert directly and assert the CHECK violation SQLState, because the upsert path swallows SQL
 * errors and would only surface the rejection as "0 nuevos".
 */
@DisplayName("V42 migration — productos.nombre and productos.sitio must not be blank")
class V42ProductoNombreSitioNotBlankTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";

    @BeforeEach
    void seedSitios() throws SQLException {
        // productos.sitio_key is derived from sitio and is an FK to sitio.sitio_key, so a blank
        // sitio can only reach productos if a row with the empty key exists; seed one so the
        // CHECK, not the FK, is what rejects the product.
        ejecutar("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES "
                + "('Tienda V42', 'tiendav42', 'shopify', false, NULL, 'historico'), "
                + "('', '', 'shopify', false, NULL, 'historico') ON CONFLICT DO NOTHING");
    }

    @AfterEach
    void cleanSitios() throws SQLException {
        ejecutar("DELETE FROM productos WHERE url LIKE 'https://v42.test/%'");
        ejecutar("DELETE FROM sitio WHERE nombre IN ('Tienda V42', '')");
    }

    @Test
    @DisplayName("A whitespace-only nombre is rejected by the database")
    void rejectsBlankNombre() {
        assertThatThrownBy(() -> insertar("https://v42.test/nombre-blanco", "Tienda V42", "   "))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        assertThatThrownBy(() -> insertar("https://v42.test/nombre-tab", "Tienda V42", " \t\n"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("An empty sitio is rejected by the database")
    void rejectsBlankSitio() {
        assertThatThrownBy(() -> insertar("https://v42.test/sitio-vacio", "", "Remera Oversize"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        assertThatThrownBy(() -> insertar("https://v42.test/sitio-blanco", "  ", "Remera Oversize"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("A real product name with inner spaces is still accepted")
    void acceptsANormalName() {
        assertThatCode(() -> insertar("https://v42.test/ok", "Tienda V42", "Remera Oversize de Algodon"))
                .doesNotThrowAnyException();
    }

    private void insertar(String url, String sitio, String nombre) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO productos (url, sitio, nombre, precio) VALUES (?, ?, ?, 1000)")) {
            ps.setString(1, url);
            ps.setString(2, sitio);
            ps.setString(3, nombre);
            ps.executeUpdate();
        }
    }

    private void ejecutar(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
