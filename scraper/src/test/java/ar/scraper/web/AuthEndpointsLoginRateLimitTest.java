package ar.scraper.web;

import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.security.LoginRateLimiter;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.TokenService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El limiter dentro del endpoint, que es donde puede romper algo.
 *
 * <p>{@code AuthEndpoints} está construido para no ser un oráculo: todo fallo
 * devuelve el mismo 401 con el mismo body, y un usuario inexistente se verifica
 * contra un hash señuelo para que tampoco se delate por tiempo. Un limiter mal
 * puesto tira eso a la basura, así que esos son los tests que importan acá.</p>
 */
@Epic("Security")
@Feature("Authentication")
@Story("POST /api/auth/login is rate limited")
@DisplayName("AuthEndpoints — login con rate limit")
class AuthEndpointsLoginRateLimitTest extends PostgresTestBase {

    private static final String SECRETO = "un-secreto-de-al-menos-32-bytes-para-hs256";
    private static final String PASSWORD = "la-password-correcta";

    private AuthEndpoints endpoints;

    @BeforeEach
    void setUp() {
        UsuarioRepository repo = new UsuarioRepository(dataSource());
        PasswordHasher hasher = new PasswordHasher();
        Clock reloj = Clock.systemUTC();
        TokenService tokens = new TokenService(SECRETO, reloj);

        endpoints = new AuthEndpoints(repo, hasher, tokens,
                new RefreshTokenService(new RefreshTokenRepository(dataSource()), tokens, reloj),
                null,
                proveedorVacio(),
                proveedorDe(new LoginRateLimiter(reloj)));

        repo.crear("ana", "ana@example.com", hasher.hash(PASSWORD), false);
        repo.asignarRol("ana", "VIEWER");
    }

    @Test
    @DisplayName("Pasados los intentos fallidos el login devuelve 429 con Retry-After")
    void tras5FallosDevuelve429() {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            assertThat(login("ana", "mal").getStatusCode().value()).isEqualTo(401);
        }

        ResponseEntity<ObjectNode> frenado = login("ana", "mal");

        assertThat(frenado.getStatusCode().value()).isEqualTo(429);
        assertThat(frenado.getHeaders().getFirst("Retry-After"))
                .isEqualTo(String.valueOf(LoginRateLimiter.VENTANA.toSeconds()));
    }

    @Test
    @DisplayName("La contraseña correcta ya no entra mientras la cuenta está frenada")
    void elFrenoTambienTapaLaPasswordCorrecta() {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            login("ana", "mal");
        }

        assertThat(login("ana", PASSWORD).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("Un login exitoso limpia el contador: los fallos previos no se acumulan contra vos")
    void elExitoLimpiaElContador() {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA - 1; i++) {
            login("ana", "mal");
        }
        assertThat(login("ana", PASSWORD).getStatusCode().value()).isEqualTo(200);

        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            assertThat(login("ana", "mal").getStatusCode().value())
                    .as("el presupuesto arrancó de cero después del login bueno").isEqualTo(401);
        }
    }

    @Test
    @DisplayName("Frenar una cuenta no frena a otra")
    void frenarUnaCuentaNoFrenaAOtra() {
        UsuarioRepository repo = new UsuarioRepository(dataSource());
        PasswordHasher hasher = new PasswordHasher();
        repo.crear("beto", "beto@example.com", hasher.hash(PASSWORD), false);
        repo.asignarRol("beto", "VIEWER");

        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            login("ana", "mal");
        }

        assertThat(login("ana", "mal").getStatusCode().value()).isEqualTo(429);
        assertThat(login("beto", PASSWORD).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("El 429 no delata qué cuentas existen: una inexistente se frena igual")
    void elFrenoNoEsUnOraculoDeExistencia() {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            assertThat(login("nadie", "mal").getStatusCode().value()).isEqualTo(401);
        }

        ResponseEntity<ObjectNode> inexistente = login("nadie", "mal");
        ResponseEntity<ObjectNode> real = frenar("ana");

        assertThat(inexistente.getStatusCode().value()).isEqualTo(429);
        assertThat(inexistente.getBody().toString())
                .as("mismo cuerpo para una cuenta real y una inventada")
                .isEqualTo(real.getBody().toString());
    }

    @Test
    @DisplayName("Sin limiter registrado el endpoint se comporta como antes")
    void sinLimiterElEndpointNoCambia() {
        UsuarioRepository repo = new UsuarioRepository(dataSource());
        PasswordHasher hasher = new PasswordHasher();
        Clock reloj = Clock.systemUTC();
        TokenService tokens = new TokenService(SECRETO, reloj);
        AuthEndpoints sinLimiter = new AuthEndpoints(repo, hasher, tokens,
                new RefreshTokenService(new RefreshTokenRepository(dataSource()), tokens, reloj), null);

        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA + 3; i++) {
            assertThat(sinLimiter.login(cuerpo("ana", "mal")).getStatusCode().value()).isEqualTo(401);
        }
    }

    private ResponseEntity<ObjectNode> frenar(String username) {
        ResponseEntity<ObjectNode> ultima = null;
        for (int i = 0; i <= LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            ultima = login(username, "mal");
        }
        return ultima;
    }

    private ResponseEntity<ObjectNode> login(String username, String password) {
        return endpoints.login(cuerpo(username, password));
    }

    private static Map<String, String> cuerpo(String username, String password) {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    private static <T> ObjectProvider<T> proveedorVacio() {
        return proveedorDe(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> proveedorDe(T valor) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(valor);
        return provider;
    }
}
