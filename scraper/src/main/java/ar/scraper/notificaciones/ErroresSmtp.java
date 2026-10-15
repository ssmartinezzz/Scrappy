package ar.scraper.notificaciones;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class ErroresSmtp {

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

    /**
     * Masks every address in a provider's text, whoever it belongs to: a 4xx can quote the sender or a
     * third party. A scan outward from each '@' rather than a regex, so it costs one pass over any input.
     */
    static String sinDirecciones(String texto) {
        StringBuilder salida = new StringBuilder(texto.length());
        int copiado = 0;
        int arroba = texto.indexOf('@');
        while (arroba >= 0) {
            int inicio = inicioDeLaParteLocal(texto, arroba, copiado);
            int fin = arroba + 1;
            while (fin < texto.length() && esDeDireccion(texto.charAt(fin))) {
                fin++;
            }
            if (inicio == arroba || fin == arroba + 1) {
                arroba = texto.indexOf('@', arroba + 1);
                continue;
            }
            salida.append(texto, copiado, inicio).append(Enmascarar.direccion(texto.substring(inicio, fin)));
            copiado = fin;
            arroba = texto.indexOf('@', fin);
        }
        return salida.append(texto, copiado, texto.length()).toString();
    }

    private static int inicioDeLaParteLocal(String texto, int arroba, int piso) {
        int inicio = arroba;
        if (arroba > piso && texto.charAt(arroba - 1) == '"') {
            int apertura = arroba - 2;
            while (apertura >= piso && texto.charAt(apertura) != '"') {
                apertura--;
            }
            return apertura >= piso ? apertura : arroba;
        }
        while (inicio > piso && esDeDireccion(texto.charAt(inicio - 1))) {
            inicio--;
        }
        return inicio;
    }

    private static boolean esDeDireccion(char c) {
        return !Character.isWhitespace(c) && !Character.isISOControl(c) && "<>()[],;:\"@".indexOf(c) < 0;
    }

    private static Throwable siguiente(Throwable t) {
        return t instanceof MessagingException m && m.getNextException() != null ? m.getNextException() : t.getCause();
    }
}
