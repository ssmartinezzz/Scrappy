package ar.scraper.notificaciones;

import java.util.List;
import java.util.Map;

/**
 * Sends a batch of mails over one connection and says what happened to each. A failure of one
 * mail never hides the others; an unreachable server fails every one of them.
 */
public interface MailPort {

    /** One entry per {@link CorreoDirigido#destino()}. */
    Map<String, Entrega> enviar(List<CorreoDirigido> correos);

    /** Opens and closes a connection to the server without sending anything. */
    default EstadoSmtp probarConexion() {
        return EstadoSmtp.noConfigurado();
    }
}
