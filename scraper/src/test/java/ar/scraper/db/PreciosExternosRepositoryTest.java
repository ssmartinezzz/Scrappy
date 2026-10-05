package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/buscar-externo persists its results into the shared precios_externos cache keyed by a
 * caller-supplied url. Any authenticated user could therefore write cache rows for an arbitrary
 * url. Persistence is gated to real, active catalog products.
 */
@Epic("Security")
@Feature("External price cache")
@Story("precios_externos is only written for active catalog products")
@DisplayName("PreciosExternosRepository")
class PreciosExternosRepositoryTest extends PostgresTestBase {

    private static final String REAL = "https://tienda.example/producto-real";
    private static final String UNKNOWN = "https://tienda.example/no-existe";

    private PreciosExternosRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new PreciosExternosRepository(dataSource());
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                    + "VALUES ('" + REAL + "', 'Freres', 'P', 100, true, now(), now())");
        }
    }

    @Test
    @DisplayName("results for a real active product are persisted")
    void persistsForARealProduct() throws Exception {
        repo.guardarPreciosExternos(REAL, "mercadolibre", List.of(unaFila()));

        assertThat(rowsFor(REAL)).isEqualTo(1);
    }

    @Test
    @DisplayName("results for a url that is not a catalog product are dropped")
    void dropsForAnUnknownUrl() throws Exception {
        repo.guardarPreciosExternos(UNKNOWN, "mercadolibre", List.of(unaFila()));

        assertThat(rowsFor(UNKNOWN))
                .as("a low-privilege user must not be able to write cache rows for an arbitrary url")
                .isZero();
    }

    private Map<String, Object> unaFila() {
        return Map.of("titulo", "T", "precio", 123.0, "url", "https://ml/x",
                "condicion", "new", "sitio", "mercadolibre");
    }

    private int rowsFor(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM precios_externos WHERE producto_url = '" + url + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
