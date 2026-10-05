package ar.scraper.security.reset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default channel. The reset link carries the single-use token, which is the whole credential,
 * so it is logged only at DEBUG — a deliberate development opt-in. At the default INFO level the log
 * says a reset was issued and to whom, but not the token, so that read access to {@code logs/} is
 * not read access to every account that requests a reset. An operator who wants the link delivered
 * out of band should select the SMTP channel.
 */
@Component
@ConditionalOnProperty(name = "password.reset.channel", havingValue = "console", matchIfMissing = true)
public class ConsoleChannel implements PasswordResetChannel {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleChannel.class);

    @Override
    public void enviar(String destino, String enlace) {
        LOG.info("""

                ╔══════════════════════════════════════════════════════════════╗
                ║  RESETEO DE CONTRASEÑA — canal `console`                     ║
                ╠══════════════════════════════════════════════════════════════╣
                ║  Para:  {}
                ║
                ║  El link (con el token de un solo uso) NO se escribe acá: es
                ║  la credencial entera. Para verlo en desarrollo, subí este
                ║  logger a DEBUG; en producción usá el canal SMTP.
                ║  Un solo uso, vence en 30 minutos.
                ╚══════════════════════════════════════════════════════════════╝
                """, destino);
        LOG.debug("[RESET] link para {}: {}", destino, enlace);
    }
}
