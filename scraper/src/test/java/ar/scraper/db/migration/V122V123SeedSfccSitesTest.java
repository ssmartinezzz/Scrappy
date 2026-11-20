package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform value is pinned by V121PlatformSfccTest; the per-site seed sync by SitioSeedSyncTest. */
@DisplayName("V122 / V123 — Dexter and Moov: sfcc seeds, curated accessory leaves, contained rollbacks")
class V122V123SeedSfccSitesTest extends PostgresTestBase {

    private static String sitioDe(String key) {
        return "sitio_key = '" + key + "'";
    }

    private void assertSeeded(String key, String nombre) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + sitioDe(key))) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo(nombre);
            assertThat(rs.getString("plataforma")).isEqualTo("sfcc");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).as("RubroResolver decides by category").isNull();
            assertThat(rs.getString("rubro_por_defecto")).isNull();
        }
    }

    private Map<String, Integer> leavesPerCategory(String key) throws Exception {
        Map<String, Integer> porCategoria = new LinkedHashMap<>();
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria WHERE " + sitioDe(key)
                     + " GROUP BY categoria ORDER BY categoria")) {
            while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
        }
        return porCategoria;
    }

    private int orphanCategories(String key) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            return count(st, "SELECT count(*) FROM sitio_categoria s LEFT JOIN categoria k ON k.nombre = s.categoria "
                    + "WHERE s." + sitioDe(key) + " AND k.nombre IS NULL");
        }
    }

    @Test
    @DisplayName("Dexter is an sfcc config row left to RubroResolver")
    void seedsDexter() throws Exception {
        assertSeeded("dexter", "Dexter");
    }

    @Test
    @DisplayName("Moov is an sfcc config row left to RubroResolver")
    void seedsMoov() throws Exception {
        assertSeeded("moov", "Moov");
    }

    @Test
    @DisplayName("Dexter: 115 accessory leaves map onto categories that exist in the canon, with the expected spread")
    void mapsDexterLeaves() throws Exception {
        assertThat(leavesPerCategory("dexter")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Accesorio Deportivo", 27),
                    Map.entry("Accesorio de Fitness", 13),
                    Map.entry("Artículo de Camping", 2),
                    Map.entry("Artículo de Limpieza", 2),
                    Map.entry("Auricular", 1),
                    Map.entry("Bijouterie", 1),
                    Map.entry("Bolso", 6),
                    Map.entry("Bufanda", 2),
                    Map.entry("Cinturón", 2),
                    Map.entry("Colágeno", 1),
                    Map.entry("Conservadora", 1),
                    Map.entry("Equipamiento Deportivo", 29),
                    Map.entry("Gorra", 2),
                    Map.entry("Gorro", 1),
                    Map.entry("Guantes", 1),
                    Map.entry("Lentes", 1),
                    Map.entry("Llavero", 1),
                    Map.entry("Medias", 2),
                    Map.entry("Mochila", 1),
                    Map.entry("Monopatín", 1),
                    Map.entry("Paleta", 1),
                    Map.entry("Pelota", 3),
                    Map.entry("Pesas", 1),
                    Map.entry("Proteína", 1),
                    Map.entry("Repuesto de Bicicleta", 3),
                    Map.entry("Riñonera", 1),
                    Map.entry("Silla", 1),
                    Map.entry("Termo y Botella", 4),
                    Map.entry("Toalla", 1),
                    Map.entry("Vincha", 1),
                    Map.entry("Zapato", 1)));
        assertThat(orphanCategories("dexter")).isZero();
    }

    @Test
    @DisplayName("Moov: 27 accessory leaves map onto categories that exist in the canon, with the expected spread")
    void mapsMoovLeaves() throws Exception {
        assertThat(leavesPerCategory("moov")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Accesorio Deportivo", 4),
                    Map.entry("Accesorio de Fitness", 1),
                    Map.entry("Artículo de Limpieza", 2),
                    Map.entry("Bijouterie", 1),
                    Map.entry("Billetera", 1),
                    Map.entry("Bolso", 4),
                    Map.entry("Bufanda", 1),
                    Map.entry("Equipamiento Deportivo", 1),
                    Map.entry("Gorra", 1),
                    Map.entry("Gorro", 1),
                    Map.entry("Guantes", 1),
                    Map.entry("Medias", 2),
                    Map.entry("Mochila", 1),
                    Map.entry("Remera", 1),
                    Map.entry("Riñonera", 1),
                    Map.entry("Termo y Botella", 2),
                    Map.entry("Vincha", 1),
                    Map.entry("Zapato", 1)));
        assertThat(orphanCategories("moov")).isZero();
    }

    @Test
    @DisplayName("rolling back V123 then V122 removes each site and its mapping, and nothing else")
    void rollbacksAreContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");
                int otrasHojas = count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key NOT IN ('dexter', 'moov')");

                st.execute(DocumentedRollback.sqlFor("V123"));
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + sitioDe("moov"))).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + sitioDe("moov"))).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + sitioDe("dexter"))).isEqualTo(1);

                st.execute(DocumentedRollback.sqlFor("V122"));
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + sitioDe("dexter"))).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + sitioDe("dexter"))).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 2);
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key NOT IN ('dexter', 'moov')"))
                        .isEqualTo(otrasHojas);
            } finally {
                c.rollback();
            }
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
