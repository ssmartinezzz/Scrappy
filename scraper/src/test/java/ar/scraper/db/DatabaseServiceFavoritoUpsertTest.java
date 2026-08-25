package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 1 — regression guard for the write path
 * {@code V26} would otherwise break in silence.
 *
 * <p>{@code guardarFavorito} upserts with {@code ON CONFLICT (url)}. That
 * inference resolves against {@code favoritos}' {@code url PRIMARY KEY} today,
 * and {@code V26} drops exactly that key. Postgres then refuses the whole
 * statement — not only the conflicting re-save but the very first insert —
 * because there is no unique index matching the inference.</p>
 *
 * <p>The failure would be invisible: {@code FavoritosRepository} logs and
 * swallows, so a broken favourite reads back as "no favourites" rather than as
 * an error. A partial unique index cannot be inferred implicitly either — the
 * clause has to repeat the index's own {@code WHERE}. This test pins both the
 * first save and the re-save, since the two fail together and one of them looks
 * like ordinary emptiness.</p>
 */
@Epic("Persistence")
@Feature("Favoritos")
@Story("guardarFavorito survives the V26 key change")
@DisplayName("DatabaseService — favoritos upsert after the key change")
class DatabaseServiceFavoritoUpsertTest extends PostgresTestBase {

    private static final String URL = "https://site.com/fav-upsert";

    private DatabaseService db;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseService(dataSource());
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('Freres', 'freres', 'shopify', false, NULL, 'historico') "
                    + "ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                    + "VALUES ('" + URL + "', 'Freres', 'P', 100, true, now(), now())");
        }
    }

    @Test
    @DisplayName("a favourite is persisted, not swallowed")
    void savingAFavouritePersistsIt() {
        db.guardarFavorito(yo(), URL, "Freres", "Producto");

        assertThat(db.listarFavoritos(yo()))
                .as("the repository logs and swallows, so a broken write reads back as emptiness")
                .hasSize(1);
    }

    @Test
    @DisplayName("saving the same url twice updates in place instead of duplicating")
    void savingTheSameUrlTwiceUpdatesInPlace() {
        db.guardarFavorito(yo(), URL, "Freres", "Nombre viejo");
        db.guardarFavorito(yo(), URL, "Freres", "Nombre nuevo");

        assertThat(db.listarFavoritos(yo())).hasSize(1);
        assertThat(db.listarFavoritos(yo()).get(0).get("nombre")).isEqualTo("Nombre nuevo");
    }

    /**
     * The owner every personal read and write is scoped by since slice 8.
     *
     * <p>A method rather than a field: {@code PostgresTestBase} truncates between
     * tests, so a cached id would point at a row that no longer exists. Seeding is
     * idempotent, so calling it repeatedly costs three cheap queries and is always
     * correct.</p>
     */
    private UUID yo() {
        return UsuarioDePrueba.yo(dataSource());
    }
}
