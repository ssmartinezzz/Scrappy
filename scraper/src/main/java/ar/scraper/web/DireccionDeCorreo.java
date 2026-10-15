package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.db.UsuarioRepository.ResultadoEmail;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DireccionDeCorreo {

    static final int MAX = 254;

    private static final Pattern FORMA = Pattern.compile("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+");

    private DireccionDeCorreo() {
    }

    /** Null clears the address; anything else is trimmed, lowercased and shape-checked. */
    public static String normalizar(Object crudo) {
        if (crudo == null) {
            return null;
        }
        if (!(crudo instanceof String texto)) {
            throw invalida();
        }
        String limpia = texto.trim().toLowerCase(Locale.ROOT);
        if (limpia.isEmpty()) {
            return null;
        }
        if (limpia.length() > MAX || !FORMA.matcher(limpia).matches()) {
            throw invalida();
        }
        return limpia;
    }

    public static void exigirCambiado(ResultadoEmail resultado) {
        switch (resultado) {
            case CAMBIADO, SIN_CAMBIO -> { }
            case TOMADO -> throw new ApiException(HttpStatus.CONFLICT, "email_no_disponible",
                    "Esa dirección no está disponible.");
            case ES_SERVICIO -> throw new ApiException(HttpStatus.CONFLICT, "cuenta_de_servicio",
                    "Las cuentas de servicio no pueden tener email.");
            case NO_EXISTE -> throw new ApiException(HttpStatus.NOT_FOUND, "no_existe", "No existe esa cuenta.");
        }
    }

    private static ApiException invalida() {
        return new ApiException(HttpStatus.BAD_REQUEST, "email_invalido", "La dirección de email no es válida.");
    }
}
