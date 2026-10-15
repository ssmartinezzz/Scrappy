package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.security.ActorResolver;
import ar.scraper.security.LoginRateLimiter;
import ar.scraper.security.PasswordHasher;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CuentaEndpoints — the signed-in account manages its own email, with its password")
class CuentaEndpointsTest extends PostgresTestBase {

    private static final String PASSWORD = "una-password-larga";

    private UsuarioRepository usuarios;
    private CuentaEndpoints endpoints;

    @BeforeEach
    void setUp() {
        usuarios = TestRepositories.usuarios(dataSource());
        PasswordHasher hasher = new PasswordHasher();
        endpoints = new CuentaEndpoints(usuarios, hasher, new ActorResolver(),
                new LoginRateLimiter(Clock.systemUTC()));
        usuarios.crear("test-viewer", null, hasher.hash(PASSWORD), false);
        usuarios.asignarRol("test-viewer", "VIEWER");
        UsuarioDePrueba.crear(dataSource(), "otroViewer", "VIEWER");
        SujetoDePrueba.entrar(dataSource(), "VIEWER");
    }

    @AfterEach
    void limpiar() {
        SujetoDePrueba.salir();
    }

    private static Map<String, Object> cuerpo(String password, String email) {
        Map<String, Object> m = new HashMap<>();
        m.put("password", password);
        m.put("email", email);
        return m;
    }

    @Test
    @DisplayName("GET shows the caller's username, email and whether it is a service account")
    void ver() {
        usuarios.cambiarEmail("test-viewer", "viewer@example.com");

        var data = Wire.data(endpoints.ver());

        assertThat(data.get("username").asText()).isEqualTo("test-viewer");
        assertThat(data.get("email").asText()).isEqualTo("viewer@example.com");
        assertThat(data.get("esServicio").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PUT with the right password sets the address, lowercased")
    void cambia() {
        var resp = endpoints.cambiarEmail(cuerpo(PASSWORD, "Viewer@Example.com"), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Wire.data(resp).get("email").asText()).isEqualTo("viewer@example.com");
        assertThat(usuarios.buscarActivaPorUsername("test-viewer").orElseThrow().email())
                .isEqualTo("viewer@example.com");
    }

    @Test
    @DisplayName("PUT with a null email clears it")
    void limpia() {
        usuarios.cambiarEmail("test-viewer", "viewer@example.com");

        endpoints.cambiarEmail(cuerpo(PASSWORD, null), null);

        assertThat(usuarios.buscarActivaPorUsername("test-viewer").orElseThrow().email()).isNull();
    }

    @Test
    @DisplayName("a wrong password is 403 (never 401: the client would treat it as an expired session) and writes nothing")
    void passwordIncorrecta() {
        assertThatThrownBy(() -> endpoints.cambiarEmail(cuerpo("no-es-esa", "viewer@example.com"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("password_incorrecta");
                });
        assertThat(usuarios.buscarActivaPorUsername("test-viewer").orElseThrow().email()).isNull();
    }

    @Test
    @DisplayName("a missing password or a body without the email key is 400: nothing is cleared by omission")
    void camposFaltantes() {
        usuarios.cambiarEmail("test-viewer", "viewer@example.com");

        for (Map<String, Object> body : new Map[]{Map.of("email", "x@example.com"), Map.of("password", PASSWORD)}) {
            assertThatThrownBy(() -> endpoints.cambiarEmail(body, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThatThrownBy(() -> endpoints.cambiarEmail(null, null)).isInstanceOf(ApiException.class);
        assertThat(usuarios.buscarActivaPorUsername("test-viewer").orElseThrow().email())
                .isEqualTo("viewer@example.com");
    }

    @Test
    @DisplayName("an address already used by another account is 409 email_no_disponible, naming no account")
    void tomado() {
        usuarios.cambiarEmail("otroViewer", "compartido@example.com");

        assertThatThrownBy(() -> endpoints.cambiarEmail(cuerpo(PASSWORD, "compartido@example.com"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("email_no_disponible");
                    assertThat(e.getMessage()).doesNotContain("otroViewer");
                });
    }

    @Test
    @DisplayName("a malformed address is 400 and the password is not even checked")
    void invalida() {
        assertThatThrownBy(() -> endpoints.cambiarEmail(cuerpo("no-es-esa", "sin-arroba"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("email_invalido"));
    }

    @Test
    @DisplayName("a service account cannot hold an address")
    void servicio() {
        SujetoDePrueba.salir();
        usuarios.crear("servicio-cli", null, new PasswordHasher().hash(PASSWORD), true);
        usuarios.asignarRol("servicio-cli", "ADMIN");
        SujetoDePrueba.entrarComo(usuarios.buscarActivaPorUsername("servicio-cli").orElseThrow().id(),
                "servicio-cli", "ADMIN");

        assertThatThrownBy(() -> endpoints.cambiarEmail(cuerpo(PASSWORD, "servicio@example.com"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("cuenta_de_servicio");
                });
    }

    @Test
    @DisplayName("repeated wrong passwords are throttled with 429, independently of the login budget")
    void throttle() {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            assertThatThrownBy(() -> endpoints.cambiarEmail(cuerpo("no-es-esa", "viewer@example.com"), null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThatThrownBy(() -> endpoints.cambiarEmail(cuerpo(PASSWORD, "viewer@example.com"), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
