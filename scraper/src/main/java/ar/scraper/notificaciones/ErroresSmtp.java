package ar.scraper.notificaciones;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ErroresSmtp {

    private static final Pattern DIRECCION = Pattern.compile("[\\w.+%-]+@[\\w.-]+");
    private static final String PUERTOS = "587 = STARTTLS, 465 = SSL/TLS";

    private ErroresSmtp() {
    }

    static EstadoSmtp clasificar(Throwable error, String host, int puerto) {
        String destino = host + ":" + puerto;
        for (Throwable t = error; t != null; t = siguiente(t)) {
            if (t instanceof AuthenticationFailedException) {
                return EstadoSmtp.fallo("El servidor rechazó el usuario o la contraseña.",
                        "Con Gmail, Outlook o Yahoo hace falta una contraseña de aplicación, no la de la cuenta.");
            }
            if (t instanceof UnknownHostException) {
                return EstadoSmtp.fallo("No se encontró el host " + host + ".", "Revisá SMTP_HOST.");
            }
            if (t instanceof SocketTimeoutException) {
                return EstadoSmtp.fallo("Se agotó el tiempo esperando a " + destino + ".",
                        "Revisá que el puerto sea el correcto y que ningún firewall lo filtre.");
            }
            if (t instanceof SSLException) {
                return EstadoSmtp.fallo("Falló el handshake TLS con " + destino + ".",
                        "Probá otro puerto según lo que pida el proveedor (" + PUERTOS + ").");
            }
            if (t instanceof ConnectException) {
                return EstadoSmtp.fallo("No se pudo conectar a " + destino + ".",
                        "Revisá SMTP_HOST y SMTP_PORT (" + PUERTOS + ") y que el firewall deje salir esa conexión.");
            }
        }
        if (StringUtils.containsIgnoreCase(error.getMessage(), "STARTTLS is required")) {
            return EstadoSmtp.fallo("El servidor " + destino + " no ofrece STARTTLS: no se envían las credenciales sin cifrar.",
                    "Usá el puerto 465 (SSL/TLS) o el de STARTTLS del proveedor. SMTP_STARTTLS_REQUIRED=false sólo para un relay local de confianza.");
        }
        return EstadoSmtp.fallo(error.getClass().getSimpleName() + ": " + sinDirecciones(String.valueOf(error.getMessage())),
                "Revisá el log del backend.");
    }

    static String sinDirecciones(String texto) {
        Matcher m = DIRECCION.matcher(texto);
        return m.replaceAll(r -> Matcher.quoteReplacement(Enmascarar.direccion(r.group())));
    }

    private static Throwable siguiente(Throwable t) {
        return t instanceof MessagingException m && m.getNextException() != null ? m.getNextException() : t.getCause();
    }
}
