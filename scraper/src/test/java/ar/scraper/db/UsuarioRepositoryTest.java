package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 1 — the read/write seam over {@code usuario}.
 *
 * <p>Slice 1 deliberately has no HTTP surface, so this is the only place the new
 * tables are exercised from Java. Two behaviours are pinned here because slice 2
 * will build login directly on top of them:</p>
 *
 * <ul>
 *   <li><b>Lookup excludes disabled accounts.</b> {@code activo = FALSE} is a
 *       revocation switch, and login must not have to remember to check it —
 *       the repository returns nothing, so an empty result already means
 *       "no such usable account", covering both "unknown" and "disabled" with
 *       one branch instead of two.</li>
 *   <li><b>Lookup is by username, never by email.</b> Email is optional and the
 *       bootstrap and service accounts have none; making it a login identifier
 *       would leave those accounts unreachable.</li>
 * </ul>
 */
@Epic("Persistence")
@Feature("Account schema")
@Story("UsuarioRepository — insert, find-by-username, disabled accounts excluded")
@DisplayName("UsuarioRepository")
class UsuarioRepositoryTest extends PostgresTestBase {

    private UsuarioRepository repo;

    @BeforeEach
    void setUp() {
        repo = new UsuarioRepository(dataSource());
    }

    @Test
    @DisplayName("an inserted account is found by its username")
    void insertThenFindByUsername() {
        repo.crear("ana", "ana@example.com", "$argon2id$hash", false);

        Optional<UsuarioRepository.Cuenta> encontrada = repo.buscarActivaPorUsername("ana");

        assertThat(encontrada).isPresent();
        assertThat(encontrada.get().username()).isEqualTo("ana");
        assertThat(encontrada.get().email()).isEqualTo("ana@example.com");
        assertThat(encontrada.get().passwordHash()).isEqualTo("$argon2id$hash");
        assertThat(encontrada.get().esServicio()).isFalse();
        assertThat(encontrada.get().id()).isNotNull();
    }

    @Test
    @DisplayName("an account with no email round-trips with a null email")
    void emailIsOptional() {
        repo.crear("cli", null, "$argon2id$hash", true);

        Optional<UsuarioRepository.Cuenta> encontrada = repo.buscarActivaPorUsername("cli");

        assertThat(encontrada).isPresent();
        assertThat(encontrada.get().email()).isNull();
        assertThat(encontrada.get().esServicio()).isTrue();
    }

    @Test
    @DisplayName("a disabled account is not returned by the lookup")
    void disabledAccountIsExcluded() {
        repo.crear("baja", null, "$argon2id$hash", false);
        repo.desactivar("baja");

        assertThat(repo.buscarActivaPorUsername("baja"))
                .as("an empty result must mean 'no usable account', covering unknown and disabled alike")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown username yields empty, not an exception")
    void unknownUsernameYieldsEmpty() {
        assertThat(repo.buscarActivaPorUsername("nadie")).isEmpty();
    }

    @Test
    @DisplayName("creating the same username twice does not duplicate the account")
    void creationIsIdempotentOnUsername() {
        assertThat(repo.crear("ana", null, "$argon2id$primero", false)).isTrue();
        assertThat(repo.crear("ana", null, "$argon2id$segundo", false))
                .as("the bootstrap seeder restarts on every boot and must not fight itself")
                .isFalse();

        assertThat(repo.buscarActivaPorUsername("ana").orElseThrow().passwordHash())
                .as("an existing hash is never overwritten by a re-seed")
                .isEqualTo("$argon2id$primero");
    }

    @Test
    @DisplayName("a role can be granted and read back")
    void rolesAreGrantedAndRead() {
        repo.crear("ana", null, "$argon2id$hash", false);
        repo.asignarRol("ana", "ADMIN");

        assertThat(repo.rolesDe("ana")).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("granting the same role twice is idempotent")
    void grantingTheSameRoleTwiceIsIdempotent() {
        repo.crear("ana", null, "$argon2id$hash", false);
        repo.asignarRol("ana", "ADMIN");
        repo.asignarRol("ana", "ADMIN");

        assertThat(repo.rolesDe("ana")).containsExactly("ADMIN");
    }
}
