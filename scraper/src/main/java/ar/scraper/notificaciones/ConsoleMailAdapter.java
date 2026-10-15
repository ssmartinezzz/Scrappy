package ar.scraper.notificaciones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Used while {@code SMTP_HOST} is blank: the outbox still drains, the mail is only announced. */
public class ConsoleMailAdapter implements MailPort {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleMailAdapter.class);

    @Override
    public Map<String, Entrega> enviar(List<CorreoDirigido> correos) {
        Map<String, Entrega> resultado = new LinkedHashMap<>();
        for (CorreoDirigido c : correos) {
            anunciar(c.destino(), c.correo());
            resultado.put(c.destino(), Entrega.ok());
        }
        return resultado;
    }

    private void anunciar(String destino, CorreoRenderizado correo) {
        LOG.info("[NOTIF] SMTP_HOST sin configurar, no se envía el mail \"{}\" a {}",
                correo.asunto(), Enmascarar.direccion(destino));
    }
}
