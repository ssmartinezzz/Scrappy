package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.notificaciones.PreferenciaAvisos;
import ar.scraper.notificaciones.PreferenciaAvisosPort;
import ar.scraper.security.ActorResolver;
import ar.scraper.security.Sujeto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The owner is always the authenticated subject; no id travels in the request. */
@RestController
@RequestMapping("/api/notificaciones")
@RequiredArgsConstructor
public class NotificacionesController {

    private static final String CAMPO = "notificarBajasPrecio";

    private final PreferenciaAvisosPort preferencias;
    private final ActorResolver actorResolver;

    @GetMapping("/preferencia")
    public ResponseEntity<ApiResponse<PreferenciaAvisos>> getPreferencia() {
        return ResponseEntity.ok(ApiResponse.ok(propia(Sujeto.de(actorResolver))));
    }

    @PutMapping("/preferencia")
    public ResponseEntity<ApiResponse<PreferenciaAvisos>> putPreferencia(@RequestBody Map<String, Object> body) {
        UUID yo = Sujeto.de(actorResolver);
        if (!(body.get(CAMPO) instanceof Boolean activar)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", CAMPO + " debe ser true o false");
        }
        if (!preferencias.actualizar(yo, activar)) {
            throw new ApiException(HttpStatus.CONFLICT, "sin_email",
                    "La cuenta no tiene una dirección de email a la que avisar.");
        }
        return ResponseEntity.ok(ApiResponse.ok(propia(yo)));
    }

    private PreferenciaAvisos propia(UUID yo) {
        return preferencias.leer(yo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "usuario_inexistente", "La cuenta no existe."));
    }
}
