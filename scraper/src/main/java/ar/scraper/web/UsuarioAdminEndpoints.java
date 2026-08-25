package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.identity.ActorResolver;
import ar.scraper.security.PasswordHasher;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Account administration: list, create, change role, deactivate, reactivate.
 *
 * <h3>Born gated</h3>
 *
 * <p>These routes ship <b>after</b> enforcement, never with it. The
 * {@code /api/usuarios/**} ADMIN rule has been sitting in
 * {@code ApiRoutePolicy.TABLE} since the enforcement slice, matching nothing, so
 * there is no instant in which a user-creation endpoint exists without a rule
 * above it. Shipping them together would have allowed a partial revert that
 * dropped the filter chain and left the routes — and an ungated
 * {@code POST /api/usuarios} lets any unauthenticated caller mint themselves an
 * ADMIN account, which is strictly worse than not having the feature.</p>
 *
 * <h3>Backend only, deliberately</h3>
 *
 * <p>No UI ships with this, in this change or the frontend one. Administration
 * is a {@code curl} away for someone who already holds ADMIN, and a screen for
 * it is a separate decision with its own design.</p>
 *
 * <h3>Deactivate, never delete</h3>
 *
 * <p>Removal sets {@code activo = FALSE}. A DELETE would cascade away the
 * account's role grants, refresh tokens and reset tokens, and orphan the audit
 * trail that says what they did — and it would take their personal rows with it,
 * since ownership cascades. Deactivation reuses the revocation behaviour already
 * built: the next request with their still-valid token is refused, with no new
 * mechanism and no token reissue.</p>
 *
 * <h3>The last administrator cannot be removed</h3>
 *
 * <p>Not in the spec, and added anyway: deactivating or demoting the only active
 * ADMIN leaves an application nobody can administer, recoverable only by direct
 * SQL against the database. That is a one-click, silent, unrecoverable-through-
 * the-API state, and refusing it costs one query.</p>
 */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioAdminEndpoints {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioAdminEndpoints.class);

    private static final int MIN_PASSWORD = 8;

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final ActorResolver actorResolver;

    public UsuarioAdminEndpoints(UsuarioRepository usuarios,
                                 PasswordHasher hasher,
                                 ActorResolver actorResolver) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    public ResponseEntity<ArrayNode> listar() {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (UsuarioRepository.Ficha f : usuarios.listar()) {
            ObjectNode n = arr.addObject();
            n.put("id", String.valueOf(f.id()));
            n.put("username", f.username());
            n.put("email", f.email());
            n.put("activo", f.activo());
            n.put("esServicio", f.esServicio());
            ArrayNode roles = n.putArray("roles");
            f.roles().forEach(roles::add);
        }
        return ResponseEntity.ok(arr);
    }

    @PostMapping
    public ResponseEntity<ObjectNode> crear(@RequestBody(required = false) Map<String, String> body) {
        String username = valor(body, "username");
        String password = valor(body, "password");
        String email = valor(body, "email");
        String rol = valor(body, "role");
        if (rol == null) {
            rol = valor(body, "rol");
        }

        if (username == null || password == null || rol == null) {
            return error(400, "faltan_campos", "username, password y role son obligatorios.");
        }
        if (password.length() < MIN_PASSWORD) {
            return error(400, "password_corta",
                    "La contraseña debe tener al menos " + MIN_PASSWORD + " caracteres.");
        }
        if (!usuarios.rolesValidos().contains(rol)) {
            return error(400, "rol_invalido",
                    "Rol inválido. Los válidos son: " + String.join(", ", usuarios.rolesValidos()) + ".");
        }

        Optional<UUID> creada;
        try {
            creada = usuarios.crearConRol(username, normalizar(email), hasher.hash(password), rol);
        } catch (IllegalArgumentException e) {
            // The repository re-checks the vocabulary; reaching here means the two
            // checks disagree, which is a bug rather than a bad request.
            LOG.warn("[ADMIN] rol rechazado por el repositorio: {}", e.getMessage());
            return error(400, "rol_invalido", "Rol inválido.");
        }
        if (creada.isEmpty()) {
            return error(409, "username_tomado", "Ya existe una cuenta con ese username.");
        }

        LOG.info("[ADMIN] '{}' creó la cuenta '{}' con rol {}", actorResolver.current(), username, rol);
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("ok", true);
        resp.put("id", String.valueOf(creada.get()));
        resp.put("username", username);
        resp.put("role", rol);
        return ResponseEntity.status(201).body(resp);
    }

    @PutMapping("/{username}/rol")
    public ResponseEntity<ObjectNode> cambiarRol(@PathVariable String username,
                                                 @RequestBody(required = false) Map<String, String> body) {
        String rol = valor(body, "role");
        if (rol == null) {
            rol = valor(body, "rol");
        }
        if (rol == null || !usuarios.rolesValidos().contains(rol)) {
            return error(400, "rol_invalido",
                    "Rol inválido. Los válidos son: " + String.join(", ", usuarios.rolesValidos()) + ".");
        }
        if (!usuarios.existe(username)) {
            return error(404, "no_existe", "No existe esa cuenta.");
        }
        if (!"ADMIN".equals(rol) && dejariaSinAdministradores(username)) {
            return error(409, "ultimo_admin",
                    "Es la única cuenta ADMIN activa. Promové a otra antes de degradar ésta, "
                            + "o el sistema queda sin administrador y sólo se arregla por SQL.");
        }

        usuarios.reemplazarRol(username, rol);
        LOG.info("[ADMIN] '{}' cambió el rol de '{}' a {}", actorResolver.current(), username, rol);
        return ok("Rol actualizado.");
    }

    /** Deactivation. Not a delete — see the class javadoc. */
    @DeleteMapping("/{username}")
    public ResponseEntity<ObjectNode> desactivar(@PathVariable String username) {
        if (!usuarios.existe(username)) {
            return error(404, "no_existe", "No existe esa cuenta.");
        }
        if (dejariaSinAdministradores(username)) {
            return error(409, "ultimo_admin",
                    "Es la única cuenta ADMIN activa. Desactivarla dejaría el sistema sin "
                            + "administrador, y sólo se recupera por SQL.");
        }

        usuarios.desactivar(username);
        LOG.info("[ADMIN] '{}' desactivó la cuenta '{}'", actorResolver.current(), username);
        return ok("Cuenta desactivada. Su próximo request va a ser rechazado.");
    }

    @PutMapping("/{username}/activar")
    public ResponseEntity<ObjectNode> reactivar(@PathVariable String username) {
        if (!usuarios.reactivar(username)) {
            return error(404, "no_existe", "No existe esa cuenta.");
        }
        LOG.info("[ADMIN] '{}' reactivó la cuenta '{}'", actorResolver.current(), username);
        return ok("Cuenta reactivada.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** True when {@code username} is the only active ADMIN left. */
    private boolean dejariaSinAdministradores(String username) {
        return usuarios.esAdminActivo(username) && usuarios.adminsActivos() <= 1;
    }

    private static String valor(Map<String, String> body, String clave) {
        if (body == null) {
            return null;
        }
        String v = body.get(clave);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** {@code email} is optional, and the schema requires it lowercase. */
    private static String normalizar(String email) {
        return email == null ? null : email.toLowerCase();
    }

    private static ResponseEntity<ObjectNode> ok(String mensaje) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("ok", true);
        resp.put("mensaje", mensaje);
        return ResponseEntity.ok(resp);
    }

    private static ResponseEntity<ObjectNode> error(int status, String codigo, String mensaje) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("ok", false);
        resp.put("error", codigo);
        resp.put("mensaje", mensaje);
        return ResponseEntity.status(status).body(resp);
    }
}
