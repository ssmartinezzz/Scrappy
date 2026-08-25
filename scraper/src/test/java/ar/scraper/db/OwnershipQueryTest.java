package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 8 — personal data is scoped to its owner.
 *
 * <p>The interesting assertion is not that two VIEWERs see different lists. It is
 * that <b>an ADMIN sees only their own too</b>, through byte-identical SQL with a
 * different bound parameter. The role governs system-level operations — scraping,
 * cron, the database — not visibility into somebody else's favourites. An admin
 * who can read every user's saved outfits was never part of this design.</p>
 *
 * <p>{@link #noRepositoryExposesAnUnscopedRead} is the one that keeps it that
 * way. A role-branching read (<code>isAdmin ? selectAll() : selectMine()</code>)
 * is where a leak eventually appears: a third caller forgets the branch, or a
 * refactor inverts the condition, and it fails <i>silently</i> because the ADMIN
 * path looks like it works. Making the unsafe call unrepresentable is strictly
 * stronger than guarding it — a method that does not exist cannot be called by
 * mistake, and the compiler enforces that rather than a reviewer.</p>
 */
@Epic("Persistence")
@Feature("Data ownership")
@Story("Every personal read and write is scoped, with no unscoped variant anywhere")
@DisplayName("Ownership — scoped reads and writes")
class OwnershipQueryTest extends PostgresTestBase {

    private DatabaseService db;
    private UsuarioRepository usuarios;
    private UUID ana;
    private UUID beto;
    private UUID admin;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseService(dataSource());
        usuarios = new UsuarioRepository(dataSource());

        ana = crear("ana", "VIEWER");
        beto = crear("beto", "VIEWER");
        admin = crear("jefa", "ADMIN");

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                    + "ON CONFLICT DO NOTHING");
            for (String url : List.of("https://site.com/a", "https://site.com/b", "https://site.com/c")) {
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                        + "VALUES ('" + url + "', 'Sitio', 'P', 100, true, now(), now())");
            }
        }
    }

    private UUID crear(String username, String rol) {
        usuarios.crear(username, null, "$argon2id$x", false);
        usuarios.asignarRol(username, rol);
        return usuarios.buscarActivaPorUsername(username).orElseThrow().id();
    }

    // ── favoritos ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two accounts each see only their own favoritos")
    void twoAccountsSeeDifferentLists() {
        db.guardarFavorito(ana, "https://site.com/a", "Sitio", "de ana");
        db.guardarFavorito(beto, "https://site.com/b", "Sitio", "de beto");

        assertThat(db.listarFavoritos(ana)).singleElement()
                .satisfies(f -> assertThat(f.get("url")).isEqualTo("https://site.com/a"));
        assertThat(db.listarFavoritos(beto)).singleElement()
                .satisfies(f -> assertThat(f.get("url")).isEqualTo("https://site.com/b"));
    }

    @Test
    @DisplayName("an ADMIN reading favoritos sees only their own — no special access")
    void adminHasNoSpecialAccessToPersonalData() {
        db.guardarFavorito(ana, "https://site.com/a", "Sitio", "de ana");
        db.guardarFavorito(beto, "https://site.com/b", "Sitio", "de beto");
        db.guardarFavorito(admin, "https://site.com/c", "Sitio", "de la jefa");

        assertThat(db.listarFavoritos(admin))
                .as("the role governs the system, not other people's personal data — and this "
                        + "runs the SAME query as a VIEWER, with a different parameter")
                .singleElement()
                .satisfies(f -> assertThat(f.get("nombre")).isEqualTo("de la jefa"));
    }

    @Test
    @DisplayName("two accounts may favourite the same url without colliding")
    void theSameUrlCanBeFavouritedByBoth() {
        db.guardarFavorito(ana, "https://site.com/a", "Sitio", "de ana");
        db.guardarFavorito(beto, "https://site.com/a", "Sitio", "de beto");

        assertThat(db.listarFavoritos(ana)).hasSize(1);
        assertThat(db.listarFavoritos(beto)).hasSize(1);
    }

    @Test
    @DisplayName("re-saving my own favourite updates it instead of duplicating")
    void resavingMyOwnUpdatesInPlace() {
        db.guardarFavorito(ana, "https://site.com/a", "Sitio", "nombre viejo");
        db.guardarFavorito(ana, "https://site.com/a", "Sitio", "nombre nuevo");

        assertThat(db.listarFavoritos(ana)).singleElement()
                .satisfies(f -> assertThat(f.get("nombre")).isEqualTo("nombre nuevo"));
    }

    @Test
    @DisplayName("deleting somebody else's favourite does nothing")
    void deletingSomebodyElsesDoesNothing() {
        db.guardarFavorito(beto, "https://site.com/b", "Sitio", "de beto");

        db.eliminarFavorito(ana, "https://site.com/b");

        assertThat(db.listarFavoritos(beto))
                .as("scoped by owner, so a delete aimed at another user's row matches nothing")
                .hasSize(1);
    }

    // ── saved outfits ────────────────────────────────────────────────────────

    @Test
    @DisplayName("saved outfits are scoped, and another owner's cannot be renamed or deleted")
    void savedOutfitsAreScoped() {
        int deAna = db.guardarOutfit(ana, "El de Ana", "[]", null, 100);
        db.guardarOutfit(beto, "El de Beto", "[]", null, 200);

        assertThat(db.obtenerOutfitsGuardados(ana)).singleElement()
                .satisfies(o -> assertThat(o.get("nombre")).isEqualTo("El de Ana"));

        assertThat(db.eliminarOutfitGuardado(beto, deAna))
                .as("false covers 'does not exist' AND 'not yours' — telling them apart would "
                        + "confirm another user's row exists")
                .isFalse();
        assertThat(db.renombrarOutfit(beto, deAna, "Mío ahora")).isFalse();
        assertThat(db.obtenerOutfitsGuardados(ana)).singleElement()
                .satisfies(o -> assertThat(o.get("nombre")).isEqualTo("El de Ana"));
    }

    // ── feedback y dismiss ───────────────────────────────────────────────────

    @Test
    @DisplayName("outfit feedback is scoped — my likes do not steer your builder")
    void feedbackIsScoped() {
        db.guardarOutfitFeedbackItem(ana, "unisex", "torso", "https://site.com/a", true, "gym");
        db.guardarOutfitFeedbackItem(beto, "unisex", "torso", "https://site.com/b", true, "gym");

        assertThat(db.obtenerOutfitFeedback(ana)).singleElement()
                .satisfies(f -> assertThat(f.url()).isEqualTo("https://site.com/a"));
        assertThat(db.obtenerOutfitFeedback(beto)).hasSize(1);
    }

    @Test
    @DisplayName("clearing my feedback does not clear yours")
    void clearingMyFeedbackLeavesYoursAlone() {
        db.guardarOutfitFeedbackItem(ana, "unisex", "torso", "https://site.com/a", true, "gym");
        db.guardarOutfitFeedbackItem(beto, "unisex", "torso", "https://site.com/b", true, "gym");

        db.limpiarOutfitFeedback(ana);

        assertThat(db.obtenerOutfitFeedback(ana)).isEmpty();
        assertThat(db.obtenerOutfitFeedback(beto)).hasSize(1);
    }

    @Test
    @DisplayName("dismissed categories are personal")
    void dismissedCategoriesArePersonal() {
        db.guardarCategoriaDismiss(ana, "remeras");

        assertThat(db.obtenerCategoriaDismiss(ana)).containsExactly("remeras");
        assertThat(db.obtenerCategoriaDismiss(beto))
                .as("one person's 'no me interesa' must not empty somebody else's feed")
                .isEmpty();
    }

    @Test
    @DisplayName("the same category can be dismissed by two people independently")
    void twoPeopleCanDismissTheSameCategory() {
        db.guardarCategoriaDismiss(ana, "remeras");
        db.guardarCategoriaDismiss(beto, "remeras");
        db.borrarCategoriaDismiss(ana, "remeras");

        assertThat(db.obtenerCategoriaDismiss(ana)).isEmpty();
        assertThat(db.obtenerCategoriaDismiss(beto)).containsExactly("remeras");
    }

    // ── the structural guarantee ─────────────────────────────────────────────

    @Test
    @DisplayName("no personal repository exposes an unscoped read or write")
    void noRepositoryExposesAnUnscopedRead() {
        List<Class<?>> repos = List.of(
                FavoritosRepository.class, SavedOutfitsRepository.class, FeedbackRepository.class);
        List<String> personales = List.of(
                "listarFavoritos", "guardarFavorito", "eliminarFavorito", "tocarFavorito",
                "obtenerOutfitsGuardados", "guardarOutfit", "eliminarOutfitGuardado", "renombrarOutfit",
                "obtenerOutfitFeedback", "guardarOutfitFeedbackItem", "limpiarOutfitFeedback",
                "obtenerCategoriaDismiss", "guardarCategoriaDismiss", "borrarCategoriaDismiss");

        List<String> sinScope = new java.util.ArrayList<>();
        for (Class<?> repo : repos) {
            for (Method m : repo.getDeclaredMethods()) {
                if (!personales.contains(m.getName())) {
                    continue;
                }
                if (m.getParameterCount() == 0 || !m.getParameterTypes()[0].equals(UUID.class)) {
                    sinScope.add(repo.getSimpleName() + "." + m.getName()
                            + java.util.Arrays.toString(m.getParameterTypes()));
                }
            }
        }

        assertThat(sinScope)
                .as("an unscoped variant sitting next to a scoped one is the method somebody "
                        + "reaches for when they want 'all of them' — the leak starts there, "
                        + "not at a missing WHERE")
                .isEmpty();
    }

    @Test
    @DisplayName("the reflection sweep actually found methods — an empty one proves nothing")
    void theSweepIsNotVacuous() {
        long encontrados = java.util.stream.Stream.of(
                        FavoritosRepository.class, SavedOutfitsRepository.class, FeedbackRepository.class)
                .flatMap(c -> java.util.Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> m.getParameterCount() > 0 && m.getParameterTypes()[0].equals(UUID.class))
                .count();

        assertThat(encontrados).isGreaterThanOrEqualTo(12);
    }
}
