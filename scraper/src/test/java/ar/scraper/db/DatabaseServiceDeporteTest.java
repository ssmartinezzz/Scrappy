package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("sp_upsert_run — productos.deporte")
class DatabaseServiceDeporteTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
    }

    private static Product producto(String url, String nombre, String deporte) {
        return Product.builder()
                .sitio("Sitio").nombre(nombre).precio(15000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("hombre")
                .talles(List.of("M")).deporte(deporte)
                .build();
    }

    private String columna(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT deporte FROM productos WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row %s", url).isTrue();
                return rs.getString(1);
            }
        }
    }

    private void bloquear(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE productos SET bloqueado_por = 'local', bloqueado_at = now() WHERE url = ?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("The sport is persisted and read back")
    void roundTrip() throws Exception {
        UpsertStats stats = db.upsertProductos(List.of(producto("https://t/boca", "Camiseta Boca Juniors", "futbol")));

        assertThat(stats.nuevos()).isEqualTo(1);
        assertThat(columna("https://t/boca")).isEqualTo("futbol");
        assertThat(db.cargarProductos().get(0).deporte()).isEqualTo("futbol");
    }

    @Test
    @DisplayName("No sport is NULL in the table, never an empty string, and reads back as empty")
    void emptyIsNull() throws Exception {
        UpsertStats stats = db.upsertProductos(List.of(producto("https://t/lisa", "Remera lisa", "")));

        assertThat(stats.nuevos()).isEqualTo(1);
        assertThat(columna("https://t/lisa")).isNull();
        assertThat(db.cargarProductos().get(0).deporte()).isEmpty();
    }

    @Test
    @DisplayName("A re-scrape updates the sport of an unlocked row")
    void reScrapeUpdates() throws Exception {
        db.upsertProductos(List.of(producto("https://t/x", "Camiseta", "")));
        db.upsertProductos(List.of(producto("https://t/x", "Camiseta", "rugby")));

        assertThat(columna("https://t/x")).isEqualTo("rugby");
    }

    @Test
    @DisplayName("A row locked by hand keeps its sport")
    void lockedRowKeepsItsSport() throws Exception {
        db.upsertProductos(List.of(producto("https://t/l", "Camiseta", "futbol")));
        bloquear("https://t/l");

        db.upsertProductos(List.of(producto("https://t/l", "Camiseta", "")));

        assertThat(columna("https://t/l")).isEqualTo("futbol");
    }

    @Test
    @DisplayName("A code outside the deporte table is rejected by the FK, and the upsert reports it as 0 nuevos")
    void unknownCodeIsRejected() throws Exception {
        UpsertStats stats = db.upsertProductos(List.of(producto("https://t/mal", "Camiseta", "curling")));

        assertThat(stats.nuevos()).isZero();
        assertThat(db.cargarProductos()).isEmpty();
    }
}
