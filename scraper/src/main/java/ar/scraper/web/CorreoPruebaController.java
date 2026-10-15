package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.api.LogSafe;
import ar.scraper.notificaciones.Enmascarar;
import ar.scraper.security.VentanaDeslizante;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.api.ApiException;
import ar.scraper.notificaciones.DiagnosticoSmtp;
import ar.scraper.notificaciones.EstadoSmtp;
import ar.scraper.security.ActorResolver;
import ar.scraper.web.dto.OpResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/notificaciones")
public class CorreoPruebaController {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoPruebaController.class);
    private static final Duration VENTANA = Duration.ofHours(1);
    private static final int ENVIOS_POR_HORA = 5;

    private final DiagnosticoSmtp diagnostico;
    private final UsuarioRepository usuarios;
    private final ActorResolver actorResolver;
    private final VentanaDeslizante envios;

    public CorreoPruebaController(DiagnosticoSmtp diagnostico, UsuarioRepository usuarios,
                                  ActorResolver actorResolver, Clock reloj) {
        this.diagnostico = diagnostico;
        this.usuarios = usuarios;
        this.actorResolver = actorResolver;
        this.envios = new VentanaDeslizante(reloj, VENTANA);
    }

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
        String actor = actorResolver.current();
        if (!envios.registrar(actor, ENVIOS_POR_HORA)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "demasiados_envios",
                    "Máximo " + ENVIOS_POR_HORA + " mails de prueba por hora. Probá de nuevo más tarde.")
                    .withHeader(HttpHeaders.RETRY_AFTER, String.valueOf(VENTANA.toSeconds()));
        }
        LOG.info("[ADMIN] '{}' manda un mail de prueba a {}", LogSafe.of(actor), LogSafe.of(Enmascarar.direccion(destino)));
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
