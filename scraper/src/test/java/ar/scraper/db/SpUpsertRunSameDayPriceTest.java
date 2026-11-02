package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code precio_historico} is the only owner of price history, and the ML used to keep the LAST
 * price of a day. {@code ON CONFLICT (url, fecha) DO NOTHING} kept the FIRST, so a product that
 * moved twice in a day was stored at its morning price.
 */
@Epic("Base de datos")
@Feature("sp_upsert_run")
@Story("Varios cambios de precio en el mismo día")
@DisplayName("sp_upsert_run — el último precio del día es el que queda en el historial")
class SpUpsertRunSameDayPriceTest extends PostgresTestBase {

    private static final String URL = "https://site.com/p";

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
    }

    private Product producto(double precio) {
        return Product.builder()
                .sitio("Sitio").nombre("Producto").precio(precio).precioOriginal(null)
                .url(URL).imagenUrl("http://img.example/x.jpg").categoria("Remera")
                .genero("unisex").talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Nike")
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }

    @Test
    @DisplayName("dos cambios el mismo día dejan una sola fila, con el último precio")
    void elUltimoPrecioDelDiaPisaAlPrimero() {
        db.upsertProductos(List.of(producto(100.0)));
        UpsertStats segundo = db.upsertProductos(List.of(producto(80.0)));
        UpsertStats tercero = db.upsertProductos(List.of(producto(70.0)));

        // swallow-guard: el upsert devuelve ceros ante un error SQL
        assertThat(segundo.actualizados()).isEqualTo(1);
        assertThat(tercero.actualizados()).isEqualTo(1);

        Map<String, List<ar.scraper.catalog.HistorialEntry>> h =
                db.getHistorialPrecios(List.of(URL));
        assertThat(h.get(URL)).hasSize(1);
        assertThat(h.get(URL).getFirst().precio()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("un precio sin cambio no toca el historial")
    void sinCambioNoEscribe() {
        db.upsertProductos(List.of(producto(100.0)));
        UpsertStats stats = db.upsertProductos(List.of(producto(100.0)));

        assertThat(stats.sinCambios()).isEqualTo(1);
        assertThat(db.getHistorialPrecios(List.of(URL)).get(URL))
                .extracting(ar.scraper.catalog.HistorialEntry::precio).containsExactly(100.0);
    }
}
