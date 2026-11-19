package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V107 — Samsung: monobrand VTEX seed with its marca row, curated leaf mapping, and a contained rollback")
class V107SeedSamsungTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V107__seed_samsung.sql";
    private static final String SITIO = "sitio_key = 'samsung'";

    @Test
    @DisplayName("Samsung is a monomarca vtex row defaulting to tecnologia, and 'Samsung' is a marca row")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + SITIO)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Samsung");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isTrue();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("tecnologia");
        }
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Samsung'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("87 leaves map to categories that exist in the canon, with the expected spread")
    void mapsLeavesToExistingCategories() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());
            Map<String, Integer> porCategoria = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria WHERE " + SITIO
                    + " GROUP BY categoria ORDER BY categoria")) {
                while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
            }
            assertThat(porCategoria).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Aire Acondicionado", 1),
                    Map.entry("Aspiradora", 1),
                    Map.entry("Auricular", 7),
                    Map.entry("Campana y Extractor", 1),
                    Map.entry("Celular", 9),
                    Map.entry("Cocina y Horno", 3),
                    Map.entry("Heladera", 6),
                    Map.entry("Lavarropas", 6),
                    Map.entry("Monitor", 5),
                    Map.entry("Mouse", 1),
                    Map.entry("Notebook", 4),
                    Map.entry("Otros", 19),
                    Map.entry("Reloj", 7),
                    Map.entry("TV y Audio", 15),
                    Map.entry("Tablet", 2)));
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria s LEFT JOIN categoria k ON k.nombre = s.categoria "
                    + "WHERE s." + SITIO + " AND k.nombre IS NULL")).isZero();
        }
    }

    @Test
    @DisplayName("rolling back removes the site, its mapping and the Samsung marca, and nothing else")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(migracion());
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");
                int marcasAntes = count(st, "SELECT count(*) FROM marca");

                st.execute(DocumentedRollback.sqlFor("V107"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Samsung'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
                assertThat(count(st, "SELECT count(*) FROM marca")).isEqualTo(marcasAntes - 1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V107SeedSamsungTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
