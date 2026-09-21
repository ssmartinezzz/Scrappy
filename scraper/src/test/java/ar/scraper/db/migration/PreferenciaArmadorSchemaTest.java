package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V35} — the schema invariants of {@code gama} and
 * {@code preferencia_armador}. Molde: {@code SchemaConstraintsTest} (V26).
 *
 * <p>Every negative case asserts the exact SQLState, never bare
 * {@code SQLException}: an insert against a table that does not exist yet
 * also throws {@code SQLException}, so the looser assertion would go green
 * before the migration is even written.</p>
 */
@DisplayName("V35 migration — gama and preferencia_armador schema constraints")
class PreferenciaArmadorSchemaTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION  = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION      = "23503";

    @Test
    @DisplayName("gama CHECK rejects a name outside the closed vocabulary")
    void gamaRejectsAnInventedName() {
        assertThatThrownBy(() -> ejecutar("INSERT INTO gama (nombre) VALUES ('ULTRA')"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a second row for the same usuario_id violates the partial unique index")
    void unaSegundaFilaParaElMismoUsuarioViolaElIndiceParcial() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        insertarPreferenciaCruda(usuario, "MEDIA");

        assertThatThrownBy(() -> insertarPreferenciaCruda(usuario, "ALTA"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("deleting the usuario cascades its preferencia_armador row")
    void borrarElUsuarioCascadeaSuPreferencia() throws Exception {
        UUID usuario = UsuarioDePrueba.crear(dataSource(), "borrable");
        insertarPreferenciaCruda(usuario, "ECONOMICA");

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            assertThat(contar(st, usuario)).isEqualTo(1);

            st.execute("DELETE FROM usuario WHERE id = '" + usuario + "'");

            assertThat(contar(st, usuario))
                    .as("a dangling preference row would outlive the account it belongs to")
                    .isZero();
        }
    }

    @Test
    @DisplayName("saved_pcs.gama_id accepts NULL")
    void savedPcsGamaIdAcceptsNull() {
        assertThatCode(() -> ejecutar("""
                INSERT INTO saved_pcs (nombre, presupuesto, con_gpu, total_estimado)
                VALUES ('Sin gama', 500000, false, 250000)
                """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("saved_pcs.gama_id rejects an FK to a gama id that does not exist")
    void savedPcsGamaIdRejectsAnUnknownGama() {
        assertThatThrownBy(() -> ejecutar("""
                INSERT INTO saved_pcs (nombre, presupuesto, con_gpu, total_estimado, gama_id)
                VALUES ('Gama inventada', 500000, false, 250000, 999)
                """))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    private void insertarPreferenciaCruda(UUID usuarioId, String gamaNombre) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO preferencia_armador (usuario_id, gama_id, con_gpu)
                    SELECT '%s', g.id, false FROM gama g WHERE g.nombre = '%s'
                    """.formatted(usuarioId, gamaNombre));
        }
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static int contar(Statement st, UUID usuarioId) throws Exception {
        try (var rs = st.executeQuery(
                "SELECT count(*) FROM preferencia_armador WHERE usuario_id = '" + usuarioId + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
