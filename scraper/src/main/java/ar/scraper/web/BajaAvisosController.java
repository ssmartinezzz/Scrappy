package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.notificaciones.PreferenciaAvisosPort;
import ar.scraper.notificaciones.TokenBajaAvisos;
import ar.scraper.security.VentanaDeslizante;
import ar.scraper.web.dto.MensajeDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

/**
 * Public by necessity: the person clicking holds no session. The signed token is the whole
 * credential, and what it can do is turn one flag off, which is why a forged or unknown token gets
 * the very same answer as a real one.
 *
 * <p>{@code GET} only serves a page with a button, because mail scanners and link previews fetch
 * every URL in a message; {@code POST} is what changes state, and is what a mail provider's
 * one-click button sends (RFC 8058).
 */
@RestController
@RequestMapping("/api/notificaciones/baja")
public class BajaAvisosController {

    private static final Duration VENTANA = Duration.ofMinutes(1);
    private static final int PEDIDOS_POR_MINUTO_Y_POR_IP = 60;
    private static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'";

    private static final String PAGINA = """
            <!DOCTYPE html>
            <html lang="es"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>Avisos de baja de precio</title></head>
            <body style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:48px auto;padding:0 16px;">
            <h1 style="font-size:20px;">Dejar de recibir avisos de baja de precio</h1>
            <p>Si confirmás, no vas a recibir más mails cuando bajen los productos de tus favoritos.
            Podés volver a activarlos cuando quieras desde la sección de favoritos.</p>
            <form method="post" action="">
            <button type="submit" style="font-size:16px;padding:10px 20px;">Confirmar baja</button>
            </form></body></html>
            """;

    private final TokenBajaAvisos tokens;
    private final PreferenciaAvisosPort preferencias;
    private final VentanaDeslizante pedidos;

    public BajaAvisosController(TokenBajaAvisos tokens, PreferenciaAvisosPort preferencias, Clock reloj) {
        this.tokens = tokens;
        this.preferencias = preferencias;
        this.pedidos = new VentanaDeslizante(reloj, VENTANA);
    }

    @PostMapping("/{token}")
    public ResponseEntity<ApiResponse<MensajeDto>> darDeBaja(@PathVariable String token, HttpServletRequest request) {
        limitar(request);
        exigirForma(token);
        tokens.verificar(token).ifPresent(id -> preferencias.actualizar(id, false));
        return ResponseEntity.ok(ApiResponse.ok(new MensajeDto("Listo: no vas a recibir más avisos de baja de precio.")));
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmar(@PathVariable String token, HttpServletRequest request) {
        limitar(request);
        exigirForma(token);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", CSP)
                .body(PAGINA);
    }

    /**
     * Per source IP, before anything else: the route is public and a forged request costs a signature
     * check. A mail provider's one-click POSTs come from a few shared IPs, hence a ceiling well above a
     * person's use rather than a tight one.
     */
    private void limitar(HttpServletRequest request) {
        if (!pedidos.registrar(request.getRemoteAddr(), PEDIDOS_POR_MINUTO_Y_POR_IP)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "demasiados_pedidos", "Demasiados pedidos. Probá de nuevo en un minuto.")
                    .withHeader(HttpHeaders.RETRY_AFTER, String.valueOf(VENTANA.toSeconds()));
        }
    }

    private static void exigirForma(String token) {
        if (!TokenBajaAvisos.tieneForma(token)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "token_invalido", "El enlace de baja no es válido.");
        }
    }
}
