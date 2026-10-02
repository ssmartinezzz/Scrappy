package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.ActorResolver;
import ar.scraper.security.PasswordHasher;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.OpResult;
import ar.scraper.web.dto.UsuariosDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

/** Born gated: these routes ship AFTER enforcement. */
@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioAdminEndpoints {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioAdminEndpoints.class);

    private static final int MIN_PASSWORD = 8;

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final ActorResolver actorResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<List<UsuariosDtos.Usuario>>> listar() {
        List<UsuariosDtos.Usuario> lista = usuarios.listar().stream()
                .map(f -> new UsuariosDtos.Usuario(String.valueOf(f.id()), f.username(), f.email(),
                        f.activo(), f.esServicio(), List.copyOf(f.roles())))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(lista));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UsuariosDtos.Creado>> crear(@RequestBody(required = false) Map<String, String> body) {
        String username = valor(body, "username");
        String password = valor(body, "password");
        String email = valor(body, "email");
        String rol = valor(body, "role");
        if (rol == null) {
            rol = valor(body, "rol");
        }

        if (username == null || password == null || rol == null) {
            throw error(400, "faltan_campos", "username, password y role son obligatorios.");
        }
        if (password.length() < MIN_PASSWORD) {
            throw error(400, "password_corta",
                    "La contraseña debe tener al menos " + MIN_PASSWORD + " caracteres.");
        }
        if (!usuarios.rolesValidos().contains(rol)) {
            throw error(400, "rol_invalido",
                    "Rol inválido. Los válidos son: " + String.join(", ", usuarios.rolesValidos()) + ".");
        }

        Optional<UUID> creada;
        try {
            creada = usuarios.crearConRol(username, normalizar(email), hasher.hash(password), rol);
        } catch (IllegalArgumentException e) {
            // The repository re-checks the vocabulary; reaching here means the two checks disagree
            // (a bug).
            LOG.warn("[ADMIN] rol rechazado por el repositorio: {}", e.getMessage());
            throw error(400, "rol_invalido", "Rol inválido.");
        }
        if (creada.isEmpty()) {
            throw error(409, "username_tomado", "Ya existe una cuenta con ese username.");
        }

        LOG.info("[ADMIN] '{}' creó la cuenta '{}' con rol {}", actorResolver.current(), username, rol);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                new UsuariosDtos.Creado(true, String.valueOf(creada.get()), username, rol)));
    }

    @PutMapping("/{username}/rol")
    public ResponseEntity<ApiResponse<OpResult>> cambiarRol(@PathVariable String username,
                                                 @RequestBody(required = false) Map<String, String> body) {
        String rol = valor(body, "role");
        if (rol == null) {
            rol = valor(body, "rol");
        }
        if (rol == null || !usuarios.rolesValidos().contains(rol)) {
            throw error(400, "rol_invalido",
                    "Rol inválido. Los válidos son: " + String.join(", ", usuarios.rolesValidos()) + ".");
        }
        if (!usuarios.existe(username)) {
            throw error(404, "no_existe", "No existe esa cuenta.");
        }
        if (!"ADMIN".equals(rol) && dejariaSinAdministradores(username)) {
            throw error(409, "ultimo_admin",
                    "Es la única cuenta ADMIN activa. Promové a otra antes de degradar ésta, "
                            + "o el sistema queda sin administrador y sólo se arregla por SQL.");
        }

        usuarios.reemplazarRol(username, rol);
        LOG.info("[ADMIN] '{}' cambió el rol de '{}' a {}", actorResolver.current(), username, rol);
        return ok("Rol actualizado.");
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<ApiResponse<OpResult>> desactivar(@PathVariable String username) {
        if (!usuarios.existe(username)) {
            throw error(404, "no_existe", "No existe esa cuenta.");
        }
        if (dejariaSinAdministradores(username)) {
            throw error(409, "ultimo_admin",
                    "Es la única cuenta ADMIN activa. Desactivarla dejaría el sistema sin "
                            + "administrador, y sólo se recupera por SQL.");
        }

        usuarios.desactivar(username);
        LOG.info("[ADMIN] '{}' desactivó la cuenta '{}'", actorResolver.current(), username);
        return ok("Cuenta desactivada. Su próximo request va a ser rechazado.");
    }

    @PutMapping("/{username}/activar")
    public ResponseEntity<ApiResponse<OpResult>> reactivar(@PathVariable String username) {
        if (!usuarios.reactivar(username)) {
            throw error(404, "no_existe", "No existe esa cuenta.");
        }
        LOG.info("[ADMIN] '{}' reactivó la cuenta '{}'", actorResolver.current(), username);
        return ok("Cuenta reactivada.");
    }

    /** True when {@code username} is the only active ADMIN left. */
    private boolean dejariaSinAdministradores(String username) {
        return usuarios.esAdminActivo(username) && usuarios.adminsActivos() <= 1;
    }

    private static String valor(Map<String, String> body, String clave) {
        if (body == null) {
            return null;
        }
        String v = body.get(clave);
        return StringUtils.isBlank(v) ? null : v.trim();
    }

    private static String normalizar(String email) {
        return email == null ? null : email.toLowerCase();
    }

    private static ResponseEntity<ApiResponse<OpResult>> ok(String mensaje) {
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, mensaje)));
    }

    private static ApiException error(int status, String codigo, String mensaje) {
        return new ApiException(HttpStatus.valueOf(status), codigo, mensaje);
    }
}
