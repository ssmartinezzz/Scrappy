package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDeCorrida;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Promos")
@DisplayName("ProductRepository — promos commit with the run's products or not at all")
class ProductRepositoryPromosTest extends PostgresTestBase {

    private static final String A = "RepoPromoA";
    private static final String B = "RepoPromoB";

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
    }

    private static PromoFinanciacion cuotas(String medio, int n) {
        return new PromoFinanciacion(medio, TipoMedio.TARJETA, TipoPromo.CUOTAS_SIN_INTERES, n, null,
                Alcance.PRODUCTO, null, null, "texto");
    }

    private static PromoFinanciacion descuentoDelSitio(String medio, String pct) {
        return new PromoFinanciacion(medio, TipoMedio.MEDIO, TipoPromo.DESCUENTO, null, new BigDecimal(pct),
                Alcance.SITIO, null, null, "texto");
    }

    private static Product producto(String sitio, String url, PromoFinanciacion... promos) {
        return Product.builder().sitio(sitio).nombre("SSD " + url).precio(100.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Almacenamiento").genero("unisex")
                .talles(List.of()).rubro("tecnologia").promos(List.of(promos)).build();
    }

    private UpsertStats correr(List<Product> productos, PromosDeCorrida promos) {
        return db.upsertProductos(productos, null, Set.of(), promos);
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long activas(String sitio) throws SQLException {
        return count("SELECT count(*) FROM promo_financiacion p JOIN sitio s ON s.sitio_key = p.sitio_key"
                + " WHERE s.nombre = '" + sitio + "' AND p.activo");
    }

    @Test
    @DisplayName("products with promos persist promo rows, links and site promos in one call")
    void persistsPromosAndLinks() throws Exception {
        PromoFinanciacion tresCuotas = cuotas("Visa", 3);
        UpsertStats stats = correr(
                List.of(producto(A, "https://a.example/1", tresCuotas), producto(A, "https://a.example/2", tresCuotas)),
                new PromosDeCorrida(Map.of(A, List.of(descuentoDelSitio("Transferencia", "10"))), Set.of(A)));

        assertThat(stats.nuevos()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM promo_financiacion")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE alcance = 'sitio' AND pct = 10")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same run twice is idempotent")
    void idempotent() throws Exception {
        List<Product> productos = List.of(producto(A, "https://a.example/1", cuotas("Visa", 3)));
        PromosDeCorrida promos = new PromosDeCorrida(Map.of(), Set.of(A));

        assertThat(correr(productos, promos).nuevos()).isEqualTo(1);
        correr(productos, promos);

        assertThat(count("SELECT count(*) FROM promo_financiacion")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto")).isEqualTo(1);
        assertThat(activas(A)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing promo call rolls the products back with it")
    void promoFailureRollsBackProducts() throws Exception {
        UpsertStats stats = correr(
                List.of(producto(A, "https://a.example/roto", cuotas("Visa\u0000", 3))),
                new PromosDeCorrida(Map.of(), Set.of(A)));

        assertThat(stats.nuevos()).isZero();
        assertThat(count("SELECT count(*) FROM productos WHERE url = 'https://a.example/roto'")).isZero();
        assertThat(count("SELECT count(*) FROM promo_financiacion")).isZero();
    }

    @Test
    @DisplayName("a site outside the promo-read set keeps its promos; one inside loses the unseen ones")
    void sweepIsScopedToTheSitesTheRunRead() throws Exception {
        PromosDeCorrida ambos = new PromosDeCorrida(Map.of(), Set.of(A, B));
        assertThat(correr(List.of(producto(A, "https://a.example/1", cuotas("Visa", 3)),
                producto(B, "https://b.example/1", cuotas("Visa", 3))), ambos).nuevos()).isEqualTo(2);
        assertThat(activas(A)).isEqualTo(1);
        assertThat(activas(B)).isEqualTo(1);

        correr(List.of(producto(A, "https://a.example/1"), producto(B, "https://b.example/1")),
                new PromosDeCorrida(Map.of(), Set.of(A)));

        assertThat(activas(A)).as("read and now empty").isZero();
        assertThat(activas(B)).as("not read this run").isEqualTo(1);
    }

    @Test
    @DisplayName("a run with no promo signal at all deactivates nothing")
    void noSignalNoSweep() throws Exception {
        correr(List.of(producto(A, "https://a.example/1", cuotas("Visa", 3))),
                new PromosDeCorrida(Map.of(), Set.of(A)));

        UpsertStats stats = db.upsertProductos(List.of(producto(A, "https://a.example/1")));

        assertThat(stats.sinCambios()).isEqualTo(1);
        assertThat(activas(A)).isEqualTo(1);
    }

    @Test
    @DisplayName("the card network of a promo survives the repository's JSON to the stored row")
    void redTravelsToTheRow() throws Exception {
        PromoFinanciacion macroVisa = new PromoFinanciacion("Macro", TipoMedio.BANCO, "Visa",
                TipoPromo.CUOTAS_SIN_INTERES, 12, null, Alcance.SITIO, null, null, "texto");
        PromoFinanciacion macroSola = new PromoFinanciacion("Macro", TipoMedio.BANCO, null,
                TipoPromo.CUOTAS_SIN_INTERES, 12, null, Alcance.SITIO, null, null, "texto");

        correr(List.of(producto(A, "https://a.example/1")),
                new PromosDeCorrida(Map.of(A, List.of(macroVisa, macroSola)), Set.of(A)));

        assertThat(count("SELECT count(*) FROM promo_financiacion p JOIN medio_pago r ON r.id = p.red_medio_pago_id"
                + " WHERE r.nombre = 'Visa'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE red_medio_pago_id IS NULL")).isEqualTo(1);
    }
}
