package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Site registry")
@Story("V137 moves Fursten from Tiendanube to Shopify")
@DisplayName("V137 — only sitio.plataforma of fursten changes")
class V137FurstenShopifyTest extends PostgresTestBase {

    private static final String FILA_Y_VECINAS = """
            SELECT sitio_key, plataforma, origen, monomarca, rubro_forzado IS NULL AS sin_rubro, es_premium
            FROM sitio WHERE sitio_key IN ('fursten', 'fuark', 'baesics') ORDER BY sitio_key""";

    @Test
    @DisplayName("fursten becomes shopify and keeps every other column; its neighbours are untouched")
    void movesOnlyThePlatform() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE sitio SET plataforma = 'tiendanube' WHERE sitio_key = 'fursten'");
            String antes = filas(st);

            st.execute(script());

            String despues = filas(st);
            assertThat(despues).isEqualTo(antes.replace("fursten|tiendanube", "fursten|shopify"));
            assertThat(despues).contains("fursten|shopify|config|true|true|false")
                    .contains("fuark|tiendanube").contains("baesics|shopify");
        }
    }

    @Test
    @DisplayName("applying it again changes nothing")
    void idempotent() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(script());
            String una = filas(st);
            st.execute(script());
            assertThat(filas(st)).isEqualTo(una);
        }
    }

    private static String script() throws Exception {
        try (InputStream in = Objects.requireNonNull(V137FurstenShopifyTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V137__fursten_shopify.sql"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String filas(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery(FILA_Y_VECINAS)) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('|').append(rs.getString(2)).append('|')
                        .append(rs.getString(3)).append('|').append(rs.getBoolean(4)).append('|')
                        .append(rs.getBoolean(5)).append('|').append(rs.getBoolean(6)).append('\n');
            }
        }
        return sb.toString();
    }
}
