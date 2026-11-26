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

@DisplayName("V110 — Cetrogar: mixed VTEX seed, curated leaf mapping, and a contained rollback")
class V110SeedCetrogarTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V110__seed_cetrogar.sql";
    /** The seed still names the category V141 renamed to Networking; the test puts the old row back inside its own transaction. */
    private static final String CATEGORIA_VIEJA = "INSERT INTO categoria (nombre) VALUES ('Red') ON CONFLICT (nombre) DO NOTHING";
    private static final String SITIO = "sitio_key = 'cetrogar'";

    @Test
    @DisplayName("Cetrogar is a vtex row, not monomarca, defaulting to electrodomesticos since V139")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + SITIO)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Cetrogar");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).as("V139 moved the default from tecnologia").isEqualTo("electrodomesticos");
        }
    }

    @Test
    @DisplayName("125 leaves map to categories that exist in the canon, with the expected spread")
    void mapsLeavesToExistingCategories() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.executeUpdate(CATEGORIA_VIEJA);
            st.execute(migracion());
            Map<String, Integer> porCategoria = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria WHERE " + SITIO
                    + " GROUP BY categoria ORDER BY categoria")) {
                while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
            }
            assertThat(porCategoria).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Accesorio para Mascota", 1),
                    Map.entry("Aire Acondicionado", 2),
                    Map.entry("Almacenamiento", 1),
                    Map.entry("Almohadón", 1),
                    Map.entry("Amoladora", 1),
                    Map.entry("Aspiradora", 1),
                    Map.entry("Auricular", 1),
                    Map.entry("Bicicleta", 5),
                    Map.entry("Bolso", 1),
                    Map.entry("Bomba de Agua", 2),
                    Map.entry("Calefactor", 1),
                    Map.entry("Calefón", 3),
                    Map.entry("Cama", 3),
                    Map.entry("Campana y Extractor", 1),
                    Map.entry("Celular", 2),
                    Map.entry("Cocina y Horno", 6),
                    Map.entry("Colchón", 1),
                    Map.entry("Conservadora", 1),
                    Map.entry("Consola", 1),
                    Map.entry("Cámara", 2),
                    Map.entry("Escritorio", 1),
                    Map.entry("Gazebo y Sombrilla", 2),
                    Map.entry("Heladera", 6),
                    Map.entry("Herramienta Eléctrica", 5),
                    Map.entry("Hidrolavadora", 1),
                    Map.entry("Impresión", 1),
                    Map.entry("Inflable", 1),
                    Map.entry("Lavarropas", 4),
                    Map.entry("Mesa", 2),
                    Map.entry("Monitor", 1),
                    Map.entry("Monopatín", 1),
                    Map.entry("Mueble", 1),
                    Map.entry("Máquina de Jardín", 7),
                    Map.entry("Notebook", 1),
                    Map.entry("Organización", 1),
                    Map.entry("PC", 1),
                    Map.entry("Parrilla", 1),
                    Map.entry("Pequeño Electrodoméstico", 17),
                    Map.entry("Pileta", 2),
                    Map.entry("Placard", 1),
                    Map.entry("Red", 1),
                    Map.entry("Reloj", 1),
                    Map.entry("Reposera y Hamaca", 1),
                    Map.entry("Riego", 1),
                    Map.entry("Ropa de Cama", 6),
                    Map.entry("Sierra", 1),
                    Map.entry("Silla", 2),
                    Map.entry("Soldadora", 1),
                    Map.entry("TV y Audio", 5),
                    Map.entry("Tablet", 1),
                    Map.entry("Taladro", 2),
                    Map.entry("Termo y Botella", 1),
                    Map.entry("Termotanque", 3),
                    Map.entry("Toalla", 1),
                    Map.entry("Vanitory", 1),
                    Map.entry("Ventilador", 1),
                    Map.entry("Videojuego", 2)));
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria s LEFT JOIN categoria k ON k.nombre = s.categoria "
                    + "WHERE s." + SITIO + " AND k.nombre IS NULL")).isZero();
            c.rollback();
        }
    }

    @Test
    @DisplayName("rolling back removes the site, its mapping, and nothing else")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate(CATEGORIA_VIEJA);
                st.execute(migracion());
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");

                st.execute(DocumentedRollback.sqlFor("V110"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V110SeedCetrogarTest.class.getResourceAsStream(MIGRACION))) {
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
