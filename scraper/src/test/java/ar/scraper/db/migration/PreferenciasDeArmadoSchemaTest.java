package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V36} — the schema invariants of the three new lookups
 * ({@code marca_chip}, {@code chipset_tier}, {@code tipo_cooler}) and the
 * columns they back on {@code preferencia_armador} and
 * {@code producto_tech_specs}. Molde: {@link PreferenciaArmadorSchemaTest}
 * (V35).
 *
 * <p>Every negative case asserts the exact SQLState, never bare
 * {@code SQLException}: an insert against a column that does not exist yet
 * also throws {@code SQLException}, so the looser assertion would go green
 * before the migration is even written.</p>
 */
@DisplayName("V36 migration — preferencias de armado schema constraints")
class PreferenciasDeArmadoSchemaTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FK_VIOLATION = "23503";

    @Test
    @DisplayName("marca_chip CHECK rejects a name outside the closed vocabulary")
    void marcaChipRejectsAnInventedName() {
        assertThatThrownBy(() -> ejecutar("INSERT INTO marca_chip (nombre) VALUES ('QUALCOMM')"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("chipset_tier CHECK rejects a name outside the closed vocabulary")
    void chipsetTierRejectsAnInventedName() {
        assertThatThrownBy(() -> ejecutar("INSERT INTO chipset_tier (nombre) VALUES ('C')"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("tipo_cooler CHECK rejects a name outside the closed vocabulary, including DESCONOCIDO")
    void tipoCoolerRejectsAnInventedNameAndTheAbstentionSentinel() {
        assertThatThrownBy(() -> ejecutar("INSERT INTO tipo_cooler (nombre) VALUES ('DESCONOCIDO')"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("preferencia_armador.marca_cpu_id rejects an FK to a marca_chip id that does not exist")
    void preferenciaArmadorMarcaCpuIdRejectsAnUnknownMarcaChip() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO preferencia_armador (usuario_id, gama_id, marca_cpu_id)
                SELECT '%s', g.id, 999 FROM gama g WHERE g.nombre = 'ALTA'
                """.formatted(usuario)))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("preferencia_armador's new columns default to NULL/false and round-trip a full row")
    void preferenciaArmadorNuevasColumnasAceptanValoresYDefaults() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        assertThatCode(() -> ejecutar("""
                INSERT INTO preferencia_armador (usuario_id, gama_id)
                SELECT '%s', g.id FROM gama g WHERE g.nombre = 'MEDIA'
                """.formatted(usuario))).doesNotThrowAnyException();

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT ddr_id, marca_cpu_id, marca_gpu_id, tipo_almacenamiento_id, ram_dual, wifi
                     FROM preferencia_armador WHERE usuario_id = ?
                     """)) {
            ps.setObject(1, usuario);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("ddr_id")).isNull();
                assertThat(rs.getObject("marca_cpu_id")).isNull();
                assertThat(rs.getObject("marca_gpu_id")).isNull();
                assertThat(rs.getObject("tipo_almacenamiento_id")).isNull();
                assertThat(rs.getBoolean("ram_dual")).isFalse();
                assertThat(rs.getBoolean("wifi")).isFalse();
            }
        }
    }

    @Test
    @DisplayName("producto_tech_specs.marca_chip_id rejects an FK to a marca_chip id that does not exist")
    void productoTechSpecsMarcaChipIdRejectsAnUnknownMarcaChip() throws Exception {
        String url = insertarProducto();

        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO producto_tech_specs (url, marca_chip_id) VALUES ('%s', 999)
                """.formatted(url)))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("producto_tech_specs.wifi accepts NULL — abstention / not a Motherboard row")
    void productoTechSpecsWifiAcceptsNull() throws Exception {
        String url = insertarProducto();

        assertThatCode(() -> ejecutar(
                "INSERT INTO producto_tech_specs (url) VALUES ('" + url + "')"))
                .doesNotThrowAnyException();

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT wifi, generacion, modulos FROM producto_tech_specs WHERE url = ?")) {
            ps.setString(1, url);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                rs.getObject("wifi");
                assertThat(rs.wasNull()).isTrue();
                rs.getObject("generacion");
                assertThat(rs.wasNull()).isTrue();
                rs.getObject("modulos");
                assertThat(rs.wasNull()).isTrue();
            }
        }
    }

    private String insertarProducto() throws Exception {
        String url = "https://v36-schema.test/" + UUID.randomUUID();
        try (Connection c = dataSource().getConnection();
             Statement seed = c.createStatement()) {
            // `sitio` is never truncated (seed data outside this test's own
            // residue) but a raw INSERT INTO productos still needs a matching
            // row — molde: UnownedRowTest/DeleteProductosGlobalGuardTest.
            seed.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                    + "ON CONFLICT DO NOTHING");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) "
                            + "VALUES (?, 'Sitio', 'Producto', 1000, 'tecnologia')")) {
                ps.setString(1, url);
                ps.executeUpdate();
            }
        }
        return url;
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
