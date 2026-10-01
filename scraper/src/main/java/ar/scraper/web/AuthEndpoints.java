package ar.scraper.web;

import ar.scraper.config.AllowedOrigins;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.AuthenticatedSubject;
import ar.scraper.security.LoginRateLimiter;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshCookie;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.TokenService;
import ar.scraper.security.reset.PasswordResetService;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.AuthDtos;
import ar.scraper.web.dto.MensajeDto;
import ar.scraper.web.dto.OpResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** Authentication endpoints: login, refresh, logout, me, password reset. */
@RestController
@RequestMapping("/api/auth")
public class AuthEndpoints {

    private static final Logger LOG = LoggerFactory.getLogger(AuthEndpoints.class);

    public static final String CSRF_HEADER = "X-Refresh-CSRF";

    private static final Set<String> SEC_FETCH_SITE_CONFIABLE = Set.of("same-origin", "same-site");

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final RefreshTokenService sesiones;
    private final PasswordResetService reseteos;
    private final AllowedOrigins allowedOrigins;
    /** Absent in @WebMvcTest slices that do not register it: no throttle there. */
    private final LoginRateLimiter limiteLogin;

    /**
     * A real Argon2id hash of a value nobody knows, verified against when the account does not
     * exist.
     */
    private final String hashSenuelo;

