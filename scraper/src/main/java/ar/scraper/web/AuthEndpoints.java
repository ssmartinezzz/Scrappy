package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshCookie;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.TokenService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Authentication endpoints: login, refresh, logout.
 *
 * <p><b>A controller of its own</b>, following {@code CronApiController} rather
 * than the {@code ApiController}-delegates-to-{@code *Endpoints} shape used by
 * the catalogue surfaces. Authentication has no dependency on the scraper, the
 * aggregator or the catalogue, and threading it through a constructor that
 * already takes twelve collaborators would couple it to all of them for nothing.</p>
 *
 * <p><b>None of this gates anything yet.</b> No {@code SecurityFilterChain}
 * exists; tokens are minted and rotated, and no route checks one. The refresh
 * surface additionally has <b>no consumer at all</b> right now — the CLI
 * re-authenticates from its own {@code .env} and never holds a refresh token, so
 * this exists for a browser client that is a separate piece of work. It is built
 * ahead of that client on purpose, so the client is only ever a matter of
 * consuming it.</p>
 *
 * <h3>Why every login failure looks identical</h3>
 *
 * <p>Unknown username, wrong password, disabled account and malformed body all
 * return the same 401 with the same body. Distinguishing them would turn the
 * endpoint into an oracle for which usernames exist. The same reasoning extends
 * to <b>timing</b>: an unknown username is verified against a fixed decoy hash
 * instead of returning early, so "no such user" costs the same Argon2id work as
 * "wrong password" — otherwise the two branches differ by ~76 ms, comfortably
 * measurable over a network, and an identical body would not hide it.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthEndpoints {

    private static final Logger LOG = LoggerFactory.getLogger(AuthEndpoints.class);

    /** Header carrying the double-submit nonce. See {@link RefreshTokenService#rotar}. */
    public static final String CSRF_HEADER = "X-Refresh-CSRF";

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final RefreshTokenService sesiones;

    /**
     * A real Argon2id hash of a value nobody knows, verified against when the
     * account does not exist. Computed once at construction: it is the cost of
     * the comparison that has to match, not the cost of producing the hash.
     */
    private final String hashSenuelo;

    public AuthEndpoints(UsuarioRepository usuarios,
                         PasswordHasher hasher,
                         TokenService tokens,
                         RefreshTokenService sesiones) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.tokens = tokens;
        this.sesiones = sesiones;
        this.hashSenuelo = hasher.hash(java.util.UUID.randomUUID().toString());
    }

    // ── login ────────────────────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<ObjectNode> login(@RequestBody Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");

        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            // Still pay the verification cost: an empty body returning instantly
            // would be its own, smaller, oracle.
            hasher.verify("", hashSenuelo);
            return rechazar();
        }

        Optional<UsuarioRepository.Cuenta> cuenta = usuarios.buscarActivaPorUsername(username);

        // The lookup already excludes activo = FALSE, so a disabled account is
        // indistinguishable from an unknown one here — by construction, not by
        // a branch somebody has to remember to write.
        String hashGuardado = cuenta.map(UsuarioRepository.Cuenta::passwordHash).orElse(hashSenuelo);
        boolean coincide = hasher.verify(password, hashGuardado);

        if (cuenta.isEmpty() || !coincide) {
            LOG.info("[AUTH] login rechazado para '{}'", username);
            return rechazar();
        }

        UsuarioRepository.Cuenta usuario = cuenta.get();
        ObjectNode resp = cuerpoDeAcceso(tokens.emitir(usuario.id()));

        // A service account gets no rotating session: the CLI re-authenticates
        // from .env, so a fourteen-day credential would sit there unused.
        Optional<RefreshTokenService.Sesion> sesion =
                sesiones.abrirSiCorresponde(usuario.id(), usuario.esServicio());
        if (sesion.isEmpty()) {
            return ResponseEntity.ok(resp);
        }
        return conSesion(resp, sesion.get());
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    /**
     * Rotates the session.
     *
     * <p>The refresh token arrives only as a cookie and the nonce only as a
     * header, and that split is the CSRF defence: a cross-site page can make the
     * browser send the cookie, but it cannot set a custom header.</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<ObjectNode> refresh(
            @CookieValue(name = RefreshCookie.NOMBRE, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String nonce) {

        RefreshTokenService.Resultado resultado = sesiones.rotar(refreshToken, nonce);

        if (resultado instanceof RefreshTokenService.Rotada rotada) {
            return conSesion(cuerpoDeAcceso(rotada.accessToken()), rotada.sesion());
        }
        if (resultado instanceof RefreshTokenService.Replay replay) {
            return conSesion(cuerpoDeAcceso(replay.accessToken()), replay.sesion());
        }
        if (resultado instanceof RefreshTokenService.CsrfInvalido) {
            return error(403, "csrf_invalido", "Falta o no coincide el nonce de refresco");
        }
        if (resultado instanceof RefreshTokenService.ReusoDetectado) {
            // The family is already revoked. Clearing the cookie stops the
            // browser from re-presenting a token that can only fail from here on.
            return ResponseEntity.status(401)
                    .header(HttpHeaders.SET_COOKIE, RefreshCookie.limpiar().toString())
                    .body(cuerpoDeError("sesion_invalidada",
                            "La sesión fue invalidada por reuso del token. Volvé a iniciar sesión."));
        }
        return ResponseEntity.status(401)
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.limpiar().toString())
                .body(cuerpoDeError("refresh_invalido", "Volvé a iniciar sesión."));
    }

    // ── logout ───────────────────────────────────────────────────────────────

    /**
     * Logout, as {@code DELETE /api/auth/refresh} rather than
     * {@code POST /api/auth/logout}.
     *
     * <p>Not a stylistic choice: the cookie's {@code Path} is
     * {@code /api/auth/refresh}, so the browser would not attach it to any other
     * path — and without the cookie the server cannot tell which family to
     * revoke. Logging out somewhere else would clear the browser's copy while
     * leaving the session alive on the server, which is the opposite of what
     * logout means.</p>
     */
    @DeleteMapping("/refresh")
    public ResponseEntity<ObjectNode> logout(
            @CookieValue(name = RefreshCookie.NOMBRE, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String nonce) {

        boolean cerrada = sesiones.cerrar(refreshToken, nonce);

        // The cookie is cleared either way. A caller holding a token we do not
        // recognise still wants it gone from their browser, and refusing to
        // clear it would leave them re-presenting something that can never work.
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("cerrada", cerrada);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.limpiar().toString())
                .body(resp);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ObjectNode cuerpoDeAcceso(String accessToken) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("accessToken", accessToken);
        resp.put("tokenType", "Bearer");
        resp.put("expiresIn", TokenService.TTL.toSeconds());
        return resp;
    }

    /** The refresh token goes in the cookie and NEVER in the body; the nonce goes in the body only. */
    private static ResponseEntity<ObjectNode> conSesion(ObjectNode resp, RefreshTokenService.Sesion sesion) {
        resp.put("csrfNonce", sesion.csrfNonce());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshCookie.emitir(sesion.refreshToken(), RefreshTokenService.VIDA).toString())
                .body(resp);
    }

    private static ResponseEntity<ObjectNode> rechazar() {
        return error(401, "credenciales_invalidas", "Usuario o contraseña incorrectos");
    }

    private static ResponseEntity<ObjectNode> error(int status, String codigo, String mensaje) {
        return ResponseEntity.status(status).body(cuerpoDeError(codigo, mensaje));
    }

    private static ObjectNode cuerpoDeError(String codigo, String mensaje) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("error", codigo);
        resp.put("mensaje", mensaje);
        return resp;
    }
}
