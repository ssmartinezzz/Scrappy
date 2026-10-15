package ar.scraper.db;

import ar.scraper.db.UsuarioRepository.ResultadoEmail;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UsuarioRepository.cambiarEmail — the reset channel changes hands atomically")
class UsuarioRepositoryEmailTest extends PostgresTestBase {

    private UsuarioRepository repo;
    private PasswordResetRepository resets;
    private UUID viewerId;

    @BeforeEach
    void setUp() {
        repo = TestRepositories.usuarios(dataSource());
        resets = TestRepositories.passwordResets(dataSource());
        viewerId = UsuarioDePrueba.crear(dataSource(), "viewer", "VIEWER");
    }

    @Test
    @DisplayName("sets the address lowercase-as-given and reads it back")
    void fija() {
        assertThat(repo.cambiarEmail("viewer", "viewer@example.com")).isEqualTo(ResultadoEmail.CAMBIADO);

        assertThat(repo.buscarActivaPorUsername("viewer").orElseThrow().email()).isEqualTo("viewer@example.com");
    }

    @Test
    @DisplayName("null clears the address")
    void limpia() {
        repo.cambiarEmail("viewer", "viewer@example.com");

        assertThat(repo.cambiarEmail("viewer", null)).isEqualTo(ResultadoEmail.CAMBIADO);

        assertThat(repo.buscarActivaPorUsername("viewer").orElseThrow().email()).isNull();
    }

    @Test
    @DisplayName("an address held by another account is TOMADO and changes nothing")
    void tomado() {
        UsuarioDePrueba.crear(dataSource(), "otroViewer", "VIEWER");
        repo.cambiarEmail("otroViewer", "compartido@example.com");
        repo.cambiarEmail("viewer", "viewer@example.com");

        assertThat(repo.cambiarEmail("viewer", "compartido@example.com")).isEqualTo(ResultadoEmail.TOMADO);

        assertThat(repo.buscarActivaPorUsername("viewer").orElseThrow().email()).isEqualTo("viewer@example.com");
    }

    @Test
    @DisplayName("re-saving the account's own address is not a conflict")
    void propioNoEsConflicto() {
        repo.cambiarEmail("viewer", "viewer@example.com");

        assertThat(repo.cambiarEmail("viewer", "viewer@example.com")).isEqualTo(ResultadoEmail.CAMBIADO);
    }

    @Test
    @DisplayName("a service account cannot get an address (chk_usuario_servicio_sin_email)")
    void servicio() {
        repo.crear("servicio", null, "$argon2id$de-prueba", true);
        repo.asignarRol("servicio", "ADMIN");

        assertThat(repo.cambiarEmail("servicio", "servicio@example.com")).isEqualTo(ResultadoEmail.ES_SERVICIO);
        assertThat(repo.cambiarEmail("servicio", null)).isEqualTo(ResultadoEmail.CAMBIADO);
    }

    @Test
    @DisplayName("an unknown username is NO_EXISTE")
    void noExiste() {
        assertThat(repo.cambiarEmail("nadie", "nadie@example.com")).isEqualTo(ResultadoEmail.NO_EXISTE);
    }

    @Test
    @DisplayName("a new address annuls the reset links issued for the old one")
    void anulaResets() {
        repo.cambiarEmail("viewer", "viejo@example.com");
        resets.crear(viewerId, "token-viejo", Instant.now().plus(Duration.ofMinutes(30)));

        repo.cambiarEmail("viewer", "nuevo@example.com");

        assertThat(resets.consumir("token-viejo", Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("a refused change leaves the reset links alive")
    void rechazoNoAnula() {
        UsuarioDePrueba.crear(dataSource(), "otroViewer", "VIEWER");
        repo.cambiarEmail("otroViewer", "compartido@example.com");
        repo.cambiarEmail("viewer", "viejo@example.com");
        resets.crear(viewerId, "token-vivo", Instant.now().plus(Duration.ofMinutes(30)));

        repo.cambiarEmail("viewer", "compartido@example.com");

        assertThat(resets.consumir("token-vivo", Instant.now())).contains(viewerId);
    }
}
