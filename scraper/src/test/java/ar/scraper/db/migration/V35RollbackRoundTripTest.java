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
 * {@code V35}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 *
 * <p>Covers T4's objects ({@code preferencia_armador}, {@code gama},
 * {@code saved_pcs.gama_id}) and T5's ({@code producto_tech_specs} and its
 * six lookups: {@code socket}, {@code ddr}, {@code form_factor},
 * {@code tipo_memoria}, {@code certificacion}, {@code tipo_almacenamiento}).</p>
 */
@DisplayName("V35 migration — the documented rollback actually runs, and is contained")
class V35RollbackRoundTripTest extends PostgresTestBase {

    private static final String[] TABLAS_DE_V35 = {
            "preferencia_armador", "gama",
            "producto_tech_specs", "socket", "ddr", "form_factor",
            "tipo_memoria", "certificacion", "tipo_almacenamiento"
    };

    @Test
    @DisplayName("Rolling back drops preferencia_armador, gama, producto_tech_specs and its six "
            + "lookups, and the saved_pcs column, leaving the rest of the schema standing")
    void rollbackDropsOnlyItsOwnObjects() throws Exception {
        sembrarUnaPreferencia();
        sembrarUnasSpecs();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V35"));

                for (String tabla : TABLAS_DE_V35) {
                    assertThat(existeTabla(st, tabla)).as(tabla + " should be dropped").isFalse();
                }

                assertThat(existeTabla(st, "saved_pcs")).isTrue();
                assertThat(existeColumna(st, "saved_pcs", "gama_id")).isFalse();

                assertThat(existeTabla(st, "productos")).isTrue();
                assertThat(existeTabla(st, "usuario")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("producto_tech_specs drops before every lookup it references and before gama, "
            + "preferencia_armador and the saved_pcs column drop before gama too, and there is "
            + "no CASCADE doing the work")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V35");

        int idxSpecs = sql.indexOf("DROP TABLE producto_tech_specs;");
        assertThat(idxSpecs).as("producto_tech_specs must be dropped").isNotNegative();

        for (String lookup : new String[] {"socket", "ddr", "form_factor", "tipo_memoria",
                "certificacion", "tipo_almacenamiento", "gama"}) {
            assertThat(idxSpecs)
                    .as("producto_tech_specs references " + lookup + "(id): dropping it first "
                            + "fails while producto_tech_specs still references it")
                    .isLessThan(sql.indexOf("DROP TABLE " + lookup + ";"));
        }

        assertThat(sql.indexOf("preferencia_armador"))
                .as("preferencia_armador.gama_id references gama(id): dropping gama first "
                        + "fails while preferencia_armador still references it")
                .isLessThan(sql.indexOf("DROP TABLE gama;"));
        assertThat(sql.indexOf("DROP COLUMN gama_id"))
                .as("saved_pcs.gama_id references gama(id) too")
                .isLessThan(sql.indexOf("DROP TABLE gama;"));
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnaPreferencia() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO preferencia_armador (usuario_id, gama_id, con_gpu)
                    SELECT '%s', g.id, false FROM gama g WHERE g.nombre = 'ALTA'
                    """.formatted(usuario));
        }
    }

    private void sembrarUnasSpecs() throws Exception {
        String url = "https://v35-rollback.test/cpu-1";
        try (Connection c = dataSource().getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) "
                            + "VALUES (?, 'Sitio', 'Producto', 1000, 'tecnologia')")) {
                ps.setString(1, url);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO producto_tech_specs (url, socket_id, gama_id)
                        SELECT '%s', s.id, g.id
                        FROM socket s, gama g
                        WHERE s.nombre = 'AM5' AND g.nombre = 'ALTA'
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
