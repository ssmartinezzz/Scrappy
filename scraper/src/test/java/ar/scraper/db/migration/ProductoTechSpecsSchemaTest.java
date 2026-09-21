package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V35} (T5) — the schema invariants of the six lookups and
 * {@code producto_tech_specs}. Molde: {@code PreferenciaArmadorSchemaTest}.
 *
 * <p>Every negative case asserts the exact SQLState, never bare
 * {@code SQLException}: an insert against a table that does not exist yet
 * also throws {@code SQLException}, so the looser assertion would go green
 * before the migration is even written.</p>
 */
@DisplayName("V35 migration — producto_tech_specs and its six lookups, schema constraints")
class ProductoTechSpecsSchemaTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FK_VIOLATION     = "23503";

    private record Lookup(String tabla, Set<String> vocabulario) {}

    private static Stream<Lookup> lookups() {
        return Stream.of(
                new Lookup("socket", Set.of("AM4", "AM5", "LGA1700", "LGA1851")),
                new Lookup("ddr", Set.of("DDR3", "DDR4", "DDR5")),
                new Lookup("form_factor", Set.of("ITX", "MATX", "ATX", "EATX")),
                new Lookup("tipo_memoria", Set.of("DIMM", "SODIMM")),
                new Lookup("certificacion", Set.of("WHITE", "BRONZE", "SILVER", "GOLD", "PLATINUM", "TITANIUM")),
                new Lookup("tipo_almacenamiento", Set.of("NVME", "SSD", "HDD")));
    }

    @ParameterizedTest(name = "{0} CHECK rejects a name outside the closed vocabulary")
    @MethodSource("lookups")
    void lookupRejectsAnInventedName(Lookup lookup) {
        assertThatThrownBy(() -> ejecutar("INSERT INTO " + lookup.tabla() + " (nombre) VALUES ('INVENTADO')"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @ParameterizedTest(name = "{0} is seeded with exactly its vocabulary, no DESCONOCIDA/NINGUNA row")
    @MethodSource("lookups")
    void lookupIsSeededWithExactlyItsVocabulary(Lookup lookup) throws Exception {
        assertThat(nombresSembrados(lookup.tabla())).isEqualTo(lookup.vocabulario());
    }

    @Test
    @DisplayName("watts = 0 is rejected — 0 is TechSpecs's abstention centinela, never a stored value")
    void wattsZeroIsRejected() throws Exception {
        String url = insertarProducto("watts-cero");
        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO producto_tech_specs (url, watts) VALUES ('%s', 0)
                """.formatted(url)))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("capacidad_gb = 0 is rejected")
    void capacidadGbZeroIsRejected() throws Exception {
        String url = insertarProducto("gb-cero");
        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO producto_tech_specs (url, capacidad_gb) VALUES ('%s', 0)
                """.formatted(url)))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("velocidad_mhz = 0 is rejected")
    void velocidadMhzZeroIsRejected() throws Exception {
        String url = insertarProducto("mhz-cero");
        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO producto_tech_specs (url, velocidad_mhz) VALUES ('%s', 0)
                """.formatted(url)))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a row for a url that isn't in productos is rejected")
    void rowForUnknownUrlIsRejected() {
        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO producto_tech_specs (url) VALUES ('https://tech-specs-schema.test/no-existe')
                """))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("deleting the product cascades its producto_tech_specs row")
    void deletingTheProductCascadesItsSpecs() throws Exception {
        String url = insertarProducto("cascade");
        ejecutar("INSERT INTO producto_tech_specs (url) VALUES ('%s')".formatted(url));

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            assertThat(existeSpec(st, url)).isTrue();

            st.execute("DELETE FROM productos WHERE url = '" + url + "'");

            assertThat(existeSpec(st, url))
                    .as("an orphaned specs row would outlive the product it describes")
                    .isFalse();
        }
    }

    private String insertarProducto(String slug) throws Exception {
        String url = "https://tech-specs-schema.test/" + slug;
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO productos (url, sitio, nombre, precio, rubro) "
                             + "VALUES (?, 'Sitio', 'Producto', 1000, 'tecnologia')")) {
            ps.setString(1, url);
            ps.executeUpdate();
        }
        return url;
    }

    private Set<String> nombresSembrados(String tabla) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre FROM " + tabla)) {
            List<String> nombres = new java.util.ArrayList<>();
            while (rs.next()) {
                nombres.add(rs.getString(1));
            }
            return Set.copyOf(nombres);
        }
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static boolean existeSpec(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM producto_tech_specs WHERE url = '" + url + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
