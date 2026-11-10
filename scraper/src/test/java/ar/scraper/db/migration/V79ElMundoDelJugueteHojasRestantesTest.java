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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Site registry")
@Story("El Mundo del Juguete: V79 maps the 30 leaves V78 left unmapped")
@DisplayName("V79 — El Mundo del Juguete remaining leaves and rollback")
class V79ElMundoDelJugueteHojasRestantesTest extends PostgresTestBase {

    private static final String V78 = "/db/migration/V78__sitio_categoria_mapeo_elmundodeljuguete.sql";
    private static final String V79 = "/db/migration/V79__sitio_categoria_hojas_restantes_elmundodeljuguete.sql";
    private static final String KEY = "elmundodeljuguete";
    private static final String HOJAS_DE_V79 = "41, 42, 63, 65, 74, 79, 98, 99, 109, 112, 116, 119, 129, 132, 133, 134, "
            + "135, 136, 137, 138, 144, 145, 146, 147, 153, 156, 157, 160, 161, 166";

    @Test
    @DisplayName("V79 maps the 30 remaining leaves, with the expected spread per category")
    void mapsTheRemainingLeaves() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql(V79));

            Map<String, Integer> porCategoria = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria "
                    + "WHERE sitio_key = '" + KEY + "' GROUP BY categoria")) {
                while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
            }

            assertThat(porCategoria).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Juguete", 6), Map.entry("Otros", 6), Map.entry("Mueble", 2),
                    Map.entry("Equipamiento Deportivo", 2), Map.entry("Termo y Botella", 2),
                    Map.entry("Conservadora", 1), Map.entry("Cámara", 1), Map.entry("Monopatín", 1),
                    Map.entry("Inflable", 1), Map.entry("Mueble de Exterior", 1), Map.entry("Carpa", 1),
                    Map.entry("Pileta", 1), Map.entry("Accesorio de Pileta", 1), Map.entry("Artículo de Camping", 1),
                    Map.entry("Organización", 1), Map.entry("Bazar", 1), Map.entry("Bolso", 1)));
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                    + "' AND origen_id IN (" + HOJAS_DE_V79 + ")")).isEqualTo(30);
        }
    }

    @Test
    @DisplayName("V79 spot checks: the mixed leaves and the ones with no canonical category")
    void spotChecks() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql(V79));

            assertThat(categoriaDe(st, 166)).isEqualTo("Juguete");
            assertThat(categoriaDe(st, 42)).isEqualTo("Juguete");
            assertThat(categoriaDe(st, 98)).isEqualTo("Otros");
            assertThat(categoriaDe(st, 144)).isEqualTo("Termo y Botella");
            assertThat(categoriaDe(st, 129)).isEqualTo("Inflable");
            assertThat(categoriaDe(st, 119)).isEqualTo("Monopatín");
        }
    }

    @Test
    @DisplayName("V78 + V79 leave only the gift cards and the shopping bags unmapped")
    void togetherWithV78OnlyExcludedNodesStayUnmapped() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql(V78));
            st.execute(sql(V79));

            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY + "'"))
                    .isEqualTo(96);
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                    + "' AND origen_id IN (71, 151, 158, 159)")).isZero();
        }
    }

    @Test
    @DisplayName("replaying V79 over a scraped node keeps the node and only sets the mapping")
    void replayKeepsScrapedNodes() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, es_servicio) "
                    + "VALUES ('" + KEY + "', 166, 'Outlet', false)");
            st.execute(sql(V79));
            st.execute(sql(V79));

            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY + "'"))
                    .isEqualTo(30);
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                    + "' AND origen_id = 166 AND nombre = 'Outlet' AND categoria = 'Juguete'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V79 rollback clears its 30 mappings, keeps every node and leaves V78's mapping intact")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sql(V78));
                st.execute(sql(V79));

                st.execute(DocumentedRollback.sqlFor("V79"));

                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                        + "' AND origen_id IN (" + HOJAS_DE_V79 + ") AND categoria IS NOT NULL")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                        + "' AND categoria IS NOT NULL")).isEqualTo(66);
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY + "'"))
                        .isEqualTo(96);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the rollback chain V79 → V78 clears the whole mapping and keeps the scraped top-level Outlet node")
    void rollbackChainKeepsScrapedTopLevelNodes() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, es_servicio) "
                        + "VALUES ('" + KEY + "', 166, 'Outlet', false)");
                st.execute(sql(V78));
                st.execute(sql(V79));

                st.execute(DocumentedRollback.sqlFor("V79"));
                st.execute(DocumentedRollback.sqlFor("V78"));

                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                        + "' AND categoria IS NOT NULL")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                        + "' AND origen_id = 166")).isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String sql(String recurso) throws Exception {
        try (InputStream in = Objects.requireNonNull(
                V79ElMundoDelJugueteHojasRestantesTest.class.getResourceAsStream(recurso), recurso)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String categoriaDe(Statement st, int origenId) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT categoria FROM sitio_categoria WHERE sitio_key = '" + KEY
                + "' AND origen_id = " + origenId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
