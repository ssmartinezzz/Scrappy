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
@Story("El Mundo del Juguete: V77 seed, V78 curated leaf mapping and their documented rollbacks")
@DisplayName("V77/V78 — El Mundo del Juguete seed, curated tree mapping and rollbacks")
class V77V78ElMundoDelJugueteTest extends PostgresTestBase {

    private static final String V78 = "/db/migration/V78__sitio_categoria_mapeo_elmundodeljuguete.sql";
    private static final String KEY = "elmundodeljuguete";

    @Test
    @DisplayName("V77 seeds an unforced VTEX site whose default rubro is juguetes")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, rubro_forzado, rubro_por_defecto, monomarca, origen "
                     + "FROM sitio WHERE sitio_key = '" + KEY + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Elmundodeljuguete");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("juguetes");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("origen")).isEqualTo("config");
        }
    }

    @Test
    @DisplayName("V78 maps 66 leaves to existing categories, with the expected spread per category")
    void mapsLeavesToExistingCategories() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());

            Map<String, Integer> porCategoria = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria "
                    + "WHERE sitio_key = '" + KEY + "' GROUP BY categoria ORDER BY categoria")) {
                while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
            }

            assertThat(porCategoria).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Bicicleta", 2), Map.entry("Bolso", 1), Map.entry("Auricular", 1),
                    Map.entry("Consola", 1), Map.entry("Equipamiento Deportivo", 1), Map.entry("Joystick", 1),
                    Map.entry("Juego de Mesa", 6), Map.entry("Juguete", 40), Map.entry("Libro", 1),
                    Map.entry("Mochila", 1), Map.entry("Monopatín", 3), Map.entry("Mouse", 1),
                    Map.entry("Pantufla", 1), Map.entry("Pelota", 1), Map.entry("Peluche", 1),
                    Map.entry("Ropa de Cama", 1), Map.entry("Silla", 1), Map.entry("Teclado", 1),
                    Map.entry("Zapatilla", 1)));
        }
    }

    @Test
    @DisplayName("V78 spot checks: a leaf and its category as read from the real tree")
    void spotChecks() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());

            assertThat(categoriaDe(st, 26)).isEqualTo("Peluche");
            assertThat(categoriaDe(st, 34)).isEqualTo("Libro");
            assertThat(categoriaDe(st, 92)).isEqualTo("Juguete");
            assertThat(categoriaDe(st, 49)).isEqualTo("Juguete");
            assertThat(categoriaDe(st, 52)).isEqualTo("Juego de Mesa");
            assertThat(categoriaDe(st, 165)).isEqualTo("Ropa de Cama");
            assertThat(categoriaDe(st, 117)).isEqualTo("Monopatín");
            assertThat(categoriaDe(st, 80)).isEqualTo("Bicicleta");
        }
    }

    @Test
    @DisplayName("V78 leaves gift cards, shopping bags and the mixed leaves unmapped")
    void abstentionsAreNotSeeded() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());

            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                    + "' AND origen_id IN (71, 158, 159, 151, 166, 129, 42, 98, 99, 144, 145)")).isZero();
        }
    }

    @Test
    @DisplayName("replaying V78 over a scraped tree keeps the nodes and only sets the mapping")
    void replayKeepsScrapedNodes() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, es_servicio) "
                    + "VALUES ('" + KEY + "', 26, 'Peluches', false)");
            st.execute(migracion());
            st.execute(migracion());

            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY + "'"))
                    .isEqualTo(66);
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                    + "' AND origen_id = 26 AND nombre = 'Peluches' AND categoria = 'Peluche'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V78 rollback removes the seeded leaves nobody scraped and clears the mapping of the scraped ones")
    void v77Rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, es_servicio) "
                        + "VALUES ('" + KEY + "', 9999, 'Padre', false)");
                st.execute("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, padre_id) "
                        + "SELECT '" + KEY + "', 26, 'Peluches', id FROM sitio_categoria WHERE origen_id = 9999 "
                        + "AND sitio_key = '" + KEY + "'");
                st.execute(migracion());

                st.execute(DocumentedRollback.sqlFor("V78"));

                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                        + "' AND categoria IS NOT NULL")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY
                        + "' AND origen_id = 26")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = '" + KEY + "'"))
                        .isEqualTo(2);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("V77 rollback removes the site; with scraped products it keeps the row")
    void v76Rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(migracion());
                st.execute(DocumentedRollback.sqlFor("V78"));
                st.execute(DocumentedRollback.sqlFor("V77"));
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = '" + KEY + "'")).isZero();
            } finally {
                c.rollback();
            }
        }
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                        INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                        VALUES ('https://rollback.test/peluche', 'Elmundodeljuguete', 'Peluche', 1000, 'juguetes', 'Peluche')
                        """);
                st.execute("SET CONSTRAINTS ALL IMMEDIATE");
                st.execute(DocumentedRollback.sqlFor("V78"));
                st.execute(DocumentedRollback.sqlFor("V77"));
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = '" + KEY + "'")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM productos WHERE url = 'https://rollback.test/peluche'"))
                        .isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws Exception {
        try (InputStream in = Objects.requireNonNull(V77V78ElMundoDelJugueteTest.class.getResourceAsStream(V78))) {
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
