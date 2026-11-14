package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stock state rides the same {@code sp_upsert_run} transaction as the price. {@code en_stock} NULL
 * means the platform gave no signal, so a NULL incoming value must not erase what an earlier run
 * learned: staleness is judged at read time from {@code stock_chequeado_at}.
 *
 * <p>Every test asserts the upsert counters first: {@code ProductRepository} swallows SQL errors
 * and returns zeros, so a missing write looks like "0 nuevos", not like an exception.</p>
 */
@Epic("Base de datos")
@Feature("sp_upsert_run")
@DisplayName("sp_upsert_run — estado de stock por producto")
class SpUpsertRunStockTest extends PostgresTestBase {

    private static final String URL = "https://site.com/ssd";

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
    }

    private Product producto(Boolean enStock) {
        return Product.builder()
                .sitio("Sitio").nombre("SSD 1TB").precio(100.0).url(URL)
                .imagenUrl("http://img.example/x.jpg").categoria("Almacenamiento").genero("unisex")
                .talles(List.of()).rubro("tecnologia").enStock(enStock)
                .build();
    }

    private record Stock(Boolean enStock, OffsetDateTime visto, OffsetDateTime chequeado, OffsetDateTime touched) {}

    private Stock leer() throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT en_stock, visto_en_stock_at, stock_chequeado_at, touched_at"
                             + " FROM productos WHERE url = ?")) {
            ps.setString(1, URL);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("la fila existe").isTrue();
                return new Stock(rs.getObject(1, Boolean.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class));
            }
        }
    }

    /** Lleva todo el estado temporal al pasado, para que la corrida siguiente sea distinguible. */
    private void envejecer() throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET touched_at = touched_at - interval '2 days',"
                             + " visto_en_stock_at = visto_en_stock_at - interval '2 days',"
                             + " stock_chequeado_at = stock_chequeado_at - interval '2 days' WHERE url = ?")) {
            ps.setString(1, URL);
            ps.executeUpdate();
        }
    }

    private UpsertStats subir(Boolean enStock) {
        return db.upsertProductos(List.of(producto(enStock)));
    }

    @Test
    @DisplayName("insert con stock true -> chequeado y visto con el timestamp de la corrida")
    void insertInStock() throws Exception {
        assertThat(subir(true).nuevos()).isEqualTo(1);
        Stock s = leer();
        assertThat(s.enStock()).isTrue();
        assertThat(s.chequeado()).isEqualTo(s.touched());
        assertThat(s.visto()).isEqualTo(s.touched());
    }

    @Test
    @DisplayName("insert con stock false -> chequeado, nunca visto en stock")
    void insertOutOfStock() throws Exception {
        assertThat(subir(false).nuevos()).isEqualTo(1);
        Stock s = leer();
        assertThat(s.enStock()).isFalse();
        assertThat(s.chequeado()).isEqualTo(s.touched());
        assertThat(s.visto()).isNull();
    }

    @Test
    @DisplayName("insert sin señal -> todo NULL, no 'en stock'")
    void insertUnknown() throws Exception {
        assertThat(subir(null).nuevos()).isEqualTo(1);
        Stock s = leer();
        assertThat(s.enStock()).isNull();
        assertThat(s.chequeado()).isNull();
        assertThat(s.visto()).isNull();
    }

    @Test
    @DisplayName("true -> false conserva visto_en_stock_at y avanza stock_chequeado_at")
    void trueToFalseKeepsLastSeen() throws Exception {
        assertThat(subir(true).nuevos()).isEqualTo(1);
        envejecer();
        Stock antes = leer();

        UpsertStats stats = subir(false);
        assertThat(stats.nuevos() + stats.actualizados() + stats.sinCambios()).isEqualTo(1);
        Stock s = leer();
        assertThat(s.enStock()).isFalse();
        assertThat(s.visto()).isEqualTo(antes.visto());
        assertThat(s.chequeado()).isEqualTo(s.touched()).isAfter(antes.chequeado());
    }

    @Test
    @DisplayName("false -> true actualiza visto_en_stock_at")
    void falseToTrueUpdatesLastSeen() throws Exception {
        assertThat(subir(false).nuevos()).isEqualTo(1);
        envejecer();

        UpsertStats stats = subir(true);
        assertThat(stats.nuevos() + stats.actualizados() + stats.sinCambios()).isEqualTo(1);
        Stock s = leer();
        assertThat(s.enStock()).isTrue();
        assertThat(s.visto()).isEqualTo(s.touched());
        assertThat(s.chequeado()).isEqualTo(s.touched());
    }

    @Test
    @DisplayName("sin señal después de un estado conocido -> conserva todo, incluido stock_chequeado_at")
    void nullAfterKnownKeepsPriorState() throws Exception {
        assertThat(subir(true).nuevos()).isEqualTo(1);
        envejecer();
        Stock antes = leer();

        UpsertStats stats = subir(null);
        assertThat(stats.nuevos() + stats.actualizados() + stats.sinCambios()).isEqualTo(1);
        Stock s = leer();
        assertThat(s.enStock()).isTrue();
        assertThat(s.visto()).isEqualTo(antes.visto());
        assertThat(s.chequeado()).isEqualTo(antes.chequeado());
        assertThat(s.touched()).isAfter(antes.touched());
    }

    @Test
    @DisplayName("round-trip por el repositorio: RowMapper devuelve enStock y los dos timestamps")
    void repositoryRoundTrip() {
        assertThat(subir(true).nuevos()).isEqualTo(1);
        Product leido = db.obtenerProducto(URL).orElseThrow();
        assertThat(leido.enStock()).isTrue();
        assertThat(leido.vistoEnStockAt()).isNotNull();
        assertThat(leido.stockChequeadoAt()).isNotNull();

        db.upsertProductos(List.of(producto(null).toBuilder().url("https://site.com/otro").build()));
        Product desconocido = db.obtenerProducto("https://site.com/otro").orElseThrow();
        assertThat(desconocido.enStock()).isNull();
        assertThat(desconocido.vistoEnStockAt()).isNull();
        assertThat(desconocido.stockChequeadoAt()).isNull();
    }

    @Test
    @DisplayName("el CHECK impide un estado conocido sin stock_chequeado_at")
    void checkRejectsKnownWithoutCheckedAt() {
        assertThat(subir(null).nuevos()).isEqualTo(1);
        assertThatThrownBy(() -> {
            try (Connection c = dataSource().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE productos SET en_stock = true WHERE url = ?")) {
                ps.setString(1, URL);
                ps.executeUpdate();
            }
        }).hasMessageContaining("ck_productos_stock_chequeado");
    }
}
