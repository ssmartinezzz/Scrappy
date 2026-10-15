package ar.scraper.notificaciones;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

public record ConfiguracionSmtp(String host, int port, String username, String password, String from) {

    private static final int PUERTO_SMTPS = 465;

    /** The first thing wrong with the settings, or empty when they look usable. */
    public Optional<String> problema() {
        if (StringUtils.containsAny(host, ':', '/') || StringUtils.containsWhitespace(host)) {
            return Optional.of("SMTP_HOST debe ser sólo el nombre del servidor, sin esquema, puerto ni ruta.");
        }
        if (port < 1 || port > 65535) {
            return Optional.of("SMTP_PORT=" + port + " está fuera de rango (1..65535).");
        }
        if (StringUtils.isNotBlank(username) && StringUtils.isBlank(password)) {
            return Optional.of("SMTP_USERNAME está cargado pero SMTP_PASSWORD está vacío.");
        }
        if (StringUtils.isBlank(from) && !StringUtils.contains(username, '@')) {
            return Optional.of("SMTP_FROM_ADDRESS está vacío y SMTP_USERNAME no es una dirección: "
                    + "los proveedores rechazan un remitente que no es un mail.");
        }
        return Optional.empty();
    }

    /** {@code host:port (modo)} for the log line; no credentials. */
    public String destino() {
        return host + ":" + port + (port == PUERTO_SMTPS ? " (SSL/TLS)" : " (STARTTLS)");
    }
}
