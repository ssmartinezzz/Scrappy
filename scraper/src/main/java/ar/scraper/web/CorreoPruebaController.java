package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.api.ApiException;
import ar.scraper.notificaciones.DiagnosticoSmtp;
import ar.scraper.notificaciones.EstadoSmtp;
import ar.scraper.security.ActorResolver;
import ar.scraper.web.dto.OpResult;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notificaciones")
@RequiredArgsConstructor
public class CorreoPruebaController {

    private final DiagnosticoSmtp diagnostico;
    private final UsuarioRepository usuarios;
    private final ActorResolver actorResolver;

    @PostMapping("/correo-prueba")
    public ResponseEntity<ApiResponse<OpResult>> enviar(@RequestBody(required = false) Map<String, Object> body) {
        Object pedido = body == null ? null : body.get("destino");
        String destino = DireccionDeCorreo.normalizar(pedido);
        if (destino == null) {
            destino = usuarios.buscarActivaPorUsername(actorResolver.current())
                    .map(UsuarioRepository.Cuenta::email).orElse(null);
        }
        if (destino == null) {
            throw new ApiException(HttpStatus.CONFLICT, "sin_destino",
                    "Tu cuenta no tiene email: cargalo en Cuentas o pasá un destino.");
        }
        EstadoSmtp estado = diagnostico.enviarPrueba(destino);
        return switch (estado.nivel()) {
            case OK -> ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, estado.mensaje())));
            case NO_CONFIGURADO -> throw new ApiException(HttpStatus.CONFLICT, "smtp_no_configurado",
                    estado.mensaje() + " " + estado.pista());
            case FALLO -> throw new ApiException(HttpStatus.BAD_GATEWAY, "smtp_fallo",
                    estado.mensaje() + " " + StringUtils.defaultString(estado.pista()));
        };
    }
}
