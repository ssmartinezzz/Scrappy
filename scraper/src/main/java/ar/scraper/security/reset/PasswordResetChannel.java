package ar.scraper.security.reset;

/**
 * How a reset link reaches the person who asked for it.
 *
 * <p>A seam with two adapters, mirroring {@code ChatProvider}/
 * {@code OpenAiCompatProvider}: {@link ConsoleChannel} writes the link to the
 * log and needs no configuration at all, {@link SmtpChannel} sends real mail and
 * is opt-in. The console adapter is the default on purpose — <b>this project
 * installs onto a laptop, and requiring a mail server to reset a password would
 * make the feature unreachable for most of the people who need it.</b></p>
 *
 * <p>Outbound only. There is no receive side anywhere in this design, which has
 * a consequence worth stating: a bounce is invisible. A wrong address produces
 * the same "check your email" as a right one, followed by permanent silence.
 * That is the price of not being an account-enumeration oracle, and the
 * operator's diagnostic is a query against {@code password_reset_token}.</p>
 */
public interface PasswordResetChannel {

    /**
     * @param destino the address, already known to belong to a resettable account
     * @param enlace  the full reset link, containing a live credential
     */
    void enviar(String destino, String enlace);
}
