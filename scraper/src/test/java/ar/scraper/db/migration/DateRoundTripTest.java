package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.HistorialEntry;
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
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice A.2 (V5), task 2.3.
 *
 * <p>Covers spec "db-column-domains" / design D6 — {@code precio_historico.fecha}
 * and {@code precios_externos.fecha} retype to {@code DATE}; {@code productos.
 * touched_at}/{@code created_at} retype to {@code TIMESTAMPTZ} (they ride in
 * this slice only because {@code sp_upsert_run}/{@code sp_soft_delete_ausentes}
 * write them). Exercises the real write path ({@code DatabaseService.
 * upsertProductos()}) and read path ({@code getHistorialPrecios}) against a
 * real Postgres — CODE-3, measured, not assumed.</p>
 */
@Epic("Persistence")
@Feature("Column domains")
@Story("V5 — DATE/TIMESTAMPTZ round-trip")
@DisplayName("V5 migration — date/timestamp round-trip through the real write path")
class DateRoundTripTest extends PostgresTestBase {

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

    @Test
    @DisplayName("precio_historico.fecha round-trips as yyyy-MM-dd through getHistorialPrecios")
    void precioHistoricoFechaRoundTrips() {
        String url = "https://site.com/date-roundtrip";
        db.upsertProductos(List.of(producto(url)));

        List<HistorialEntry> historial = db.getHistorialPrecios(url);

        assertThat(historial).hasSize(1);
        String fecha = historial.get(0).fecha();
        assertThat(fecha).matches("^\\d{4}-\\d{2}-\\d{2}$");
    }

    @Test
    @DisplayName("precios_externos.fecha round-trips through the repository write/read path")
    void preciosExternosFechaRoundTrips() {
        String url = "https://site.com/externos-date-roundtrip";
        db.upsertProductos(List.of(producto(url)));
        db.guardarPreciosExternos(url, "MercadoLibre", List.of(
                java.util.Map.of("titulo", "Externo", "precio", 999.0,
                        "url", "http://ext.example", "condicion", "new")));

        List<java.util.Map<String, Object>> externos = db.cargarPreciosExternos(url);

        assertThat(externos).hasSize(1);
        Object fecha = externos.get(0).get("fecha");
        assertThat(fecha).asString().matches("^\\d{4}-\\d{2}-\\d{2}$");
    }

    @Test
    @DisplayName("productos.touched_at/created_at accept sp_upsert_run's 'now' string as a real timestamptz")
    void touchedAtCreatedAtRoundTripAsTimestamptz() throws Exception {
        String url = "https://site.com/touched-created-roundtrip";
        db.upsertProductos(List.of(producto(url)));

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT touched_at, created_at FROM productos WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row exists").isTrue();
                Timestamp touchedAt = rs.getTimestamp("touched_at");
                Timestamp createdAt = rs.getTimestamp("created_at");
                assertThat(touchedAt).as("touched_at is a real timestamp, not null/unparseable").isNotNull();
                assertThat(createdAt).as("created_at is a real timestamp, not null/unparseable").isNotNull();
            }
        }
    }

    @Test
    @DisplayName("re-upserting a changed price still records one precio_historico row per day, dates intact")
    void reUpsertKeepsFechaOnEachHistoryRow() {
        String url = "https://site.com/date-roundtrip-reupsert";
        db.upsertProductos(List.of(producto(url)));
        db.upsertProductos(List.of(new Product("Sitio", "Producto", 2000.0, null, url,
                "http://img.example/x.jpg", "Remera", "unisex", List.of("M"), Product.MlScore.EMPTY,
                "Nike", "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1)));

        List<HistorialEntry> historial = db.getHistorialPrecios(url);

        // Same calendar day -> ON CONFLICT (url, fecha) DO NOTHING keeps exactly 1 row,
        // and that row's fecha must still be a valid DATE string.
        assertThat(historial).hasSize(1);
        assertThat(historial.get(0).fecha()).matches("^\\d{4}-\\d{2}-\\d{2}$");
    }
}
