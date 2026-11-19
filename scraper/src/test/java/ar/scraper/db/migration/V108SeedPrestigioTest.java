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

@DisplayName("V108 — Prestigio: mixed VTEX seed defaulting to construccion, curated leaf mapping, and a contained rollback")
class V108SeedPrestigioTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V108__seed_prestigio.sql";
    private static final String SITIO = "sitio_key = 'prestigio'";

    @Test
    @DisplayName("Prestigio is a vtex row, not monomarca, defaulting to construccion")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + SITIO)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Prestigio");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("construccion");
        }
    }

    @Test
    @DisplayName("97 leaves map to categories that exist in the canon, with the expected spread")
    void mapsLeavesToExistingCategories() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());
            Map<String, Integer> porCategoria = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT categoria, count(*) FROM sitio_categoria WHERE " + SITIO
                    + " GROUP BY categoria ORDER BY categoria")) {
                while (rs.next()) porCategoria.put(rs.getString(1), rs.getInt(2));
            }
            assertThat(porCategoria).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    Map.entry("Accesorio de Baño", 2),
                    Map.entry("Accesorio de Pintura", 1),
                    Map.entry("Adhesivo", 2),
                    Map.entry("Aire Acondicionado", 1),
                    Map.entry("Artículo de Limpieza", 2),
                    Map.entry("Aspiradora", 1),
                    Map.entry("Bazar", 12),
                    Map.entry("Caja y Canasto", 2),
                    Map.entry("Calefactor", 2),
                    Map.entry("Campana y Extractor", 1),
                    Map.entry("Celular", 1),
                    Map.entry("Cinta Adhesiva", 1),
                    Map.entry("Cocina y Horno", 5),
                    Map.entry("Colchón", 1),
                    Map.entry("Consola", 1),
                    Map.entry("Cámara", 1),
                    Map.entry("Decoración", 1),
                    Map.entry("Depósito de Jardín", 1),
                    Map.entry("Diluyente", 1),
                    Map.entry("Escalera", 1),
                    Map.entry("Gazebo y Sombrilla", 1),
                    Map.entry("Grifería", 1),
                    Map.entry("Heladera", 2),
                    Map.entry("Herramienta Manual", 1),
                    Map.entry("Iluminación", 1),
                    Map.entry("Impermeabilizante", 1),
                    Map.entry("Lavarropas", 3),
                    Map.entry("Lija", 1),
                    Map.entry("Mesa", 4),
                    Map.entry("Moldura", 1),
                    Map.entry("Monitor", 1),
                    Map.entry("Mueble", 1),
                    Map.entry("Mueble de Exterior", 3),
                    Map.entry("Parrilla", 1),
                    Map.entry("Pequeño Electrodoméstico", 12),
                    Map.entry("Pileta", 1),
                    Map.entry("Pintura", 8),
                    Map.entry("Piso", 2),
                    Map.entry("Protección Personal", 1),
                    Map.entry("Químico de Pileta", 1),
                    Map.entry("Reposera y Hamaca", 1),
                    Map.entry("Revestimiento", 1),
                    Map.entry("Silla", 3),
                    Map.entry("Sillón", 1),
                    Map.entry("TV y Audio", 2),
                    Map.entry("Ventilador", 1),
                    Map.entry("Wall Panel", 1)));
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

                st.execute(DocumentedRollback.sqlFor("V108"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V108SeedPrestigioTest.class.getResourceAsStream(MIGRACION))) {
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
