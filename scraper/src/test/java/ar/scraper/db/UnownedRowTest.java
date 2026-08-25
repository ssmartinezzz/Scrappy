package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 8 — what a row with no owner does now.
 *
 * <p>{@code WHERE usuario_id = :subject} is never true for {@code NULL}, so an
 * unadopted row is invisible to <b>everybody</b> rather than visible to
 * everybody. That is the safe direction and it was chosen deliberately: an
 * invisible row can be adopted and reappears, whereas a leaked one cannot be
 * un-leaked.</p>
 *
 * <p>It is still somebody's favourites disappearing, though, which is why
 * {@link UnownedRowsWarner} exists — the rows are safe <i>and</i> the operator
 * is told, with the SQL to fix it. Silence would be the actual failure here.</p>
 */
@Epic("Persistence")
@Feature("Data ownership")
@Story("An unadopted row is invisible to everybody, and startup says so")
@DisplayName("Ownership — rows with no owner")
class UnownedRowTest extends PostgresTestBase {

    private DatabaseService db;
    private UsuarioRepository usuarios;
    private UUID ana;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseService(dataSource());
        usuarios = new UsuarioRepository(dataSource());
        usuarios.crear("ana", null, "$argon2id$x", false);
        ana = usuarios.buscarActivaPorUsername("ana").orElseThrow().id();

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                    + "ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                    + "VALUES ('https://site.com/huerfano', 'Sitio', 'P', 100, true, now(), now())");
            // The state an installation is in between the migration and adoption.
            st.execute("INSERT INTO favoritos (url, sitio, nombre, added_at) "
                    + "VALUES ('https://site.com/huerfano', 'Sitio', 'Sin dueño', now())");
            st.execute("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('remeras', now())");
        }
    }

    @Test
    @DisplayName("an unowned row is invisible to its would-be owner and to everybody else")
    void anUnownedRowIsInvisibleToEveryone() {
        assertThat(db.listarFavoritos(ana))
                .as("NULL = anything is never true, so the row matches nobody rather than everybody")
                .isEmpty();
        assertThat(db.obtenerCategoriaDismiss(ana)).isEmpty();
    }

    @Test
    @DisplayName("the row is not lost — adopting it brings it straight back")
    void adoptingItBringsItBack() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("UPDATE favoritos SET usuario_id = '" + ana + "' WHERE usuario_id IS NULL");
        }

        assertThat(db.listarFavoritos(ana))
                .as("invisible is recoverable; leaked would not have been")
                .hasSize(1);
    }

    @Test
    @DisplayName("startup counts the unowned rows per table")
    void theWarnerCountsThemPerTable() {
        Map<String, Integer> huerfanas = new UnownedRowsWarner(dataSource()).contar();

        assertThat(huerfanas)
                .as("silence here would leave the operator staring at an empty favourites list "
                        + "with nothing to go on")
                .containsEntry("favoritos", 1)
                .containsEntry("categoria_dismiss", 1);
    }

    @Test
    @DisplayName("with every row owned it counts nothing and stays quiet")
    void theWarnerIsSilentWhenEverythingIsOwned() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("UPDATE favoritos SET usuario_id = '" + ana + "' WHERE usuario_id IS NULL");
            st.execute("UPDATE categoria_dismiss SET usuario_id = '" + ana + "' WHERE usuario_id IS NULL");
        }

        assertThat(new UnownedRowsWarner(dataSource()).contar())
                .as("a warning that fires in a healthy installation is a warning people learn to skip")
                .isEmpty();
    }
}
