package ar.scraper.security.reset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default channel: writes the reset link to the log.
 *
 * <p><b>Zero configuration is the point.</b> This project installs onto a
 * laptop from a single script; demanding SMTP credentials before a password can
 * be reset would make the feature unreachable for exactly the single-user
 * installations it mostly serves. With this adapter the flow works end to end
 * out of the box: the operator reads the link out of {@code scraper.log} and
 * opens it.</p>
 *
 * <p><b>This deliberately puts a live credential in a log file.</b> That is a
 * real tradeoff, not an oversight, and it is bounded on both sides: the token is
 * single-use and expires in thirty minutes, and anybody who can read
 * {@code scraper.log} can already read {@code .env}, which holds the database
 * password and the JWT signing secret. An operator who considers the log a
 * weaker boundary than the environment file should select the SMTP channel.</p>
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
