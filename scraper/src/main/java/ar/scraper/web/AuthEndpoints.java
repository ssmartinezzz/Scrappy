package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.TokenService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Authentication endpoints. Currently one: {@code POST /api/auth/login}.
 *
 * <p><b>A controller of its own</b>, following {@code CronApiController} rather
 * than the {@code ApiController}-delegates-to-{@code *Endpoints} shape used by
 * the catalogue surfaces. Authentication has no dependency on the scraper, the
 * aggregator or the catalogue, and threading it through a constructor that
 * already takes twelve collaborators (and carries legacy overloads for its
 * existing tests) would couple it to all of them for nothing.</p>
 *
 * <p><b>This endpoint gates nothing.</b> It mints a token; no filter chain
 * exists yet to check one. Every other route stays exactly as open as it was.</p>
 *
 * <h3>Why every failure looks identical</h3>
 *
 * <p>Unknown username, wrong password, disabled account and malformed body all
 * return the same 401 with the same body. Distinguishing them would turn the
 * endpoint into an oracle for which usernames exist, which is the first step of
 * a credential-stuffing run.</p>
 *
 * <p>The same reasoning extends to <b>timing</b>: an unknown username is
 * verified against a fixed dummy hash instead of returning early, so that
 * "no such user" costs the same Argon2id work as "wrong password". Without it
 * the two branches differ by ~76 ms — comfortably measurable over a network,
 * and the body being identical would not hide it.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthEndpoints {

    private static final Logger LOG = LoggerFactory.getLogger(AuthEndpoints.class);

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final TokenService tokens;

    /**
     * A real Argon2id hash of a value nobody knows, verified against when the
     * account does not exist. Computed once at construction: it is the cost of
     * the comparison that has to match, not the cost of producing the hash.
     */
    private final String hashSenuelo;

    public AuthEndpoints(UsuarioRepository usuarios, PasswordHasher hasher, TokenService tokens) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.tokens = tokens;
        this.hashSenuelo = hasher.hash(java.util.UUID.randomUUID().toString());
    }

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

        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("accessToken", tokens.emitir(cuenta.get().id()));
        resp.put("tokenType", "Bearer");
        resp.put("expiresIn", TokenService.TTL.toSeconds());
        return ResponseEntity.ok(resp);
    }

    private static ResponseEntity<ObjectNode> rechazar() {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("error", "credenciales_invalidas");
        resp.put("mensaje", "Usuario o contraseña incorrectos");
        return ResponseEntity.status(401).body(resp);
    }
}
