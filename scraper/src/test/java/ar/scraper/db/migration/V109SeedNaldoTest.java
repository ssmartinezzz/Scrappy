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

@DisplayName("V109 — Naldo: mixed VTEX seed defaulting to hogar, curated leaf mapping, and a contained rollback")
class V109SeedNaldoTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V109__seed_naldo.sql";
    private static final String SITIO = "sitio_key = 'naldo'";

    @Test
    @DisplayName("Naldo is a vtex row, not monomarca, defaulting to hogar")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + SITIO)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Naldo");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("hogar");
        }
    }

    @Test
    @DisplayName("133 leaves map to categories that exist in the canon, with the expected spread")
    void mapsLeavesToExistingCategories() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());
            Map<String, Integer> porCategoria = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria WHERE " + SITIO
                    + " GROUP BY categoria ORDER BY categoria")) {
                while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
            }
            assertThat(porCategoria).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Accesorio de Auto", 1),
                    Map.entry("Accesorio de Herramienta", 1),
                    Map.entry("Accesorio para Mascota", 1),
                    Map.entry("Aire Acondicionado", 5),
                    Map.entry("Alimento para Mascota", 1),
                    Map.entry("Amoladora", 1),
                    Map.entry("Aspiradora", 1),
                    Map.entry("Auricular", 2),
                    Map.entry("Bazar", 1),
                    Map.entry("Bicicleta", 4),
                    Map.entry("Bolsa de Dormir", 1),
                    Map.entry("Bolso", 1),
                    Map.entry("Bomba de Agua", 1),
                    Map.entry("Calefactor", 4),
                    Map.entry("Calefón", 1),
                    Map.entry("Cama", 3),
                    Map.entry("Campana y Extractor", 2),
                    Map.entry("Carpa", 1),
                    Map.entry("Celular", 3),
                    Map.entry("Cocina y Horno", 4),
                    Map.entry("Colchón", 1),
                    Map.entry("Conservadora", 1),
                    Map.entry("Consola", 1),
                    Map.entry("Cortina", 1),
                    Map.entry("Cámara", 2),
                    Map.entry("Decoración", 1),
                    Map.entry("Gazebo y Sombrilla", 2),
                    Map.entry("Grifería", 1),
                    Map.entry("Heladera", 5),
                    Map.entry("Herramienta Eléctrica", 3),
                    Map.entry("Herramienta Manual", 1),
                    Map.entry("Hidrolavadora", 1),
                    Map.entry("Iluminación", 1),
                    Map.entry("Impresión", 1),
                    Map.entry("Juguete", 1),
                    Map.entry("Lavarropas", 4),
                    Map.entry("Mesa", 2),
                    Map.entry("Micrófono", 1),
                    Map.entry("Mochila", 1),
                    Map.entry("Monitor", 1),
                    Map.entry("Monopatín", 1),
                    Map.entry("Mueble", 3),
                    Map.entry("Mueble de Exterior", 1),
                    Map.entry("Máquina de Jardín", 6),
                    Map.entry("Notebook", 1),
                    Map.entry("Otros", 5),
                    Map.entry("PC", 1),
                    Map.entry("Paleta", 1),
                    Map.entry("Parrilla", 1),
                    Map.entry("Pequeño Electrodoméstico", 16),
                    Map.entry("Perfume", 3),
                    Map.entry("Pileta", 1),
                    Map.entry("Reloj", 2),
                    Map.entry("Reposera y Hamaca", 2),
                    Map.entry("Ropa de Cama", 1),
                    Map.entry("Sierra", 1),
                    Map.entry("Sillón", 2),
                    Map.entry("Soldadora", 1),
                    Map.entry("TV y Audio", 3),
                    Map.entry("Tablet", 1),
                    Map.entry("Taladro", 2),
                    Map.entry("Termo y Botella", 1),
                    Map.entry("Termotanque", 2),
                    Map.entry("Toalla", 1),
                    Map.entry("Ventilador", 2),
                    Map.entry("Videojuego", 1)));
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria s LEFT JOIN categoria k ON k.nombre = s.categoria "
                    + "WHERE s." + SITIO + " AND k.nombre IS NULL")).isZero();
        }
    }

    @Test
    @DisplayName("rolling back removes the site, its mapping, and nothing else")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(migracion());
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");

                st.execute(DocumentedRollback.sqlFor("V109"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V109SeedNaldoTest.class.getResourceAsStream(MIGRACION))) {
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
