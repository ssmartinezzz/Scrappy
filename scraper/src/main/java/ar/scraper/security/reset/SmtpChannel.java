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
import org.apache.commons.lang3.StringUtils;

/** Opt-in SMTP delivery, selected with {@code PASSWORD_RESET_CHANNEL=smtp}. */
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
        props.put("mail.smtp.auth", String.valueOf(StringUtils.isNotBlank(username)));
        props.put("mail.smtp.starttls.enable", "true");
        return impl;
    }

    /** Enough to debug, not enough to identify. */
    static String enmascarar(String direccion) {
        if (StringUtils.isBlank(direccion)) {
            return "(vacío)";
        }
        int arroba = direccion.indexOf('@');
        if (arroba <= 0) {
            return direccion.charAt(0) + "**";
        }
        return direccion.charAt(0) + "**" + direccion.substring(arroba);
    }
}
