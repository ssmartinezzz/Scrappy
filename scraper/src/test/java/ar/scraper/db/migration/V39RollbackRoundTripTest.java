package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V39}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 *
 * <p>Covers T6's objects: the {@code uso} lookup and the column it adds to
 * {@code preferencia_armador}. It is the newest migration in the chain, so —
 * unlike {@code V36}/{@code V37} — nothing later hangs a reference off it
 * that needs reverting first.</p>
 */
@DisplayName("V39 migration — the documented rollback actually runs, and is contained")
class V39RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops uso and preferencia_armador.uso_id, leaving the rest standing")
    void rollbackDropsOnlyItsOwnObjects() throws Exception {
        sembrarUnaPreferenciaHomelab();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V39"));

                assertThat(existeTabla(st, "uso")).isFalse();
                assertThat(existeColumna(st, "preferencia_armador", "uso_id")).isFalse();

                // Lo de V35/V36/V37/V38 sigue en pie.
                assertThat(existeTabla(st, "preferencia_armador")).isTrue();
                assertThat(existeTabla(st, "tamanio_gabinete")).isTrue();
                assertThat(existeTabla(st, "tipo_cooler")).isTrue();
                assertThat(existeColumna(st, "preferencia_armador", "gama_id")).isTrue();
                assertThat(existeColumna(st, "preferencia_armador", "watts_minimos")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("uso siembra las DOS filas: GAMING no es un centinela, tiene fila propia igual que HOMELAB")
    void usoSiembraGamingYHomelab() {
        assertThatCode(() -> ejecutar("""
                INSERT INTO preferencia_armador (usuario_id, gama_id, uso_id)
                SELECT NULL, g.id, u.id FROM gama g, uso u WHERE g.nombre = 'MEDIA' AND u.nombre = 'GAMING'
                """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el CHECK de uso rechaza un nombre inventado — dominio cerrado")
    void elCheckDeUsoRechazaUnNombreInventado() {
        assertThatThrownBy(() -> ejecutar("INSERT INTO uso (nombre) VALUES ('SERVIDOR')"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo("23514");
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("The block drops the referencing column before the lookup, and there is no CASCADE")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V39");

        int idxDropColumn = sql.indexOf("DROP COLUMN uso_id");
        int idxDropTabla = sql.indexOf("DROP TABLE uso;");

        assertThat(idxDropColumn).isNotNegative();
        assertThat(idxDropTabla)
                .as("preferencia_armador.uso_id references uso(id): it must drop before the table")
                .isGreaterThan(idxDropColumn);
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnaPreferenciaHomelab() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO preferencia_armador (usuario_id, gama_id, uso_id)
                    SELECT '%s', g.id, u.id FROM gama g, uso u
                    WHERE g.nombre = 'ALTA' AND u.nombre = 'HOMELAB'
                    """.formatted(usuario));
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
