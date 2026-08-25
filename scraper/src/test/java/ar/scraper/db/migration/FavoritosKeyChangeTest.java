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
 * user-accounts-and-roles, slice 1 — {@code favoritos} stops being keyed by
 * {@code url} alone, because two people may now favourite the same product.
 *
 * <p>The interesting part is not the new {@code UNIQUE (usuario_id, url)}: it
 * is the <b>partial index for the transient window</b>. Until adoption runs and
 * the application learns to scope by owner, every row is still written with
 * {@code usuario_id = NULL} — and a plain {@code UNIQUE (usuario_id, url)}
 * treats NULLs as distinct, so the same URL could be inserted twice. That is a
 * silent regression against today's {@code url PRIMARY KEY}, introduced by the
 * very migration meant to be additive.</p>
 *
 * <p>{@code uq_fav_unowned_url} preserves the current guarantee for exactly the
 * window in which it can be violated, and stops constraining anything the
 * moment a row has a real owner. Postgres 15's {@code NULLS NOT DISTINCT} would
 * say this in one clause, but the portable {@code _tools/pgsql} version is
 * unpinned, and a partial index works on every version we might meet.</p>
 */
@Epic("Persistence")
@Feature("Data ownership")
@Story("V26 — favoritos surrogate PK, UNIQUE(usuario_id,url), partial index over the unowned window")
@DisplayName("V26 migration — favoritos key restructuring")
class FavoritosKeyChangeTest extends PostgresTestBase {

    /* Asserted instead of the exception class: a missing column throws
     * SQLException too, so the class alone would go green before V26 exists. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Test
    @DisplayName("favoritos gains a surrogate identity primary key")
    void favoritosGainsASurrogatePrimaryKey() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT kcu.column_name "
                             + "FROM information_schema.table_constraints tc "
                             + "JOIN information_schema.key_column_usage kcu "
                             + "  ON kcu.constraint_name = tc.constraint_name "
                             + "WHERE tc.table_schema='public' AND tc.table_name='favoritos' "
                             + "  AND tc.constraint_type='PRIMARY KEY'")) {
            assertThat(rs.next()).as("favoritos still has a primary key").isTrue();
            assertThat(rs.getString(1))
                    .as("the same surrogate shape V1 already uses for every other child table")
                    .isEqualTo("id");
            assertThat(rs.next()).as("the PK is a single surrogate column, not a composite").isFalse();
        }
    }

    @Test
    @DisplayName("two unowned rows for the same url — the second is rejected")
    void theUnownedWindowKeepsTodaysOneRowPerUrlGuarantee() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrarProducto(st, "https://site.com/fav-dup");

                st.execute("INSERT INTO favoritos (url, sitio, usuario_id) "
                        + "VALUES ('https://site.com/fav-dup', 'Sitio', NULL)");

                assertThatThrownBy(() -> st.execute("INSERT INTO favoritos (url, sitio, usuario_id) "
                        + "VALUES ('https://site.com/fav-dup', 'Sitio', NULL)"))
                        .as("without the partial index this second insert succeeds, and today's "
                                + "url-PRIMARY-KEY guarantee is silently lost during the unowned window")
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo(UNIQUE_VIOLATION);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("two different owners may favourite the same url")
    void twoOwnersMayFavouriteTheSameUrl() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrarProducto(st, "https://site.com/fav-compartido");
                st.execute("INSERT INTO usuario (username, password_hash) VALUES ('ana', 'x'), ('beto', 'x')");

                assertThatCode(() -> {
                    st.execute("INSERT INTO favoritos (url, sitio, usuario_id) "
                            + "SELECT 'https://site.com/fav-compartido', 'Sitio', id "
                            + "FROM usuario WHERE username = 'ana'");
                    st.execute("INSERT INTO favoritos (url, sitio, usuario_id) "
                            + "SELECT 'https://site.com/fav-compartido', 'Sitio', id "
                            + "FROM usuario WHERE username = 'beto'");
                }).as("this is the whole reason the key had to change").doesNotThrowAnyException();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the same owner cannot favourite the same url twice")
    void oneOwnerCannotFavouriteTheSameUrlTwice() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrarProducto(st, "https://site.com/fav-mio");
                st.execute("INSERT INTO usuario (username, password_hash) VALUES ('ana', 'x')");
                st.execute("INSERT INTO favoritos (url, sitio, usuario_id) "
                        + "SELECT 'https://site.com/fav-mio', 'Sitio', id FROM usuario WHERE username = 'ana'");

                assertThatThrownBy(() -> st.execute("INSERT INTO favoritos (url, sitio, usuario_id) "
                        + "SELECT 'https://site.com/fav-mio', 'Sitio', id FROM usuario WHERE username = 'ana'"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo(UNIQUE_VIOLATION);
            } finally {
                c.rollback();
            }
        }
    }

    /** fk_favoritos_url (V4) and fk_productos_sitio (V23) both have to be satisfied first. */
    private static void sembrarProducto(Statement st, String url) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                + "ON CONFLICT DO NOTHING");
        st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                + "VALUES ('" + url + "', 'Sitio', 'P', 100, true, now(), now())");
    }
}
