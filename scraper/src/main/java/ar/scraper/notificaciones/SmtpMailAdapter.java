package ar.scraper.notificaciones;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Active whenever {@code SMTP_HOST} is set; no further flag. Port 465 is implicit TLS (SMTPS);
 * any other port speaks plain SMTP and upgrades with STARTTLS when the server offers it.
 */
public class SmtpMailAdapter implements MailPort {

    private static final String TIMEOUT_MS = "15000";
    private static final int PUERTO_SMTPS = 465;

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;

    public SmtpMailAdapter(String host,
                           int port,
                           String username,
                           String password,
                           String from) {
        this(host, port, username, password, from, port, null);
    }

    SmtpMailAdapter(String host,
                    int port,
                    String username,
                    String password,
                    String from,
                    int puertoDeProtocolo,
                    SSLSocketFactory confianza) {
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean smtps = puertoDeProtocolo == PUERTO_SMTPS;
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(StringUtils.isNotBlank(username)));
        props.put("mail.smtp.ssl.enable", String.valueOf(smtps));
        props.put("mail.smtp.starttls.enable", String.valueOf(!smtps));
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.socketFactory", SocketsTcpNoDelay.plano());
        props.put("mail.smtp.ssl.socketFactory",
                SocketsTcpNoDelay.tls(ObjectUtils.getIfNull(confianza, () -> (SSLSocketFactory) SSLSocketFactory.getDefault())));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        this.from = StringUtils.defaultIfBlank(from, username);
    }

    Properties propiedades() {
        return sender.getJavaMailProperties();
    }

    @Override
    public EstadoSmtp probarConexion() {
        try {
            sender.testConnection();
            return EstadoSmtp.ok("El servidor " + sender.getHost() + ":" + sender.getPort() + " aceptó la conexión.");
        } catch (MessagingException | RuntimeException e) {
            return ErroresSmtp.clasificar(e, sender.getHost(), sender.getPort());
        }
    }

    /** One connection for the whole batch: {@code send(MimeMessage...)} opens the transport once. */
    @Override
    public Map<String, Entrega> enviar(List<CorreoDirigido> correos) {
        Map<String, Entrega> resultado = new LinkedHashMap<>();
        Map<MimeMessage, String> destinoDe = new IdentityHashMap<>();
        List<MimeMessage> mensajes = new ArrayList<>();
        for (CorreoDirigido c : correos) {
            try {
                MimeMessage mensaje = armar(c);
                mensajes.add(mensaje);
                destinoDe.put(mensaje, c.destino());
            } catch (MessagingException e) {
                resultado.put(c.destino(), Entrega.fallo("no se pudo armar el mail: " + e.getMessage()));
            }
        }
        if (mensajes.isEmpty()) {
            return resultado;
        }
        try {
            sender.send(mensajes.toArray(MimeMessage[]::new));
            mensajes.forEach(m -> resultado.put(destinoDe.get(m), Entrega.ok()));
        } catch (MailSendException e) {
            for (MimeMessage m : mensajes) {
                Exception causa = e.getFailedMessages().get(m);
                resultado.put(destinoDe.get(m), causa == null ? Entrega.ok() : clasificar(causa));
            }
        } catch (MailException e) {
            mensajes.forEach(m -> resultado.put(destinoDe.get(m), Entrega.fallo(descripcion(e))));
        }
        return resultado;
    }

    private MimeMessage armar(CorreoDirigido c) throws MessagingException {
        MimeMessage mensaje = c.claveMensaje() == null
                ? sender.createMimeMessage()
                : conIdentidad(idDeMensaje(c.claveMensaje()));
        MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(c.destino());
        helper.setSubject(c.correo().asunto());
        if (c.enlaceBaja() != null) {
            mensaje.setHeader("List-Unsubscribe", "<" + c.enlaceBaja() + ">");
            mensaje.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        helper.setText(c.correo().texto(), c.correo().html());
        return mensaje;
    }

    /** {@code saveChanges} regenerates the Message-ID unless the message overrides how it is made. */
    private MimeMessage conIdentidad(String id) {
        return new MimeMessage(sender.getSession()) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", id);
            }
        };
    }

    private String idDeMensaje(String clave) {
        String dominio = StringUtils.substringAfterLast(from, "@");
        return "<notif-" + clave + "@" + StringUtils.defaultIfBlank(dominio, "localhost") + ">";
    }

    /**
     * Only a 5xx about a recipient or the message is permanent. A 5xx about the sender or the
     * credentials (530, 534, 535) is the operator's to fix and the rows must survive until then.
     */
    private static Entrega clasificar(Exception causa) {
        String descripcion = descripcion(causa);
        for (Throwable t = causa; t != null; t = siguiente(t)) {
            if (t instanceof SMTPAddressFailedException a && esPermanente(a.getReturnCode(), a.getCommand())) {
                return Entrega.rechazo(descripcion);
            }
            if (t instanceof SMTPSendFailedException e && esPermanente(e.getReturnCode(), e.getCommand())) {
                return Entrega.rechazo(descripcion);
            }
        }
        return Entrega.fallo(descripcion);
    }

    private static boolean esPermanente(int codigo, String comando) {
        return codigo >= 500 && codigo < 600
                && codigo != 530 && codigo != 534 && codigo != 535
                && !StringUtils.startsWithIgnoreCase(comando, "MAIL FROM");
    }

    private static Throwable siguiente(Throwable t) {
        return t instanceof MessagingException m && m.getNextException() != null ? m.getNextException() : t.getCause();
    }

    private static String descripcion(Exception e) {
        return e.getClass().getSimpleName() + ": " + StringUtils.defaultString(e.getMessage());
    }
}
