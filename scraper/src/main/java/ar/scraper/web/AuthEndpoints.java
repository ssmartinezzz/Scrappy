package ar.scraper.web;

import ar.scraper.config.AllowedOrigins;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.AuthenticatedSubject;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshCookie;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.TokenService;
import ar.scraper.security.reset.PasswordResetService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Authentication endpoints: login, refresh, logout.
 *
 * <p><b>A controller of its own</b>, following {@code CronApiController} rather
 * than the {@code ApiController}-delegates-to-{@code *Endpoints} shape used by
 * the catalogue surfaces. Authentication has no dependency on the scraper, the
 * aggregator or the catalogue, and threading it through a constructor that
 * already takes twelve collaborators would couple it to all of them for nothing.</p>
 *
 * <p><b>This gates everything now.</b> When this class was first written nothing
 * checked a token, and the paragraph here said so; that stopped being true two
 * slices later and the comment did not follow. {@link ar.scraper.security.SecurityConfig}
 * and {@code JwtAuthFilter} now gate every {@code /api/*} route through
 * {@link ar.scraper.security.ApiRoutePolicy#TABLE}, which ends in
 * {@code denyAll()} — a route with no row is refused, not allowed.</p>
 *
 * <p>The refresh surface <b>has a consumer</b>: the browser client at
 * {@code frontend/src/lib/authSession.js} calls {@code POST} and
 * {@code DELETE /api/auth/refresh}. What remains true is the CLI half — it
 * re-authenticates from its own {@code .env} and never holds a refresh token.</p>
 *
 * <p>Kept as a warning rather than deleted: on an auth file, a comment that
 * misdescribes the present is not a cosmetic problem. "None of this gates
 * anything" makes deleting {@code SecurityConfig} look harmless to whoever
 * refactors next.</p>
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

    /** {@code Sec-Fetch-Site} values a nonce-less bootstrap refresh trusts. See {@link #esBootstrapAdmitido}. */
    private static final Set<String> SEC_FETCH_SITE_CONFIABLE = Set.of("same-origin", "same-site");

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final RefreshTokenService sesiones;
    private final PasswordResetService reseteos;
    private final AllowedOrigins allowedOrigins;

    /**
     * A real Argon2id hash of a value nobody knows, verified against when the
     * account does not exist. Computed once at construction: it is the cost of
     * the comparison that has to match, not the cost of producing the hash.
     */
    private final String hashSenuelo;

    /**
     * The sole Spring-managed constructor — {@code SpringWiringTest} enforces
     * exactly one {@code @Autowired} constructor per bean, so a second
     * {@code @Autowired(required = false)} overload (the earlier shape of this
     * change) is not an option here. {@code AllowedOrigins} arrives via
     * {@link ObjectProvider} instead of directly: several existing
     * {@code @WebMvcTest} slices (predating frontend-auth-ui Phase 2) construct
     * this controller with no {@code AllowedOrigins} bean registered at all, and
     * {@code ObjectProvider} is always resolvable — {@link ObjectProvider#getIfAvailable()}
     * simply returns {@code null} where the bean is absent, which
     * {@link #esBootstrapAdmitido} already treats as "never admit". A direct
     * {@code AllowedOrigins} parameter would instead fail those slices outright.
     */
    @Autowired
    public AuthEndpoints(UsuarioRepository usuarios,
                         PasswordHasher hasher,
                         TokenService tokens,
                         RefreshTokenService sesiones,
                         PasswordResetService reseteos,
                         ObjectProvider<AllowedOrigins> allowedOrigins) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.tokens = tokens;
        this.sesiones = sesiones;
        this.reseteos = reseteos;
        this.allowedOrigins = allowedOrigins.getIfAvailable();
        this.hashSenuelo = hasher.hash(java.util.UUID.randomUUID().toString());
    }

    /**
     * Legacy 5-arg convenience overload, kept for the tests that predate the
     * bootstrap-CSRF check and construct this class directly in plain Java (not
     * through Spring). Not {@code @Autowired} — Spring never sees it as a
     * candidate — so it exists purely so those call sites keep compiling
     * unedited. With no allow-list to consult, a nonce-less refresh is never
     * admitted through the bootstrap path — exactly this class's behaviour
     * before Phase 2, not a new leniency.
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
     *
     * <h3>The bootstrap path — no nonce at all</h3>
     *
     * <p>A cold page load holds no nonce in memory yet, so its first refresh
     * necessarily omits {@link #CSRF_HEADER}. {@link #esBootstrapAdmitido} decides
     * whether that absence is forgiven, from {@code Origin} and
     * {@code Sec-Fetch-Site} alone — never from anything the client could not be
     * trusted to send honestly. The verdict is threaded into
     * {@link RefreshTokenService#rotar} as an explicit argument; this method does
     * not otherwise change what "valid" means.</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<ObjectNode> refresh(
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

    /**
     * Legacy 2-arg overload for tests that construct calls directly (bypassing
     * HTTP) and predate the bootstrap-CSRF headers. No {@code Origin} or
     * {@code Sec-Fetch-Site} means {@link #esBootstrapAdmitido} answers
     * {@code false} regardless — identical to this endpoint's behaviour before
     * Phase 2, not a shortcut around it.
     */
    public ResponseEntity<ObjectNode> refresh(String refreshToken, String nonce) {
        return refresh(refreshToken, nonce, null, null);
    }

    /**
     * The bootstrap-CSRF admission check (frontend-auth-ui, design D1).
     *
     * <p>Admits a nonce-less refresh only when <b>both</b> hold: {@code Origin}
     * is present and an exact match of a configured allow-listed origin — port
     * included, unlike {@code SameSite}/cookie scoping — and
     * {@code Sec-Fetch-Site} is present and is {@code same-origin} or
     * {@code same-site}. Either header missing, or {@code Origin} not an exact
     * match, fails closed. {@code Sec-Fetch-Site} alone cannot discriminate a
     * legitimate cross-origin deployment (this application's own topology sends
     * {@code same-site} in both shipped installs) from a foreign {@code
     * localhost} port, which is why {@code Origin} carries the real weight here.
     */
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

    // ── me ───────────────────────────────────────────────────────────────────

    /**
     * Who the caller is, per the current filter chain's own read.
     *
     * <p>Reached only after {@link ar.scraper.security.SecurityConfig}'s chain
     * has already required a valid, authenticated subject for this route (its
     * {@link ar.scraper.security.ApiRoutePolicy} row is {@code AUTHENTICATED},
     * deliberately not on the permit list — see that table's comment). This
     * handler therefore never runs for an anonymous caller; the entry point
     * answers 401 first. Zero new queries: {@link ar.scraper.security.JwtAuthFilter}
     * already read the username and roles from the database for this exact
     * request, and this method only reads what it already put in the security
     * context.</p>
     *
     * <p>{@code roles} is a JSON array, never a scalar — {@code usuario_rol} is a
     * join table that admits more than one, and collapsing it here would bake in
     * an assumption the schema does not make.</p>
     */
    @GetMapping("/me")
    public ResponseEntity<ObjectNode> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedSubject subject = (AuthenticatedSubject) auth.getPrincipal();

        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("username", subject.username());
        ArrayNode roles = resp.putArray("roles");
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String nombre = authority.getAuthority();
            roles.add(nombre.startsWith("ROLE_") ? nombre.substring("ROLE_".length()) : nombre);
        }
        return ResponseEntity.ok(resp);
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

    // ── password reset ───────────────────────────────────────────────────────

    /**
     * Accepts a reset request and says nothing about the address.
     *
     * <p>Always 202, always the same body, always at the same speed. The
     * uniformity is not politeness: a form that answers differently for a known
     * address is a list of this system's users, handed out for free to anybody
     * with a wordlist. See {@link PasswordResetService} for how the timing half
     * is achieved.</p>
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<ObjectNode> pedirReseteo(@RequestBody(required = false) Map<String, String> body,
                                                   HttpServletRequest request) {
        String direccion = body == null ? null : body.get("email");
        reseteos.solicitar(direccion, request == null ? null : request.getRemoteAddr());

        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("mensaje", "Si la dirección corresponde a una cuenta, va a recibir un enlace.");
        return ResponseEntity.accepted().body(resp);
    }

    /** Consumes the token and sets the new password, or refuses without saying why. */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ObjectNode> confirmarReseteo(@RequestBody(required = false) Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        String nueva = body == null ? null : body.get("password");

        if (!reseteos.confirmar(token, nueva)) {
            return error(400, "reseteo_invalido",
                    "El enlace no sirve, ya fue usado o venció, o la contraseña es muy corta "
                            + "(mínimo 8 caracteres). Pedí uno nuevo.");
        }
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("ok", true);
        resp.put("mensaje", "Contraseña cambiada. Todas las sesiones abiertas fueron cerradas.");
        return ResponseEntity.ok(resp);
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
