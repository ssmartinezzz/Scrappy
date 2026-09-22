package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V36}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 *
 * <p>Covers T5's objects: the three new lookups ({@code marca_chip},
 * {@code chipset_tier}, {@code tipo_cooler}) and the columns they back on
 * {@code preferencia_armador} and {@code producto_tech_specs}. V35's own
 * objects (both tables themselves, and its six lookups) are untouched by
 * this rollback — dropping only the columns V36 added leaves them standing,
 * same as {@link V35RollbackRoundTripTest} verifies for V34's.</p>
 */
@DisplayName("V36 migration — the documented rollback actually runs, and is contained")
class V36RollbackRoundTripTest extends PostgresTestBase {

    private static final String[] TABLAS_DE_V36 = {"marca_chip", "chipset_tier", "tipo_cooler"};

    @Test
    @DisplayName("Rolling back drops the three new lookups and the columns they back, leaving "
            + "preferencia_armador, producto_tech_specs and the rest of the schema standing")
    void rollbackDropsOnlyItsOwnObjects() throws Exception {
        sembrarUnaPreferencia();
        sembrarUnasSpecs();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V36"));

                for (String tabla : TABLAS_DE_V36) {
                    assertThat(existeTabla(st, tabla)).as(tabla + " should be dropped").isFalse();
                }

                for (String columna : new String[] {"ddr_id", "marca_cpu_id", "marca_gpu_id",
                        "tipo_almacenamiento_id", "ram_dual", "wifi"}) {
                    assertThat(existeColumna(st, "preferencia_armador", columna))
                            .as("preferencia_armador." + columna + " should be dropped").isFalse();
                }
                for (String columna : new String[] {"marca_chip_id", "chipset_tier_id", "tipo_cooler_id",
                        "generacion", "modulos", "wifi"}) {
                    assertThat(existeColumna(st, "producto_tech_specs", columna))
                            .as("producto_tech_specs." + columna + " should be dropped").isFalse();
                }

                assertThat(existeTabla(st, "preferencia_armador")).isTrue();
                assertThat(existeTabla(st, "producto_tech_specs")).isTrue();
                assertThat(existeTabla(st, "gama")).isTrue();
                assertThat(existeTabla(st, "socket")).isTrue();
                assertThat(existeTabla(st, "ddr")).isTrue();
                assertThat(existeTabla(st, "tipo_almacenamiento")).isTrue();
                assertThat(existeTabla(st, "productos")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Every column referencing a V36 lookup drops before that lookup, and there is no CASCADE")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V36");

        int idxSpecsCols = sql.indexOf("marca_chip_id");
        int idxPrefCols = sql.indexOf("marca_cpu_id");
        assertThat(idxSpecsCols).as("producto_tech_specs.marca_chip_id must be dropped").isNotNegative();
        assertThat(idxPrefCols).as("preferencia_armador.marca_cpu_id must be dropped").isNotNegative();

        for (String lookup : TABLAS_DE_V36) {
            int idxDrop = sql.indexOf("DROP TABLE " + lookup + ";");
            assertThat(idxDrop).as("DROP TABLE " + lookup + " must be present").isNotNegative();
            assertThat(idxSpecsCols)
                    .as("producto_tech_specs' columns reference " + lookup
                            + "(id): dropping it first fails while they still reference it")
                    .isLessThan(idxDrop);
            assertThat(idxPrefCols)
                    .as("preferencia_armador's columns reference " + lookup
                            + "(id): dropping it first fails while they still reference it")
                    .isLessThan(idxDrop);
        }
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnaPreferencia() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO preferencia_armador (usuario_id, gama_id, marca_cpu_id)
                    SELECT '%s', g.id, m.id FROM gama g, marca_chip m
                    WHERE g.nombre = 'ALTA' AND m.nombre = 'AMD'
                    """.formatted(usuario));
        }
    }

    private void sembrarUnasSpecs() throws Exception {
        String url = "https://v36-rollback.test/mother-1";
        try (Connection c = dataSource().getConnection()) {
            try (Statement seed = c.createStatement()) {
                // molde: UnownedRowTest/DeleteProductosGlobalGuardTest — `sitio`
                // is seed data, never truncated, but a raw INSERT INTO productos
                // still needs a matching row present.
                seed.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                        + "ON CONFLICT DO NOTHING");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) "
                            + "VALUES (?, 'Sitio', 'Producto', 1000, 'tecnologia')")) {
                ps.setString(1, url);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO producto_tech_specs (url, marca_chip_id, chipset_tier_id, tipo_cooler_id, wifi)
                        SELECT '%s', m.id, t.id, c.id, true
                        FROM marca_chip m, chipset_tier t, tipo_cooler c
                        WHERE m.nombre = 'AMD' AND t.nombre = 'X_Z' AND c.nombre = 'AIRE'
                        """.formatted(url));
            }
        }
    }

    private static boolean existeTabla(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = '%s'
                """.formatted(tabla))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static boolean existeColumna(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '%s' AND column_name = '%s'
                """.formatted(tabla, columna))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
