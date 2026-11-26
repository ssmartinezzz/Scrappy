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

@DisplayName("V114 — Oncity: mixed VTEX seed defaulting to hogar, curated leaf mapping, and a contained rollback")
class V114SeedOncityTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V114__seed_oncity.sql";
    /** The seed still names the category V141 renamed to Networking; the test puts the old row back inside its own transaction. */
    private static final String CATEGORIA_VIEJA = "INSERT INTO categoria (nombre) VALUES ('Red') ON CONFLICT (nombre) DO NOTHING";
    private static final String SITIO = "sitio_key = 'oncity'";

    @Test
    @DisplayName("Oncity is a vtex row, not monomarca, defaulting to hogar")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + SITIO)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Oncity");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("hogar");
        }
    }

    @Test
    @DisplayName("325 leaves map to categories that exist in the canon, with the expected spread")
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
                    Map.entry("Accesorio Deportivo", 2),
                    Map.entry("Accesorio de Auto", 3),
                    Map.entry("Accesorio de Baño", 2),
                    Map.entry("Accesorio de Fitness", 2),
                    Map.entry("Accesorio de Herramienta", 1),
                    Map.entry("Accesorio de Pileta", 1),
                    Map.entry("Accesorio de Pintura", 1),
                    Map.entry("Accesorio para Mascota", 3),
                    Map.entry("Aire Acondicionado", 1),
                    Map.entry("Aislante", 1),
                    Map.entry("Alfombra", 2),
                    Map.entry("Alimento para Mascota", 2),
                    Map.entry("Almacenamiento", 2),
                    Map.entry("Almohadón", 1),
                    Map.entry("Artículo de Camping", 3),
                    Map.entry("Artículo de Limpieza", 2),
                    Map.entry("Aspiradora", 2),
                    Map.entry("Auricular", 2),
                    Map.entry("Bazar", 18),
                    Map.entry("Bicicleta", 5),
                    Map.entry("Bolsa de Dormir", 1),
                    Map.entry("Bolso", 1),
                    Map.entry("Bomba de Agua", 1),
                    Map.entry("Cable", 2),
                    Map.entry("Caja y Canasto", 2),
                    Map.entry("Calefactor", 1),
                    Map.entry("Calefón", 1),
                    Map.entry("Cama", 2),
                    Map.entry("Cama para Mascota", 1),
                    Map.entry("Campana y Extractor", 1),
                    Map.entry("Candado", 1),
                    Map.entry("Carpa", 1),
                    Map.entry("Celular", 4),
                    Map.entry("Cerradura", 1),
                    Map.entry("Cocina y Horno", 5),
                    Map.entry("Colchón", 3),
                    Map.entry("Colgador", 1),
                    Map.entry("Consola", 1),
                    Map.entry("Cooler", 1),
                    Map.entry("Cortina", 2),
                    Map.entry("Cuidado de la Piel", 6),
                    Map.entry("Cuidado del Cabello", 5),
                    Map.entry("Cámara", 4),
                    Map.entry("Decoración", 7),
                    Map.entry("Desodorante", 1),
                    Map.entry("Diluyente", 1),
                    Map.entry("Ducha", 1),
                    Map.entry("Equipamiento Deportivo", 3),
                    Map.entry("Escalera", 1),
                    Map.entry("Escritorio", 1),
                    Map.entry("Espejo", 2),
                    Map.entry("Estantería", 1),
                    Map.entry("Fuente", 1),
                    Map.entry("GPU", 1),
                    Map.entry("Gazebo y Sombrilla", 1),
                    Map.entry("Grifería", 1),
                    Map.entry("Guantes", 1),
                    Map.entry("Heladera", 4),
                    Map.entry("Herramienta Eléctrica", 4),
                    Map.entry("Herramienta Manual", 7),
                    Map.entry("Herramienta de Auto", 1),
                    Map.entry("Hidrolavadora", 1),
                    Map.entry("Higiene de Mascota", 2),
                    Map.entry("Iluminación", 6),
                    Map.entry("Impermeabilizante", 2),
                    Map.entry("Impresión", 5),
                    Map.entry("Joystick", 2),
                    Map.entry("Juego de Mesa", 1),
                    Map.entry("Juguete", 11),
                    Map.entry("Lavarropas", 4),
                    Map.entry("Libro", 1),
                    Map.entry("Llavero", 1),
                    Map.entry("Mantel", 1),
                    Map.entry("Maquillaje", 9),
                    Map.entry("Mesa", 4),
                    Map.entry("Micrófono", 2),
                    Map.entry("Mochila", 3),
                    Map.entry("Monitor", 3),
                    Map.entry("Monopatín", 4),
                    Map.entry("Mouse", 1),
                    Map.entry("Mueble", 10),
                    Map.entry("Máquina de Jardín", 4),
                    Map.entry("Notebook", 3),
                    Map.entry("Organización", 2),
                    Map.entry("Otros", 10),
                    Map.entry("PC", 2),
                    Map.entry("Paleta", 1),
                    Map.entry("Parrilla", 2),
                    Map.entry("Pelota", 3),
                    Map.entry("Peluche", 1),
                    Map.entry("Pequeño Electrodoméstico", 23),
                    Map.entry("Perfume", 7),
                    Map.entry("Pesas", 1),
                    Map.entry("Pileta", 1),
                    Map.entry("Pintura", 3),
                    Map.entry("Placard", 1),
                    Map.entry("Planta Artificial", 1),
                    Map.entry("Pulverizador", 1),
                    Map.entry("Red", 1),
                    Map.entry("Reloj", 2),
                    Map.entry("Reposera y Hamaca", 1),
                    Map.entry("Repuesto de Bicicleta", 1),
                    Map.entry("Respaldo", 1),
                    Map.entry("Revestimiento", 1),
                    Map.entry("Ropa de Cama", 4),
                    Map.entry("Sanitario", 2),
                    Map.entry("Silla", 5),
                    Map.entry("Sillón", 2),
                    Map.entry("Soldadora", 1),
                    Map.entry("TV y Audio", 9),
                    Map.entry("Tablet", 2),
                    Map.entry("Taladro", 2),
                    Map.entry("Teclado", 1),
                    Map.entry("Tendedero", 1),
                    Map.entry("Termo y Botella", 1),
                    Map.entry("Termotanque", 1),
                    Map.entry("Toalla", 1),
                    Map.entry("UPS", 1),
                    Map.entry("Vanitory", 1),
                    Map.entry("Ventilador", 1),
                    Map.entry("Videojuego", 1)));
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

                st.execute(DocumentedRollback.sqlFor("V114"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V114SeedOncityTest.class.getResourceAsStream(MIGRACION))) {
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
