package ar.scraper.security.reset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * An operator who considers the log a weaker boundary than the environment file should select the
 * SMTP channel.
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
                ║  Link:  {}
                ║
                ║  Un solo uso, vence en 30 minutos.
                ╚══════════════════════════════════════════════════════════════╝
                """, destino, enlace);
    }
}