    /**
     * {@code AllowedOrigins} arrives via {@link ObjectProvider} because several older
     * {@code @WebMvcTest} slices register no such bean; absent means "never admit" in
     * {@link #esBootstrapAdmitido}.
     */
    @Autowired
    public AuthEndpoints(UsuarioRepository usuarios,
                         PasswordHasher hasher,
                         TokenService tokens,
                         RefreshTokenService sesiones,
                         PasswordResetService reseteos,
                         ObjectProvider<AllowedOrigins> allowedOrigins,
                         ObjectProvider<LoginRateLimiter> limiteLogin) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.tokens = tokens;
        this.sesiones = sesiones;
        this.reseteos = reseteos;
        this.allowedOrigins = allowedOrigins.getIfAvailable();
        this.limiteLogin = limiteLogin.getIfAvailable();
        this.hashSenuelo = hasher.hash(java.util.UUID.randomUUID().toString());
    }

    /**
     * Plain-Java overload for tests that predate the bootstrap-CSRF check; with no allow-list a
     * nonce-less refresh is never admitted.
     */
    public AuthEndpoints(UsuarioRepository usuarios,
                         PasswordHasher hasher,
                         TokenService tokens,
                         RefreshTokenService sesiones,
                         PasswordResetService reseteos) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.tokens = tokens;
        this.sesiones = sesiones;
        this.reseteos = reseteos;
        this.allowedOrigins = null;
        this.limiteLogin = null;
        this.hashSenuelo = hasher.hash(java.util.UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthDtos.Token>> login(@RequestBody Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");

        if (StringUtils.isBlank(username) || StringUtils.isEmpty(password)) {
            hasher.verify("", hashSenuelo);
            throw rechazar();
        }

        if (limiteLogin != null && !limiteLogin.permitir(username)) {
            LOG.info("[AUTH] login frenado por rate limit");
            throw demasiadosIntentos();
        }

        Optional<UsuarioRepository.Cuenta> cuenta = usuarios.buscarActivaPorUsername(username);

        String hashGuardado = cuenta.map(UsuarioRepository.Cuenta::passwordHash).orElse(hashSenuelo);
        boolean coincide = hasher.verify(password, hashGuardado);

        if (cuenta.isEmpty() || !coincide) {
            LOG.info("[AUTH] login rechazado para '{}'", username);
            // Counted whether or not the account exists: counting only real ones would make the 429
            // an oracle.
            if (limiteLogin != null) limiteLogin.registrarFallo(username);
            throw rechazar();
        }

        UsuarioRepository.Cuenta usuario = cuenta.get();
        if (limiteLogin != null) limiteLogin.limpiarCuenta(username);
        AuthDtos.Token resp = cuerpoDeAcceso(tokens.emitir(usuario.id()));

        Optional<RefreshTokenService.Sesion> sesion =
                sesiones.abrirSiCorresponde(usuario.id(), usuario.esServicio());
        if (sesion.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(resp));
        }
        return conSesion(resp, sesion.get());
    }

    /**
     * The refresh token arrives only as a cookie and the nonce only as a header: that split is the
     * CSRF defence (a cross-site page can make the browser send the cookie, not set a custom
     * header).
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthDtos.Token>> refresh(
            @CookieValue(name = RefreshCookie.NOMBRE, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String nonce,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Sec-Fetch-Site", required = false) String secFetchSite) {

        boolean bootstrapAdmitido = esBootstrapAdmitido(origin, secFetchSite);
        RefreshTokenService.Resultado resultado = sesiones.rotar(refreshToken, nonce, bootstrapAdmitido);

        if (resultado instanceof RefreshTokenService.Rotada rotada) {
            return conSesion(cuerpoDeAcceso(rotada.accessToken()), rotada.sesion());
        }
        if (resultado instanceof RefreshTokenService.Replay replay) {
            return conSesion(cuerpoDeAcceso(replay.accessToken()), replay.sesion());
        }
        if (resultado instanceof RefreshTokenService.CsrfInvalido) {
            throw error(403, "csrf_invalido", "Falta o no coincide el nonce de refresco");
        }
        if (resultado instanceof RefreshTokenService.ReusoDetectado) {
            // The family is already revoked; clearing the cookie stops the browser re-presenting a
            // dead token.
            throw error(401, "sesion_invalidada",
                    "La sesión fue invalidada por reuso del token. Volvé a iniciar sesión.")
                    .withHeader(HttpHeaders.SET_COOKIE, RefreshCookie.limpiar().toString());
        }
        throw error(401, "refresh_invalido", "Volvé a iniciar sesión.")
                .withHeader(HttpHeaders.SET_COOKIE, RefreshCookie.limpiar().toString());
    }

    /**
     * Plain-Java overload for tests that predate the bootstrap headers; without them the bootstrap
     * is never admitted.
     */
    public ResponseEntity<ApiResponse<AuthDtos.Token>> refresh(String refreshToken, String nonce) {
        return refresh(refreshToken, nonce, null, null);
    }

    /** Admits a nonce-less refresh only when BOTH hold: Either header missing fails closed. */
    private boolean esBootstrapAdmitido(String origin, String secFetchSite) {
        if (allowedOrigins == null) {
            return false;
        }
        if (origin == null || secFetchSite == null) {
            return false;
        }
        if (!allowedOrigins.esPermitido(origin)) {
            return false;
        }
        return SEC_FETCH_SITE_CONFIABLE.contains(secFetchSite);
    }

    /**
     * Reached only after the chain required an authenticated subject; {@code roles} is an array
     * because {@code usuario_rol} admits more than one.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthDtos.Me>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedSubject subject = (AuthenticatedSubject) auth.getPrincipal();

        List<String> roles = new ArrayList<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String nombre = authority.getAuthority();
            roles.add(nombre.startsWith("ROLE_") ? nombre.substring("ROLE_".length()) : nombre);
        }
        return ResponseEntity.ok(ApiResponse.ok(new AuthDtos.Me(subject.username(), roles)));
    }

    /**
     * Logout is {@code DELETE /api/auth/refresh} because the cookie's Path is
     * {@code /api/auth/refresh}: on any other path the browser would not send it and the server
     * could not tell which family to revoke, clearing the browser copy while leaving the session
     * alive.
     */
    @DeleteMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthDtos.Logout>> logout(
            @CookieValue(name = RefreshCookie.NOMBRE, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String nonce) {

        boolean cerrada = sesiones.cerrar(refreshToken, nonce);

        // The cookie is cleared either way: a caller holding an unrecognised token still wants it
        // gone.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.limpiar().toString())
                .body(ApiResponse.ok(new AuthDtos.Logout(cerrada)));
    }

    /**
     * Always 202, same body, same speed: answering differently for a known address would hand out a
     * list of this system's users. See {@link PasswordResetService} for the timing half.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<MensajeDto>> pedirReseteo(@RequestBody(required = false) Map<String, String> body,
                                                                HttpServletRequest request) {
        String direccion = body == null ? null : body.get("email");
        reseteos.solicitar(direccion, request == null ? null : request.getRemoteAddr());

        return ResponseEntity.accepted().body(ApiResponse.ok(new MensajeDto(
                "Si la dirección corresponde a una cuenta, va a recibir un enlace.")));
    }

    /** Consumes the token and sets the new password, or refuses without saying why. */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<OpResult>> confirmarReseteo(@RequestBody(required = false) Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        String nueva = body == null ? null : body.get("password");

        if (!reseteos.confirmar(token, nueva)) {
            throw error(400, "reseteo_invalido",
                    "El enlace no sirve, ya fue usado o venció, o la contraseña es muy corta "
                            + "(mínimo 8 caracteres). Pedí uno nuevo.");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true,
                "Contraseña cambiada. Todas las sesiones abiertas fueron cerradas.")));
    }

    private static AuthDtos.Token cuerpoDeAcceso(String accessToken) {
        return new AuthDtos.Token(accessToken, "Bearer", TokenService.TTL.toSeconds(), null);
    }

    /**
     * The refresh token goes in the cookie and NEVER in the body; the nonce goes in the body only.
     */
    private static ResponseEntity<ApiResponse<AuthDtos.Token>> conSesion(AuthDtos.Token resp,
                                                                        RefreshTokenService.Sesion sesion) {
        resp.setCsrfNonce(sesion.csrfNonce());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshCookie.emitir(sesion.refreshToken(), RefreshTokenService.VIDA).toString())
                .body(ApiResponse.ok(resp));
    }

    private static ApiException rechazar() {
        return error(401, "credenciales_invalidas", "Usuario o contraseña incorrectos");
    }

    private static ApiException demasiadosIntentos() {
        return error(429, "demasiados_intentos",
                "Demasiados intentos fallidos. Probá de nuevo en "
                        + LoginRateLimiter.VENTANA.toMinutes() + " minutos.")
                .withHeader(HttpHeaders.RETRY_AFTER, String.valueOf(LoginRateLimiter.VENTANA.toSeconds()));
    }

    private static ApiException error(int status, String codigo, String mensaje) {
        return new ApiException(HttpStatus.valueOf(status), codigo, mensaje);
    }
}
