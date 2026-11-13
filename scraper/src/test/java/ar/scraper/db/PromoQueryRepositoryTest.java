package ar.scraper.db;

import ar.scraper.catalog.ProductoRef;
import ar.scraper.catalog.PromoPagina;
import ar.scraper.catalog.PromoQueryPort;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoVigente;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromoQueryRepository — current promos, filters and per-product resolution")
class PromoQueryRepositoryTest extends PostgresTestBase {

    private static final String A = "PromoReadA";
    private static final String B = "PromoReadB";
    private static final String A1 = "https://a.example/q1";
    private static final String A2 = "https://a.example/q2";
    private static final String B1 = "https://b.example/q1";

    private PromoQueryPort query;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        db.upsertProductos(List.of(producto(A, A1), producto(A, A2), producto(B, B1)));
        query = TestDatabaseServices.promoQuery(dataSource());
        exec("DELETE FROM promo_financiacion");
    }

    private static Product producto(String sitio, String url) {
        return Product.builder().sitio(sitio).nombre("SSD").precio(100.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Almacenamiento").genero("unisex")
                .talles(List.of()).rubro("tecnologia").build();
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void sembrar(String json, String... sitios) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT sp_upsert_promos(?::jsonb, ?)")) {
            ps.setString(1, json);
            ps.setArray(2, c.createArrayOf("text", sitios));
            ps.execute();
        }
    }

    private static String fila(String sitio, String medio, String tipoMedio, String red, String tipo, String cuotas,
                               String pct, String alcance, String desde, String hasta, String urls) {
        return "{\"sitio\":\"%s\",\"medioPago\":\"%s\",\"tipoMedio\":\"%s\",\"red\":%s,\"tipo\":\"%s\",\"cuotas\":%s,\"pct\":%s,\"alcance\":\"%s\",\"vigenciaDesde\":%s,\"vigenciaHasta\":%s,\"textoFuente\":\"texto %s\",\"urls\":%s}"
                .formatted(sitio, medio, tipoMedio, red == null ? "null" : "\"" + red + "\"", tipo, cuotas, pct, alcance,
                        desde == null ? "null" : "\"" + desde + "\"", hasta == null ? "null" : "\"" + hasta + "\"",
                        medio, urls);
    }

    private static String sitioCuotas(String sitio, String medio, int n) {
        return fila(sitio, medio, "banco", null, "cuotas_sin_interes", String.valueOf(n), "null", "sitio", null, null, "[]");
    }

    @Test
    @DisplayName("buscar returns the promo with medio, network, kind, vigencia, source text and a UTC last-seen")
    void returnsEveryField() throws SQLException {
        sembrar("[" + fila(A, "Macro", "banco", "Visa", "cuotas_sin_interes", "12", "null", "sitio",
                "2020-01-01", "2999-12-31", "[]") + "]", A);

        PromoPagina pagina = query.buscar(null, null, 0, 50);

        assertThat(pagina.total()).isEqualTo(1);
        PromoVigente p = pagina.promos().get(0);
        assertThat(p.sitio()).isEqualTo(A);
        assertThat(p.medio()).isEqualTo("Macro");
        assertThat(p.tipoMedio()).isEqualTo(TipoMedio.BANCO);
        assertThat(p.red()).isEqualTo("Visa");
        assertThat(p.tipo()).isEqualTo(TipoPromo.CUOTAS_SIN_INTERES);
        assertThat(p.cuotas()).isEqualTo(12);
        assertThat(p.pct()).isNull();
        assertThat(p.alcance()).isEqualTo(Alcance.SITIO);
        assertThat(p.vigenciaDesde()).hasToString("2020-01-01");
        assertThat(p.vigenciaHasta()).hasToString("2999-12-31");
        assertThat(p.textoFuente()).isEqualTo("texto Macro");
        assertThat(p.vistoAt()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("filters by site, and by medio matching the medio OR the required network")
    void filtersBySitioAndMedio() throws SQLException {
        sembrar("[" + sitioCuotas(A, "Santander", 6) + ","
                + fila(A, "Macro", "banco", "Visa", "cuotas_sin_interes", "12", "null", "sitio", null, null, "[]") + ","
                + sitioCuotas(B, "Galicia", 3) + "]", A, B);

        assertThat(query.buscar(A, null, 0, 50).total()).isEqualTo(2);
        assertThat(query.buscar(B, null, 0, 50).promos()).extracting(PromoVigente::medio).containsExactly("Galicia");
        assertThat(query.buscar(null, "Santander", 0, 50).promos()).extracting(PromoVigente::medio)
                .containsExactly("Santander");
        assertThat(query.buscar(null, "Visa", 0, 50).promos()).extracting(PromoVigente::medio)
                .as("Visa is the required network of the Macro promo").containsExactly("Macro");
        assertThat(query.buscar(A, "Galicia", 0, 50).total()).isZero();
        assertThat(query.buscar(null, "Nadie", 0, 50).total()).isZero();
    }

    @Test
    @DisplayName("inactive promos, expired ones and ones that have not started never show")
    void onlyCurrentPromos() throws SQLException {
        sembrar("[" + sitioCuotas(A, "Santander", 6) + ","
                + fila(A, "Galicia", "banco", null, "cuotas_sin_interes", "3", "null", "sitio", null, "2000-01-01", "[]") + ","
                + fila(A, "BBVA", "banco", null, "cuotas_sin_interes", "3", "null", "sitio", "2999-01-01", null, "[]") + ","
                + sitioCuotas(A, "Macro", 3) + "]", A);
        exec("UPDATE promo_financiacion SET activo = false WHERE medio_pago_id = (SELECT id FROM medio_pago WHERE nombre = 'Macro')");

        assertThat(query.buscar(null, null, 0, 50).promos()).extracting(PromoVigente::medio)
                .containsExactly("Santander");
    }

    @Test
    @DisplayName("pages are zero-based slices with the full total")
    void paginates() throws SQLException {
        sembrar("[" + sitioCuotas(A, "Santander", 6) + "," + sitioCuotas(A, "Galicia", 6) + ","
                + sitioCuotas(A, "BBVA", 6) + "]", A);

        PromoPagina primera = query.buscar(null, null, 0, 2);
        PromoPagina segunda = query.buscar(null, null, 1, 2);

        assertThat(primera.total()).isEqualTo(3);
        assertThat(primera.promos()).hasSize(2);
        assertThat(segunda.total()).isEqualTo(3);
        assertThat(segunda.promos()).hasSize(1);
        assertThat(query.buscar(null, null, 5, 2).promos()).isEmpty();
    }

    @Test
    @DisplayName("paraProductos merges the product's own promos with its site's, never another site's")
    void resolvesPerProduct() throws SQLException {
        sembrar("[" + sitioCuotas(A, "Santander", 6) + ","
                + fila(A, "Galicia", "banco", null, "cuotas_sin_interes", "12", "null", "producto", null, null,
                        "[\"" + A1 + "\"]") + ","
                + sitioCuotas(B, "BBVA", 3) + "]", A, B);

        Map<String, List<PromoVigente>> r = query.paraProductos(
                List.of(new ProductoRef(A1, A), new ProductoRef(A2, A), new ProductoRef(B1, B)));

        assertThat(r.get(A1)).extracting(PromoVigente::medio).containsExactlyInAnyOrder("Santander", "Galicia");
        assertThat(r.get(A2)).extracting(PromoVigente::medio).containsExactly("Santander");
        assertThat(r.get(B1)).extracting(PromoVigente::medio).containsExactly("BBVA");
    }

    @Test
    @DisplayName("a product with no promos is absent, an empty request is an empty map")
    void absentWhenNone() throws SQLException {
        sembrar("[" + sitioCuotas(B, "BBVA", 3) + "]", B);

        assertThat(query.paraProductos(List.of(new ProductoRef(A1, A)))).isEmpty();
        assertThat(query.paraProductos(List.of())).isEmpty();
    }
}
