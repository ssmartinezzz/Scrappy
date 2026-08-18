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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code precio_historico} records price CHANGES, not sightings — that is the
 * contract stated in CLAUDE.md and enforced by
 * {@code DatabaseServiceTest.unchangedPriceOnlyTouchesTimestamp}: an unchanged
 * price only bumps {@code touched_at}.
 *
 * <p>A product that was soft-deleted and later comes back escaped that rule.
 * {@code sp_upsert_run} read the previous price with
 * {@code WHERE url = ? AND activo}, so an inactive row matched nothing and
 * {@code v_prev_precio} came back NULL — indistinguishable from a URL never
 * seen before. The run then took the "brand new product" branch: it counted the
 * product as {@code nuevos} and wrote a {@code precio_historico} row even when
 * the price had not moved a cent.</p>
 *
 * <p>Same-day reruns hid this: the {@code UNIQUE(url, fecha)} constraint plus
 * {@code ON CONFLICT DO NOTHING} silently swallows the second insert. The bug
 * only materialises when the product returns on a LATER date, which is exactly
 * the real-world case — a product goes out of stock and reappears days later.
 * These tests therefore backdate the first history row before reactivating.</p>
 *
 * <p>Every assertion checks the counters BEFORE any row count:
 * {@code ProductRepository} swallows SQL errors and returns
 * {@code UpsertStats(0,0,0,0)}, so a zero there is the signature of a failed
 * write, not of a passing expectation.</p>
 */
@Epic("Base de datos")
@Feature("sp_upsert_run")
@Story("Reactivación de un producto soft-deleted")
@DisplayName("sp_upsert_run — un producto que vuelve no inventa un punto de historial")
class SpUpsertRunReactivationHistoryTest extends PostgresTestBase {

    private static final String VUELVE = "https://site.com/vuelve";
    private static final String OTRO   = "https://site.com/otro";

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, double precio) {
        return new Product(
                "Sitio", "Producto", precio, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    /**
     * Mueve el historial existente de una URL al pasado. La fecha del upsert es
     * {@code LocalDate.now()} adentro de ProductRepository y no es inyectable, así
     * que backdatear la fila previa es la única forma de que la reactivación caiga
     * en una fecha distinta y el ON CONFLICT (url, fecha) no tape el insert.
     */
    private void backdateHistorial(String url, int dias) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE precio_historico SET fecha = fecha - CAST(? AS INTEGER) WHERE url = ?")) {
            ps.setInt(1, dias);
            ps.setString(2, url);
            ps.executeUpdate();
        }
    }

    /** Saca a VUELVE del run (queda soft-deleted) y lo vuelve a traer al precio dado. */
    private DatabaseService.UpsertStats desaparecerYVolver(double precioAlVolver) throws Exception {
        db.upsertProductos(List.of(producto(OTRO, 500.0)));
        assertThat(db.esProductoActivo(VUELVE)).isFalse();

        backdateHistorial(VUELVE, 3);
        return db.upsertProductos(List.of(producto(VUELVE, precioAlVolver), producto(OTRO, 500.0)));
    }

    @Test
    @DisplayName("vuelve al MISMO precio -> sin fila nueva de historial")
    void aProductThatComesBackAtTheSamePriceRecordsNoNewHistoryPoint() throws Exception {
        db.upsertProductos(List.of(producto(VUELVE, 750.0), producto(OTRO, 500.0)));
        assertThat(db.cargarHistorial(VUELVE)).hasSize(1);

        DatabaseService.UpsertStats stats = desaparecerYVolver(750.0);

        // Swallow-guard, no la aserción del caso: ProductRepository devuelve
        // UpsertStats(0,0,0,0) ante un error SQL, así que un total de 2 filas
        // procesadas prueba que el write ocurrió antes de mirar el historial.
        assertThat(stats.nuevos() + stats.actualizados() + stats.sinCambios()).isEqualTo(2);
        assertThat(db.esProductoActivo(VUELVE)).isTrue();

        assertThat(db.cargarHistorial(VUELVE)).hasSize(1);
    }

    @Test
    @DisplayName("vuelve al mismo precio -> NO se cuenta como nuevo, porque no lo es")
    void aReactivatedProductIsNotCountedAsNew() throws Exception {
        db.upsertProductos(List.of(producto(VUELVE, 750.0), producto(OTRO, 500.0)));

        DatabaseService.UpsertStats stats = desaparecerYVolver(750.0);

        assertThat(stats.nuevos()).isZero();
        assertThat(stats.sinCambios()).isEqualTo(2);
    }

    @Test
    @DisplayName("vuelve a OTRO precio -> sí registra el cambio, como cualquier update")
    void aProductThatComesBackAtADifferentPriceStillRecordsTheChange() throws Exception {
        db.upsertProductos(List.of(producto(VUELVE, 750.0), producto(OTRO, 500.0)));

        DatabaseService.UpsertStats stats = desaparecerYVolver(900.0);

        assertThat(stats.actualizados()).isEqualTo(1);
        assertThat(db.cargarHistorial(VUELVE)).hasSize(2);
        assertThat(db.obtenerProducto(VUELVE)).get()
                .extracting(Product::precio).isEqualTo(900.0);
    }

    @Test
    @DisplayName("una URL genuinamente nueva sigue contando como nueva y abriendo historial")
    void aGenuinelyNewUrlStillCountsAsNew() {
        DatabaseService.UpsertStats stats =
                db.upsertProductos(List.of(producto(VUELVE, 750.0)));

        assertThat(stats.nuevos()).isEqualTo(1);
        assertThat(db.cargarHistorial(VUELVE)).hasSize(1);
    }
}
