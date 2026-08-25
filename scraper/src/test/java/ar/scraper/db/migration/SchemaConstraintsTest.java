package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user-accounts-and-roles, slice 1 — the invariants {@code V26} encodes in the
 * schema itself rather than in application code.
 *
 * <p>Three of these are deliberately DB-level rather than service-level, and
 * the reason is the same in each case: application code can be bypassed by a
 * second writer, a migration, or a {@code psql} session, and every one of
 * these invariants is a security property.</p>
 *
 * <ul>
 *   <li>The {@code rol} vocabulary is closed by CHECK so no code path can
 *       invent a third role — the authorization matrix only knows two.</li>
 *   <li>{@code CHECK (NOT es_servicio OR email IS NULL)} makes
 *       service-account non-resettability a <b>schema invariant</b>: the
 *       password-reset flow looks accounts up by email, and an account with no
 *       email cannot be found by it. That is stronger than an
 *       {@code if (es_servicio) return;} somebody can forget.</li>
 *   <li>The CASCADEs mean deleting a user cannot leave a live refresh token or
 *       a dangling role grant behind.</li>
 * </ul>
 *
 * <p>Real Postgres enforcement via {@link PostgresTestBase}, no mocks. The
 * negative cases assert the exact <b>SQLState</b>, not just {@code SQLException}:
 * an insert into a table that does not exist yet throws {@code SQLException}
 * too, so the looser assertion goes green before the migration is written —
 * a test passing for the wrong reason, which is worse than no test.</p>
 */
@Epic("Persistence")
@Feature("Account schema")
@Story("V26 — closed role vocabulary, email uniqueness, service-account invariant, cascades")
@DisplayName("V26 migration — account schema constraints")
class SchemaConstraintsTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION  = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    // ── 1.1 · rol vocabulary is closed ───────────────────────────────────────

    @Test
    @DisplayName("rol CHECK accepts ADMIN and VIEWER")
    void rolAcceptsTheClosedVocabulary() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT nombre FROM rol ORDER BY nombre")) {
                assertThat(leerColumna(rs))
                        .as("V26 seeds exactly the two roles the matrix knows")
                        .containsExactly("ADMIN", "VIEWER");
            }
        }
    }

    @Test
    @DisplayName("rol CHECK rejects a role outside the closed vocabulary")
    void rolRejectsAnInventedRole() {
        assertThatThrownBy(() -> ejecutar("INSERT INTO rol (nombre) VALUES ('SUPERADMIN')"))
                .as("a third role would be invisible to the authorization matrix")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    // ── 1.2 · email is unique only when present ──────────────────────────────

    @Test
    @DisplayName("two accounts with email IS NULL both insert")
    void emailIsUniqueOnlyWhenPresent() {
        assertThatCode(() -> {
            insertarUsuario("sin-email-1", null);
            insertarUsuario("sin-email-2", null);
        }).as("Postgres UNIQUE treats NULLs as distinct — both service and bootstrap accounts rely on it")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a duplicate non-null email is rejected")
    void duplicateNonNullEmailIsRejected() throws Exception {
        insertarUsuario("con-email-1", "dup@example.com");
        assertThatThrownBy(() -> insertarUsuario("con-email-2", "dup@example.com"))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("email must be stored lowercase")
    void emailMustBeLowercase() {
        assertThatThrownBy(() -> insertarUsuario("mixto", "Mixed@Example.com"))
                .as("case-varying duplicates would defeat the UNIQUE constraint")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    // ── 1.3 · service accounts have no email, enforced by the schema ─────────

    @Test
    @DisplayName("a service account with an email is rejected by CHECK")
    void serviceAccountCannotHaveAnEmail() {
        assertThatThrownBy(() -> ejecutar(
                "INSERT INTO usuario (username, email, password_hash, es_servicio) "
                        + "VALUES ('cli', 'cli@example.com', 'x', TRUE)"))
                .as("non-resettability of service accounts is a schema invariant, not application logic")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a service account with no email is accepted")
    void serviceAccountWithoutEmailIsAccepted() {
        assertThatCode(() -> ejecutar(
                "INSERT INTO usuario (username, email, password_hash, es_servicio) "
                        + "VALUES ('cli', NULL, 'x', TRUE)"))
                .doesNotThrowAnyException();
    }

    // ── 1.4 · deleting a user cascades ───────────────────────────────────────

    @Test
    @DisplayName("deleting a usuario cascades usuario_rol, refresh_token and password_reset_token")
    void deletingAUserCascadesItsRolesAndTokens() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO usuario (username, password_hash) VALUES ('borrable', 'x')");
                st.execute("INSERT INTO usuario_rol (usuario_id, rol_id) "
                        + "SELECT u.id, r.id FROM usuario u, rol r "
                        + "WHERE u.username = 'borrable' AND r.nombre = 'VIEWER'");
                st.execute("INSERT INTO refresh_token (token_hash, family_id, usuario_id, expires_at) "
                        + "SELECT 'hash-refresh', gen_random_uuid(), u.id, now() + interval '14 days' "
                        + "FROM usuario u WHERE u.username = 'borrable'");
                st.execute("INSERT INTO password_reset_token (token_hash, usuario_id, expires_at) "
                        + "SELECT 'hash-reset', u.id, now() + interval '30 minutes' "
                        + "FROM usuario u WHERE u.username = 'borrable'");

                assertThat(contar(st, "usuario_rol")).isEqualTo(1);
                assertThat(contar(st, "refresh_token")).isEqualTo(1);
                assertThat(contar(st, "password_reset_token")).isEqualTo(1);

                st.execute("DELETE FROM usuario WHERE username = 'borrable'");

                assertThat(contar(st, "usuario_rol"))
                        .as("a dangling role grant would outlive the account it grants to")
                        .isZero();
                assertThat(contar(st, "refresh_token"))
                        .as("a live refresh token outliving its account is a session that cannot be revoked")
                        .isZero();
                assertThat(contar(st, "password_reset_token")).isZero();
            } finally {
                c.rollback();
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void insertarUsuario(String username, String email) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            String emailLiteral = email == null ? "NULL" : "'" + email + "'";
            st.execute("INSERT INTO usuario (username, email, password_hash) "
                    + "VALUES ('" + username + "', " + emailLiteral + ", 'x')");
        }
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static int contar(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tabla)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static java.util.List<String> leerColumna(ResultSet rs) throws Exception {
        java.util.List<String> valores = new java.util.ArrayList<>();
        while (rs.next()) {
            valores.add(rs.getString(1));
        }
        return valores;
    }
}
