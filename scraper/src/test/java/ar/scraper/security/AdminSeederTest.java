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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user-accounts-and-roles, slice 2 — bootstrap seeding.
 *
 * <p>Two behaviours here are safety properties rather than conveniences.</p>
 *
 * <p><b>The seeder never overwrites an existing password hash.</b> It runs on
 * every boot, so an overwriting seeder would silently reset the admin password
 * back to whatever the environment says on each restart — undoing a password
 * change without telling anyone, and making the environment variable a
 * permanent back door rather than an initial value.</p>
 *
 * <p><b>It refuses the placeholder from {@code .env.example}.</b> The single
 * most likely way this project gets deployed with a known-to-the-world admin
 * password is somebody copying the example file and never editing it. Refusing
 * that exact string costs nothing and closes the common case; it warns loudly
 * rather than failing, because refusing to start leaves the operator with an
 * application they cannot log into and no explanation.</p>
 */
@Epic("Security")
@Feature("Bootstrap seeding")
@Story("AdminSeeder — idempotent, non-overwriting, placeholder-refusing")
@DisplayName("AdminSeeder")
class AdminSeederTest extends PostgresTestBase {

    private static final String PASSWORD_REAL = "una-password-de-verdad";
    private static final String PASSWORD_CLI = "otra-password-de-verdad";

    private UsuarioRepository repo;
    private PasswordHasher hasher;

    @BeforeEach
    void setUp() {
        repo = new UsuarioRepository(dataSource());
        hasher = new PasswordHasher();
    }

    private AdminSeeder seeder(String adminPassword) {
        return new AdminSeeder(repo, hasher, "admin", adminPassword, "cli", PASSWORD_CLI);
    }

    @Test
    @DisplayName("first run seeds one ADMIN account with an Argon2id hash")
    void firstRunSeedsTheAdmin() throws Exception {
        seeder(PASSWORD_REAL).run(null);

        UsuarioRepository.Cuenta admin = repo.buscarActivaPorUsername("admin").orElseThrow();
        assertThat(admin.passwordHash()).startsWith("$argon2id$");
        assertThat(hasher.verify(PASSWORD_REAL, admin.passwordHash())).isTrue();
        assertThat(repo.rolesDe("admin")).containsExactly("ADMIN");
        assertThat(admin.esServicio()).isFalse();
    }

    @Test
    @DisplayName("the CLI service account is seeded with es_servicio = TRUE and no email")
    void theServiceAccountIsFlaggedAsSuch() throws Exception {
        seeder(PASSWORD_REAL).run(null);

        UsuarioRepository.Cuenta cli = repo.buscarActivaPorUsername("cli").orElseThrow();
        assertThat(cli.esServicio())
                .as("the audit trail has to be able to tell a cronjob from a person")
                .isTrue();
        assertThat(cli.email())
                .as("a service account with an email would be resettable, and the CHECK forbids it")
                .isNull();
        assertThat(repo.rolesDe("cli")).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("a restart seeds nothing new and changes nothing")
    void restartIsIdempotent() throws Exception {
        seeder(PASSWORD_REAL).run(null);
        String hashInicial = repo.buscarActivaPorUsername("admin").orElseThrow().passwordHash();

        seeder(PASSWORD_REAL).run(null);

        assertThat(contarUsuarios()).isEqualTo(2);
        assertThat(repo.buscarActivaPorUsername("admin").orElseThrow().passwordHash())
                .isEqualTo(hashInicial);
        assertThat(repo.rolesDe("admin")).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("changing the env password does NOT change an already-seeded account")
    void anExistingHashIsNeverOverwritten() throws Exception {
        seeder(PASSWORD_REAL).run(null);

        seeder("una-password-completamente-distinta").run(null);

        assertThat(hasher.verify(PASSWORD_REAL,
                repo.buscarActivaPorUsername("admin").orElseThrow().passwordHash()))
                .as("otherwise the env var is a permanent back door, not an initial value")
                .isTrue();
    }

    @Test
    @DisplayName("the .env.example placeholder is refused, loudly, without seeding")
    void thePlaceholderPasswordIsRefused() {
        AdminSeeder conPlaceholder = new AdminSeeder(
                repo, hasher, "admin", AdminSeeder.PLACEHOLDER, "cli", PASSWORD_CLI);

        assertThatThrownBy(() -> conPlaceholder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_BOOTSTRAP_PASSWORD")
                .hasMessageContaining("admin");

        assertThat(repo.buscarActivaPorUsername("admin"))
                .as("a refused seed must not leave a half-made account behind")
                .isEmpty();
    }

    @Test
    @DisplayName("the service-account placeholder is refused too")
    void theServicePlaceholderIsRefusedAsWell() {
        AdminSeeder conPlaceholder = new AdminSeeder(
                repo, hasher, "admin", PASSWORD_REAL, "cli", AdminSeeder.PLACEHOLDER);

        assertThatThrownBy(() -> conPlaceholder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLI_SERVICE_ACCOUNT_PASSWORD");
    }

    private int contarUsuarios() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM usuario")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
