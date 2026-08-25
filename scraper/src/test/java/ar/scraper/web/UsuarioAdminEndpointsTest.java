package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.identity.ActorResolver;
import ar.scraper.security.PasswordHasher;
import ar.scraper.web.support.SujetoDePrueba;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 9 — account administration.
 *
 * <p>Authorization is <b>not</b> tested here, and that is deliberate rather than
 * an omission: it lives entirely in {@code ApiRoutePolicy}'s ADMIN row for
 * {@code /api/usuarios/**} and is exercised by the filter-chain tests. Asserting
 * it a second time from a class that constructs the endpoint directly would prove
 * nothing about the chain, which is the thing that actually decides.</p>
 *
 * <p>What is tested here is the behaviour behind that rule — including the guard
 * the spec does not ask for and that the feature is unsafe without: the last
 * active ADMIN cannot be removed. Without it, one call leaves an application
 * nobody can administer, recoverable only by direct SQL.</p>
 */
@Epic("Security")
@Feature("User administration")
@Story("List, create, change role and deactivate — ADMIN only, backend only")
@DisplayName("UsuarioAdminEndpoints")
class UsuarioAdminEndpointsTest extends PostgresTestBase {

    private UsuarioRepository usuarios;
    private PasswordHasher hasher;
    private UsuarioAdminEndpoints endpoints;

    @BeforeEach
    void setUp() {
        usuarios = new UsuarioRepository(dataSource());
        hasher = new PasswordHasher();
        endpoints = new UsuarioAdminEndpoints(usuarios, hasher, new ActorResolver());
        SujetoDePrueba.entrar(dataSource(), "ADMIN");

        // A second ADMIN so the last-admin guard is not tripped by every test.
        usuarios.crear("jefa", null, hasher.hash("x"), false);
        usuarios.asignarRol("jefa", "ADMIN");
    }

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("creating with a valid role hashes the password and grants the role")
    void creatingWithAValidRoleWorks() {
        ResponseEntity<ObjectNode> resp = crear("ana", "ana@example.com", "una-password-larga", "VIEWER");

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(usuarios.rolesDe("ana")).containsExactly("VIEWER");

        String hash = usuarios.buscarActivaPorUsername("ana").orElseThrow().passwordHash();
        assertThat(hash).startsWith("$argon2id$");
        assertThat(hasher.verify("una-password-larga", hash)).isTrue();
        assertThat(resp.getBody().toString())
                .as("a creation response is not a place to echo a credential")
                .doesNotContain("una-password-larga")
                .doesNotContain(hash);
    }

    @Test
    @DisplayName("a role outside the closed vocabulary is rejected and creates nothing")
    void anInventedRoleCreatesNothing() {
        ResponseEntity<ObjectNode> resp = crear("ana", null, "una-password-larga", "SUPERADMIN");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(usuarios.buscarActivaPorUsername("ana"))
                .as("half-creating the account would leave one that lists fine and cannot log in, "
                        + "because a user with no role reads as disabled")
                .isEmpty();
    }

    @Test
    @DisplayName("a duplicate username is refused without touching the existing account")
    void aDuplicateUsernameIsRefused() {
        crear("ana", null, "una-password-larga", "VIEWER");
        String hashOriginal = usuarios.buscarActivaPorUsername("ana").orElseThrow().passwordHash();

        ResponseEntity<ObjectNode> resp = crear("ana", null, "otra-password-larga", "ADMIN");

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(usuarios.buscarActivaPorUsername("ana").orElseThrow().passwordHash())
                .as("otherwise creating a duplicate would be a way to reset somebody's password")
                .isEqualTo(hashOriginal);
        assertThat(usuarios.rolesDe("ana")).containsExactly("VIEWER");
    }

    @Test
    @DisplayName("a short password is refused")
    void aShortPasswordIsRefused() {
        assertThat(crear("ana", null, "corta", "VIEWER").getStatusCode().value()).isEqualTo(400);
        assertThat(usuarios.buscarActivaPorUsername("ana")).isEmpty();
    }

    @Test
    @DisplayName("missing fields are refused, not defaulted")
    void missingFieldsAreRefused() {
        assertThat(endpoints.crear(Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(endpoints.crear(null).getStatusCode().value()).isEqualTo(400);
        assertThat(crear("ana", null, "una-password-larga", null).getStatusCode().value()).isEqualTo(400);
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the listing includes disabled accounts and never a password hash")
    void theListingShowsEverybodyAndNoSecrets() {
        crear("ana", "ana@example.com", "una-password-larga", "VIEWER");
        usuarios.desactivar("ana");

        ResponseEntity<ArrayNode> resp = endpoints.listar();

        assertThat(resp.getBody().toString())
                .as("a hash nobody fetches cannot be leaked by a future serializer")
                .doesNotContain("$argon2id$");
        assertThat(resp.getBody().toString())
                .as("hiding the deactivated account would make deactivation look like deletion "
                        + "to the admin who has to undo it")
                .contains("\"ana\"");
    }

    // ── change role ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("changing a role replaces it instead of accumulating grants")
    void changingARoleReplacesIt() {
        crear("ana", null, "una-password-larga", "VIEWER");

        assertThat(endpoints.cambiarRol("ana", Map.of("role", "ADMIN")).getStatusCode().value())
                .isEqualTo(200);

        assertThat(usuarios.rolesDe("ana"))
                .as("leaving the old grant in place would be a demotion that did not demote")
                .containsExactly("ADMIN");
    }

    @Test
    @DisplayName("an invalid role or an unknown account is refused")
    void invalidRoleOrUnknownAccountIsRefused() {
        crear("ana", null, "una-password-larga", "VIEWER");

        assertThat(endpoints.cambiarRol("ana", Map.of("role", "SUPERADMIN")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(endpoints.cambiarRol("nadie", Map.of("role", "ADMIN")).getStatusCode().value())
                .isEqualTo(404);
        assertThat(usuarios.rolesDe("ana")).containsExactly("VIEWER");
    }

    // ── deactivate ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivating sets activo=FALSE and never deletes the row")
    void deactivatingIsNeverADelete() {
        crear("ana", null, "una-password-larga", "VIEWER");

        assertThat(endpoints.desactivar("ana").getStatusCode().value()).isEqualTo(200);

        assertThat(usuarios.buscarActivaPorUsername("ana"))
                .as("the lookup excludes disabled accounts — which is what makes the very next "
                        + "request with their still-valid token fail, with no new mechanism")
                .isEmpty();
        assertThat(usuarios.existe("ana"))
                .as("a DELETE would cascade away their roles, tokens, audit trail and personal rows")
                .isTrue();
        assertThat(usuarios.rolesDe("ana")).containsExactly("VIEWER");
    }

    @Test
    @DisplayName("a deactivated account can be brought back")
    void aDeactivatedAccountCanBeReactivated() {
        crear("ana", null, "una-password-larga", "VIEWER");
        endpoints.desactivar("ana");

        assertThat(endpoints.reactivar("ana").getStatusCode().value()).isEqualTo(200);
        assertThat(usuarios.buscarActivaPorUsername("ana")).isPresent();
    }

    @Test
    @DisplayName("deactivating an unknown account is 404")
    void deactivatingAnUnknownAccountIs404() {
        assertThat(endpoints.desactivar("nadie").getStatusCode().value()).isEqualTo(404);
    }

    // ── the guard the spec does not ask for ──────────────────────────────────

    @Test
    @DisplayName("the last active ADMIN cannot be deactivated")
    void theLastAdminCannotBeDeactivated() {
        // 'jefa' from setUp is the only other ADMIN — remove her first.
        endpoints.desactivar("jefa");
        String soloAdmin = soloAdminRestante();

        ResponseEntity<ObjectNode> resp = endpoints.desactivar(soloAdmin);

        assertThat(resp.getStatusCode().value())
                .as("one call would otherwise leave an application nobody can administer, "
                        + "recoverable only by direct SQL")
                .isEqualTo(409);
        assertThat(usuarios.buscarActivaPorUsername(soloAdmin)).isPresent();
    }

    @Test
    @DisplayName("the last active ADMIN cannot be demoted either")
    void theLastAdminCannotBeDemoted() {
        endpoints.desactivar("jefa");
        String soloAdmin = soloAdminRestante();

        ResponseEntity<ObjectNode> resp = endpoints.cambiarRol(soloAdmin, Map.of("role", "VIEWER"));

        assertThat(resp.getStatusCode().value())
                .as("demoting is the same trap wearing different clothes")
                .isEqualTo(409);
        assertThat(usuarios.rolesDe(soloAdmin)).contains("ADMIN");
    }

    @Test
    @DisplayName("with a second ADMIN present, either one may be removed")
    void withTwoAdminsEitherCanGo() {
        assertThat(endpoints.desactivar("jefa").getStatusCode().value())
                .as("the guard protects the last one, not the feature")
                .isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<ObjectNode> crear(String username, String email, String password, String rol) {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("password", password);
        if (email != null) {
            body.put("email", email);
        }
        if (rol != null) {
            body.put("role", rol);
        }
        return endpoints.crear(body);
    }

    /** The test's own subject is the remaining ADMIN once 'jefa' is out. */
    private String soloAdminRestante() {
        return usuarios.listar().stream()
                .filter(f -> f.activo() && f.roles().contains("ADMIN"))
                .map(UsuarioRepository.Ficha::username)
                .findFirst()
                .orElseThrow();
    }
}
