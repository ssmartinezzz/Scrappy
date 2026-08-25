package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same contract as the other {@code V*RollbackRoundTripTest}: an applied
 * migration is byte-frozen, so its rollback lives in {@code docs/DATABASE.md}
 * and this test runs <b>that exact block</b> against the real schema, inside a
 * transaction that always reverts.
 *
 * <p>{@code V26} is the first rollback in this change that is not total, and the
 * interesting case is the one the SQL has to handle rather than the columns it
 * drops. Restoring {@code PRIMARY KEY (url)} is impossible while two different
 * owners hold the same url, so the block de-duplicates first and keeps the
 * older row. A rollback that had merely been written and never executed would
 * look correct and fail exactly there, on the only dataset where it matters.</p>
 */
@Epic("Persistence")
@Feature("Account schema")
@Story("V26 rollback restores favoritos' natural key after de-duplicating by url")
@DisplayName("V26 migration — the documented rollback actually runs")
class V26RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("rolling back drops the five identity tables")
    void rollbackDropsTheIdentityTables() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String tabla : new String[]{
                        "usuario", "rol", "usuario_rol", "refresh_token", "password_reset_token"}) {
                    assertThat(tablaExiste(st, tabla)).as("%s exists before rollback", tabla).isTrue();
                }

                st.execute(DocumentedRollback.sqlFor("V26"));

                for (String tabla : new String[]{
                        "usuario", "rol", "usuario_rol", "refresh_token", "password_reset_token"}) {
                    assertThat(tablaExiste(st, tabla)).as("%s is gone after rollback", tabla).isFalse();
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("rolling back removes the owner column from the four personal tables")
    void rollbackDropsTheOwnerColumns() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V26"));

                for (String tabla : new String[]{
                        "favoritos", "saved_outfits", "outfit_feedback_item", "categoria_dismiss"}) {
                    assertThat(columnaExiste(st, tabla, "usuario_id"))
                            .as("%s.usuario_id is gone after rollback", tabla)
                            .isFalse();
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("favoritos returns to PRIMARY KEY (url), de-duplicating two owners of the same url")
    void rollbackRestoresTheNaturalKeyAndKeepsTheOlderRow() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrarProducto(st, "https://site.com/rollback-compartido");
                sembrarProducto(st, "https://site.com/rollback-propio");
                st.execute("INSERT INTO usuario (username, password_hash) VALUES ('ana', 'x'), ('beto', 'x')");

                // El caso que hace falla el rollback ingenuo: la misma url, dos dueños.
                st.execute("INSERT INTO favoritos (url, sitio, nombre, usuario_id) "
                        + "SELECT 'https://site.com/rollback-compartido', 'Sitio', 'de ana', id "
                        + "FROM usuario WHERE username = 'ana'");
                st.execute("INSERT INTO favoritos (url, sitio, nombre, usuario_id) "
                        + "SELECT 'https://site.com/rollback-compartido', 'Sitio', 'de beto', id "
                        + "FROM usuario WHERE username = 'beto'");
                // Y una url que sólo tiene un dueño: no debe perderse nada de ella.
                st.execute("INSERT INTO favoritos (url, sitio, nombre, usuario_id) "
                        + "SELECT 'https://site.com/rollback-propio', 'Sitio', 'solo de ana', id "
                        + "FROM usuario WHERE username = 'ana'");

                assertThat(contarFavoritos(st)).isEqualTo(3);

                st.execute(DocumentedRollback.sqlFor("V26"));

                assertThat(columnasDeLaPk(st, "favoritos"))
                        .as("the natural key is restored")
                        .containsExactly("url");
                assertThat(contarFavoritos(st))
                        .as("one row per url survives — the duplicate pair collapses, the lone row does not")
                        .isEqualTo(2);
                assertThat(nombreDe(st, "https://site.com/rollback-compartido"))
                        .as("the surviving row is the older one, as the documented block states")
                        .isEqualTo("de ana");
                assertThat(nombreDe(st, "https://site.com/rollback-propio"))
                        .as("a url with a single owner is untouched by the de-duplication")
                        .isEqualTo("solo de ana");
            } finally {
                c.rollback();
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void sembrarProducto(Statement st, String url) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                + "ON CONFLICT DO NOTHING");
        st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                + "VALUES ('" + url + "', 'Sitio', 'P', 100, true, now(), now())");
    }

    private static boolean tablaExiste(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name='" + tabla + "'")) {
            return rs.next();
        }
    }

    private static boolean columnaExiste(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + tabla + "' "
                        + "AND column_name='" + columna + "'")) {
            return rs.next();
        }
    }

    private static java.util.List<String> columnasDeLaPk(Statement st, String tabla) throws Exception {
        java.util.List<String> columnas = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT kcu.column_name "
                        + "FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON kcu.constraint_name = tc.constraint_name "
                        + "WHERE tc.table_schema='public' AND tc.table_name='" + tabla + "' "
                        + "  AND tc.constraint_type='PRIMARY KEY'")) {
            while (rs.next()) {
                columnas.add(rs.getString(1));
            }
        }
        return columnas;
    }

    private static int contarFavoritos(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM favoritos")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static String nombreDe(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT nombre FROM favoritos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("la fila de %s sobrevivió", url).isTrue();
            return rs.getString(1);
        }
    }
}
