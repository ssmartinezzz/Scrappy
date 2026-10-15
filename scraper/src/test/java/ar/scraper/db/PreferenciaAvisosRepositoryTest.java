package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import ar.scraper.notificaciones.PreferenciaAvisos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreferenciaAvisosRepository — the per-account opt-out for price-drop mail")
class PreferenciaAvisosRepositoryTest extends PostgresTestBase {

    private PreferenciaAvisosRepository repo;
    private UsuarioRepository usuarios;

    @BeforeEach
    void setUp() {
        repo = new PreferenciaAvisosRepository(dataSource());
        usuarios = TestRepositories.usuarios(dataSource());
    }

    private UUID cuenta(String username, String email) {
        usuarios.crear(username, email, "$argon2id$de-prueba", false);
        return usuarios.buscarActivaPorUsername(username).orElseThrow().id();
    }

    @Test
    @DisplayName("a new account is opted in by default and reports that it has an email")
    void porDefectoActivada() {
        UUID id = cuenta("usuario", "usuario@example.com");

        assertThat(repo.leer(id)).contains(new PreferenciaAvisos(true, true));
    }

    @Test
    @DisplayName("turning it off and on persists and touches only that account")
    void alternaSoloLaPropia() {
        UUID id = cuenta("usuario", "usuario@example.com");
        UUID otro = cuenta("otro", "otro@example.com");

        assertThat(repo.actualizar(id, false)).isTrue();

        assertThat(repo.leer(id)).contains(new PreferenciaAvisos(false, true));
        assertThat(repo.leer(otro)).contains(new PreferenciaAvisos(true, true));
        assertThat(repo.actualizar(id, true)).isTrue();
        assertThat(repo.leer(id)).contains(new PreferenciaAvisos(true, true));
    }

    @Test
    @DisplayName("an account without an email cannot change the flag: nothing to opt in to")
    void sinEmailNoCambia() {
        UUID id = cuenta("servicio", null);

        assertThat(repo.actualizar(id, false)).isFalse();

        assertThat(repo.leer(id)).contains(new PreferenciaAvisos(true, false));
    }

    @Test
    @DisplayName("an unknown account reads as absent")
    void desconocida() {
        assertThat(repo.leer(UUID.randomUUID())).isEmpty();
    }
}
