package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.ActorResolver;
import ar.scraper.security.Sujeto;
import ar.scraper.security.LoginRateLimiter;
import ar.scraper.security.PasswordHasher;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cuenta")
@RequiredArgsConstructor
public class CuentaEndpoints {

    public record Vista(String username, String email, boolean esServicio) {}

    private static final String CLAVE_DE_LIMITE = "cuenta-email:";

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final ActorResolver actorResolver;
    private final LoginRateLimiter limiteDePassword;

    @GetMapping
    public ResponseEntity<ApiResponse<Vista>> ver() {
        return ResponseEntity.ok(ApiResponse.ok(vistaDe(propia())));
    }

    /**
     * The address is the password-reset channel, so the caller proves the password even though
     * the session is already authenticated: a stolen access token alone must not be able to
     * redirect the reset. Sessions are NOT revoked (the password was just verified, unlike a
     * password change); reset links issued to the old address are annulled by the repository.
     */
    @PutMapping("/email")
    public ResponseEntity<ApiResponse<Vista>> cambiarEmail(@RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        if (body == null || !body.containsKey("email") || !(body.get("password") instanceof String password)
                || password.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "faltan_campos", "password y email son obligatorios.");
        }
        String nuevo = DireccionDeCorreo.normalizar(body.get("email"));

        UsuarioRepository.Cuenta cuenta = propia();
        String clave = CLAVE_DE_LIMITE + cuenta.username();
        String ip = request == null ? null : request.getRemoteAddr();
        if (!limiteDePassword.permitir(clave, ip)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "demasiados_intentos",
                    "Demasiados intentos fallidos. Probá de nuevo en "
                            + LoginRateLimiter.VENTANA.toMinutes() + " minutos.")
                    .withHeader(HttpHeaders.RETRY_AFTER, String.valueOf(LoginRateLimiter.VENTANA.toSeconds()));
        }
        if (!hasher.verify(password, cuenta.passwordHash())) {
            limiteDePassword.registrarFallo(clave, ip);
            throw new ApiException(HttpStatus.FORBIDDEN, "password_incorrecta", "La contraseña no es correcta.");
        }
        limiteDePassword.limpiarCuenta(clave);

        DireccionDeCorreo.exigirCambiado(usuarios.cambiarEmail(cuenta.username(), nuevo));
        return ResponseEntity.ok(ApiResponse.ok(vistaDe(propia())));
    }

    private UsuarioRepository.Cuenta propia() {
        Sujeto.de(actorResolver);
        return usuarios.buscarActivaPorUsername(actorResolver.current()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "usuario_inexistente", "La cuenta no existe."));
    }

    private static Vista vistaDe(UsuarioRepository.Cuenta cuenta) {
        return new Vista(cuenta.username(), cuenta.email(), cuenta.esServicio());
    }
}
