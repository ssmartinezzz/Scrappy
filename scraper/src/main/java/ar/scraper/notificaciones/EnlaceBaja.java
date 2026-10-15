package ar.scraper.notificaciones;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Builds the public one-click unsubscribe URL; with no usable public origin there is no link, not a broken one. */
@Component
public class EnlaceBaja {

    private static final String RUTA = "/api/notificaciones/baja/";

    private final TokenBajaAvisos tokens;
    private final String origen;

    public EnlaceBaja(TokenBajaAvisos tokens, @Value("${notificaciones.api-base-url:}") String origenPublico) {
        this.tokens = tokens;
        String limpio = StringUtils.removeEnd(StringUtils.strip(origenPublico), "/");
        this.origen = StringUtils.startsWithAny(StringUtils.lowerCase(limpio, Locale.ROOT), "http://", "https://")
                ? limpio : "";
    }

    public Optional<String> para(UUID usuarioId) {
        return origen.isEmpty() ? Optional.empty() : Optional.of(origen + RUTA + tokens.emitir(usuarioId));
    }
}
