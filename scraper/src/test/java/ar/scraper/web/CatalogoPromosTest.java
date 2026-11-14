package ar.scraper.web;

import ar.scraper.catalog.PoliticaStock;
import ar.scraper.catalog.ProductKey;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.TestDatabaseServices;
import ar.scraper.indices.IndiceService;
import ar.scraper.model.Product;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Promos in the catalog list ({@code mejorPromo} + {@code medios}) and in the product detail
 * ({@code promos}): product-scoped promos plus the site's, resolved set-based, absent when there are none.
 */
@DisplayName("CatalogoController — promos in the list rows and the product detail")
class CatalogoPromosTest extends ar.scraper.db.support.PostgresTestBase {

    private static final String CON = "https://promo.example/con";
    private static final String SIN = "https://otro.example/sin";

    private CatalogoController controller;

    @BeforeEach
    void setUp() throws SQLException {
        ScraperService service = mock(ScraperService.class);
        IndiceService indiceService = mock(IndiceService.class);
        when(indiceService.deflactorParaRubro(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ar.scraper.indices.Deflactor.NEUTRO);
        ScraperConfig config = mock(ScraperConfig.class);
        when(config.getMoneda()).thenReturn("ARS");
        DatabaseService db = TestDatabaseServices.create(dataSource());
        controller = new CatalogoController(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(),
                config, indiceService, new PoliticaStock(Clock.systemUTC(), Duration.ofDays(7)),
                TestDatabaseServices.promoQuery(dataSource()));
        db.upsertProductos(List.of(producto("SitioPromo", CON, 100), producto("SitioSinPromo", SIN, 200)));
        exec("DELETE FROM promo_financiacion");
        sembrar("[" + sitio("SitioPromo", "Santander", "banco", "cuotas_sin_interes", "6", "null") + ","
                + sitio("SitioPromo", "Transferencia", "medio", "descuento", "null", "10") + ","
                + sitio("SitioPromo", "Galicia", "banco", "cuotas_sin_interes", "12", "null") + ","
                + "{\"sitio\":\"SitioPromo\",\"medioPago\":\"Mercado Pago\",\"tipoMedio\":\"billetera\",\"tipo\":\"descuento\","
                + "\"pct\":15,\"alcance\":\"producto\",\"urls\":[\"" + CON + "\"]}]", "SitioPromo");
    }

    private static Product producto(String sitio, String url, double precio) {
        return Product.builder().sitio(sitio).nombre("Producto " + url).precio(precio).url(url).imagenUrl("img")
                .categoria("Remera").genero("unisex").talles(List.of()).marca("Nike").rubro("indumentaria").build();
    }

    private static String sitio(String sitio, String medio, String tipoMedio, String tipo, String cuotas, String pct) {
        return "{\"sitio\":\"%s\",\"medioPago\":\"%s\",\"tipoMedio\":\"%s\",\"tipo\":\"%s\",\"cuotas\":%s,\"pct\":%s,\"alcance\":\"sitio\",\"urls\":[]}"
                .formatted(sitio, medio, tipoMedio, tipo, cuotas, pct);
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

    private JsonNode fila(String url) {
        var resp = controller.data(0, 24, null, null, null, null, null, null, null, null, null, null, "precio_asc",
                null, null, null, null, null, null, null, null, null, null, null, null);
        for (JsonNode p : Wire.data(resp).path("productos")) if (url.equals(p.path("url").asText())) return p;
        throw new AssertionError("no row " + url);
    }

    @Test
    @DisplayName("a list row carries the best promo, compact, and the accepted medios in priority order")
    void listRowCarriesBestPromoAndMedios() {
        JsonNode row = fila(CON);

        JsonNode mejor = row.path("mejorPromo");
        assertThat(mejor.path("medio").asText()).as("15% product-scoped beats 10% and the installments").isEqualTo("Mercado Pago");
        assertThat(mejor.path("tipo").asText()).isEqualTo("descuento");
        assertThat(mejor.path("pct").asDouble()).isEqualTo(15.0);
        assertThat(mejor.has("cuotas")).isTrue();
        assertThat(mejor.path("cuotas").isNull()).isTrue();
        assertThat(mejor.path("red").isNull()).isTrue();
        assertThat(row.path("medios")).extracting(JsonNode::asText)
                .containsExactly("Mercado Pago", "Transferencia", "Santander", "Galicia");
    }

    @Test
    @DisplayName("a row with no promos has an empty medios list and no mejorPromo")
    void rowWithoutPromos() {
        JsonNode row = fila(SIN);

        assertThat(row.has("mejorPromo")).isFalse();
        assertThat(row.path("medios").isArray()).isTrue();
        assertThat(row.path("medios")).isEmpty();
    }

    @Test
    @DisplayName("the detail lists the product's own and its site's promos, most useful first")
    void detailListsPromosSorted() {
        JsonNode data = Wire.data(controller.productoDetalle(ProductKey.of(CON)));

        JsonNode promos = data.path("promos");
        assertThat(promos).extracting(p -> p.path("medio").path("nombre").asText())
                .containsExactly("Mercado Pago", "Transferencia", "Galicia", "Santander");
        JsonNode primera = promos.get(0);
        assertThat(primera.path("medio").path("tipo").asText()).isEqualTo("billetera");
        assertThat(primera.path("red").isNull()).isTrue();
        assertThat(primera.path("alcance").asText()).isEqualTo("producto");
        assertThat(primera.path("tipo").asText()).isEqualTo("descuento");
        assertThat(primera.path("pct").asDouble()).isEqualTo(15.0);
        assertThat(primera.path("vistoAt").asText()).endsWith("Z");
        assertThat(promos.get(3).path("cuotas").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("the detail of a product with no promos has an empty array")
    void detailWithoutPromos() {
        JsonNode data = Wire.data(controller.productoDetalle(ProductKey.of(SIN)));

        assertThat(data.path("promos").isArray()).isTrue();
        assertThat(data.path("promos")).isEmpty();
    }
}
