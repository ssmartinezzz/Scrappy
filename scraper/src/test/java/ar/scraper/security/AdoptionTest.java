package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 2 — adoption of the rows that existed before
 * anybody had an account.
 *
 * <p>{@code V26} could not give the existing favourites an owner, because at
 * migration time no user row existed yet. Adoption closes that gap right after
 * the bootstrap admin is seeded, in the same startup. It matters more than it
 * looks: from slice 8 onward a scoped read is
 * {@code WHERE usuario_id = :subject}, and {@code NULL} is never equal to
 * anything, so a row that was never adopted becomes invisible to <b>everybody</b>
 * rather than visible to everybody. That is the safe direction to fail in — an
 * invisible row can be adopted and reappears, whereas a leaked one cannot be
 * un-leaked — but it is still a user's favourites silently vanishing, so the
 * upgrade path is tested rather than assumed.</p>
 */
@Epic("Persistence")
@Feature("Data ownership")
@Story("Pre-existing personal rows are adopted by the bootstrap admin")
@DisplayName("Adoption — no row is left ownerless after startup")
class AdoptionTest extends PostgresTestBase {

    private static final List<String> TABLAS =
            List.of("favoritos", "saved_outfits", "outfit_feedback_item", "categoria_dismiss");

    private UsuarioRepository repo;
    private AdminSeeder seeder;

    @BeforeEach
    void setUp() {
        repo = new UsuarioRepository(dataSource());
        seeder = new AdminSeeder(repo, new PasswordHasher(),
                "admin", "una-password-de-verdad", "cli", "otra-password-de-verdad");
    }

    @Test
    @DisplayName("every pre-existing row is adopted by the bootstrap admin")
    void everyOrphanRowIsAdopted() throws Exception {
        sembrarFilasSinDueno();

        seeder.run(null);

        UUID admin = repo.buscarActivaPorUsername("admin").orElseThrow().id();
        for (String tabla : TABLAS) {
            assertThat(sinDueno(tabla)).as("%s has no ownerless row left", tabla).isZero();
            assertThat(duenosDe(tabla))
                    .as("%s belongs to the bootstrap admin", tabla)
                    .containsExactly(admin);
        }
    }

    @Test
    @DisplayName("a pre-existing favourite survives the upgrade — never NULL, never deleted")
    void aPreExistingFavouriteSurvives() throws Exception {
        sembrarFilasSinDueno();

        seeder.run(null);

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT url, usuario_id FROM favoritos WHERE url = 'https://site.com/viejo'")) {
            assertThat(rs.next()).as("the row is still there").isTrue();
            assertThat(rs.getObject("usuario_id", UUID.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("adoption is idempotent — a second run touches nothing")
    void adoptionIsIdempotent() throws Exception {
        sembrarFilasSinDueno();
        seeder.run(null);
        UUID admin = repo.buscarActivaPorUsername("admin").orElseThrow().id();

        seeder.run(null);

        for (String tabla : TABLAS) {
            assertThat(duenosDe(tabla)).containsExactly(admin);
            assertThat(sinDueno(tabla)).isZero();
        }
    }

    @Test
    @DisplayName("a row that already has another owner is not stolen")
    void adoptionDoesNotReassignAnOwnedRow() throws Exception {
        repo.crear("otra", null, "$argon2id$x", false);
        UUID otra = repo.buscarActivaPorUsername("otra").orElseThrow().id();
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO categoria_dismiss (categoria, created_at, usuario_id) "
                    + "VALUES ('remeras', now(), '" + otra + "')");
        }

        seeder.run(null);

        assertThat(duenosDe("categoria_dismiss"))
                .as("adoption claims the ownerless, never the owned")
                .containsExactly(otra);
    }

    @Test
    @DisplayName("two instances starting at once do not corrupt each other")
    void concurrentStartupsAreSafe() throws Exception {
        sembrarFilasSinDueno();

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<Throwable> fallas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        Runnable arranque = () -> {
            listos.countDown();
            try {
                largada.await(5, TimeUnit.SECONDS);
                new AdminSeeder(new UsuarioRepository(dataSource()), new PasswordHasher(),
                        "admin", "una-password-de-verdad", "cli", "otra-password-de-verdad").run(null);
            } catch (Throwable t) {
                fallas.add(t);
            }
        };
        Thread uno = new Thread(arranque);
        Thread dos = new Thread(arranque);
        uno.start();
        dos.start();
        assertThat(listos.await(5, TimeUnit.SECONDS)).isTrue();
        largada.countDown();
        uno.join(30_000);
        dos.join(30_000);

        assertThat(fallas).as("a second instance must not blow up on the first one's insert").isEmpty();
        assertThat(contar("usuario")).as("exactly one admin and one service account").isEqualTo(2);
        UUID admin = repo.buscarActivaPorUsername("admin").orElseThrow().id();
        for (String tabla : TABLAS) {
            assertThat(duenosDe(tabla)).containsExactly(admin);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The state an installation upgrading from before this change is in. */
    private void sembrarFilasSinDueno() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                    + "ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                    + "VALUES ('https://site.com/viejo', 'Sitio', 'P', 100, true, now(), now())");
            st.execute("INSERT INTO favoritos (url, sitio, nombre, added_at) "
                    + "VALUES ('https://site.com/viejo', 'Sitio', 'Viejo', now())");
            // V14 normalizó slots_json/suplementos_json a saved_outfit_item y las
            // soltó — la tabla padre ya no guarda documentos.
            st.execute("INSERT INTO saved_outfits (nombre, total_estimado, created_at) "
                    + "VALUES ('Outfit viejo', 0, now())");
            st.execute("INSERT INTO outfit_feedback_item (slot, url, liked, estilo, created_at) "
                    + "VALUES ('torso', 'https://site.com/viejo', true, 'gym', now())");
            st.execute("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('remeras', now())");
        }
    }

    private int sinDueno(String tabla) throws Exception {
        return contar(tabla + " WHERE usuario_id IS NULL");
    }

    private int contar(String desde) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + desde)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private List<UUID> duenosDe(String tabla) throws Exception {
        List<UUID> duenos = new java.util.ArrayList<>();
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT usuario_id FROM " + tabla)) {
            while (rs.next()) {
                duenos.add(rs.getObject(1, UUID.class));
            }
        }
        return duenos;
    }
}
