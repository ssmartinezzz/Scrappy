package ar.scraper.security.reset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Properties;

/**
 * Opt-in SMTP delivery, selected with {@code PASSWORD_RESET_CHANNEL=smtp}.
 *
 * <p><b>It builds its own {@link JavaMailSenderImpl} instead of using Boot's
 * mail auto-configuration, on purpose.</b> Auto-configuration reads
 * {@code spring.mail.*} and quietly defaults the host to {@code localhost}. On a
 * machine with no mail server that produces a connection refused per send —
 * inside an async task, where nobody is watching — and on a machine that happens
 * to run one, it silently relays through it. Neither is a behaviour anyone asked
 * for. Requiring the variables explicitly, and failing startup when they are
 * missing, is the same fail-fast posture the rest of this configuration has.</p>
 *
 * <p><b>The from/username mismatch is a warning, not a failure.</b> Plenty of
 * relays authenticate with an API key rather than an address, so treating a
 * mismatch as an error would reject a correct configuration. The check only
 * fires when the username looks like an email — the shape where a mismatch
 * really does usually mean a mistake — and even then it warns. This project's
 * fail-fast posture is about <i>absent</i> variables, not about second-guessing
 * present ones.</p>
 *
 * <p><b>Bounces are invisible.</b> The ERROR log below covers synchronous
 * rejection only; a message accepted by the relay and bounced later produces no
 * line anywhere, because nothing in this design receives mail.</p>
 */
@Component
@ConditionalOnProperty(name = "password.reset.channel", havingValue = "smtp")
public class SmtpChannel implements PasswordResetChannel {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpChannel.class);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;

    public SmtpChannel(@Value("${smtp.host}") String host,
                       @Value("${smtp.port}") int port,
                       @Value("${smtp.username}") String username,
                       @Value("${smtp.password}") String password,
                       @Value("${smtp.from-address}") String from) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
    }

    @PostConstruct
    void avisarSiElRemitenteNoCoincide() {
        boolean usuarioPareceEmail = username != null && username.contains("@");
        if (usuarioPareceEmail && !username.equalsIgnoreCase(from)) {
            LOG.warn("[RESET] SMTP_USERNAME ({}) y SMTP_FROM_ADDRESS ({}) no coinciden. "
                            + "Muchos relays rechazan un remitente que no es la cuenta autenticada. "
                            + "Si es a propósito, ignorá este aviso.",
                    enmascarar(username), enmascarar(from));
        }
    }

    @Override
    public void enviar(String destino, String enlace) {
        try {
            SimpleMailMessage mensaje = new SimpleMailMessage();
            mensaje.setFrom(from);
            mensaje.setTo(destino);
            mensaje.setSubject("Restablecer tu contraseña");
            mensaje.setText("Para elegir una contraseña nueva, entrá acá:\n\n" + enlace
                    + "\n\nEl link se usa una sola vez y vence en 30 minutos.\n"
                    + "Si no pediste esto, ignorá el mensaje: tu contraseña no cambió.\n");
            sender().send(mensaje);
        } catch (Exception e) {
            // Masked: an error log naming who asked for a reset is the same
            // disclosure the uniform response exists to prevent, moved to a file.
            LOG.error("[RESET] no se pudo enviar el mail a {}: {}", enmascarar(destino), e.getMessage());
        }
    }

    private JavaMailSender sender() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        impl.setUsername(username);
        impl.setPassword(password);
        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        props.put("mail.smtp.starttls.enable", "true");
        return impl;
    }

    /** {@code ana@example.com} → {@code a**@example.com}. Enough to debug, not enough to identify. */
    static String enmascarar(String direccion) {
        if (direccion == null || direccion.isBlank()) {
            return "(vacío)";
        }
        int arroba = direccion.indexOf('@');
        if (arroba <= 0) {
            return direccion.charAt(0) + "**";
        }
        return direccion.charAt(0) + "**" + direccion.substring(arroba);
    }
}
