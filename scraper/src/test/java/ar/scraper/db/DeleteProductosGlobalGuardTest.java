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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user-accounts-and-roles, slice 8 — the catalogue-wipe guard counts
 * <b>everybody's</b> favourites, not the caller's.
 *
 * <p>This is the one place in the change where scoping is deliberately <i>not</i>
 * applied, and the reason is worth stating because the opposite looks tidier.
 * {@code DELETE /api/db/productos} destroys the <b>shared</b> product catalogue.
 * A product somebody else favourited is destroyed just the same, whoever presses
 * the button. Scoping the guard to the caller would make an ADMIN's "no protected
 * favourites" check pass <b>precisely when it is most misleading</b> — an admin
 * with no favourites of their own would wipe everybody's, and be told the coast
 * was clear.</p>
 *
 * <p>Ownership governs reads and writes of the favourite <i>row</i>. It does not
 * govern the lifetime of the shared product that row points at. These tests exist
 * so that distinction survives a future tidy-up that "makes the guard consistent
 * with the rest of slice 8".</p>
 *
 * <p>Accepted consequence: the 409 tells an ADMIN that <i>somebody</i> has
 * favourites. It reports a <b>count only</b> — never an owner, never a URL — and
 * an ADMIN already holds full catalogue control, so this is not a meaningful
 * escalation.</p>
 */
@Epic("Persistence")
@Feature("Data ownership")
@Story("DELETE /api/db/productos is guarded globally, not per caller")
@DisplayName("Catalogue wipe — the guard considers every owner")
class DeleteProductosGlobalGuardTest extends PostgresTestBase {

    private DatabaseService db;
    private UUID jefa;
    private UUID beto;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseService(dataSource());
        UsuarioRepository usuarios = new UsuarioRepository(dataSource());

        usuarios.crear("jefa", null, "$argon2id$x", false);
        usuarios.asignarRol("jefa", "ADMIN");
        jefa = usuarios.buscarActivaPorUsername("jefa").orElseThrow().id();

        usuarios.crear("beto", null, "$argon2id$x", false);
        usuarios.asignarRol("beto", "VIEWER");
        beto = usuarios.buscarActivaPorUsername("beto").orElseThrow().id();

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                    + "ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                    + "VALUES ('https://site.com/protegido', 'Sitio', 'P', 100, true, now(), now())");
        }
    }

    @Test
    @DisplayName("an ADMIN with zero favourites is still blocked by another user's")
    void anAdminWithNoFavouritesIsStillBlocked() {
        db.guardarFavorito(beto, "https://site.com/protegido", "Sitio", "de beto");

        assertThat(db.listarFavoritos(jefa))
                .as("the calling admin has none of their own — the misleading case")
                .isEmpty();

        assertThatThrownBy(() -> db.limpiarProductos())
                .as("a caller-scoped guard would pass here and silently destroy somebody else's data")
                .isInstanceOf(FavoritosProtegidosException.class);
    }

    @Test
    @DisplayName("the refusal reports a count and nothing else")
    void theRefusalNamesNoOwnerAndNoUrl() {
        db.guardarFavorito(beto, "https://site.com/protegido", "Sitio", "de beto");

        assertThatThrownBy(() -> db.limpiarProductos())
                .isInstanceOf(FavoritosProtegidosException.class)
                .satisfies(e -> {
                    FavoritosProtegidosException fpe = (FavoritosProtegidosException) e;
                    assertThat(fpe.getFavoritosBloqueantes()).isEqualTo(1);
                    assertThat(String.valueOf(e.getMessage()) + fpe.getFavoritosBloqueantes())
                            .as("a count is enough to act on; an owner or a URL would be a disclosure")
                            .doesNotContain("beto")
                            .doesNotContain("https://site.com/protegido");
                });
    }

    @Test
    @DisplayName("with nobody's favourites in the way the wipe proceeds")
    void withNoFavouritesAtAllTheWipeProceeds() {
        assertThatCode(() -> db.limpiarProductos()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unowned favourite blocks it too — invisible is not the same as absent")
    void anUnownedFavouriteBlocksItAsWell() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO favoritos (url, sitio, nombre, added_at) "
                    + "VALUES ('https://site.com/protegido', 'Sitio', 'Sin dueño', now())");
        }

        assertThatThrownBy(() -> db.limpiarProductos())
                .as("a row nobody can see is still a row somebody will want back once it is adopted")
                .isInstanceOf(FavoritosProtegidosException.class);
    }
}
