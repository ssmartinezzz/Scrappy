package ar.scraper.security.reset;

/**
 * The console adapter is the default on purpose — this project installs onto a laptop, and
 * requiring a mail server to reset a password would make the feature unreachable for most of the
 * people who need it. Outbound only.
 */
public interface PasswordResetChannel {

    void enviar(String destino, String enlace);
}
