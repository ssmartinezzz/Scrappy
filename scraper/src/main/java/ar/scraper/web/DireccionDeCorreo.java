package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.db.UsuarioRepository.ResultadoEmail;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Locale;

public final class DireccionDeCorreo {

    static final int MAX = 254;

    private static final int MAX_LOCAL = 64;
    private static final int MAX_ETIQUETA = 63;
    private static final String CARACTERES_LOCALES = "!#$%&'*+/=?^_`{|}~.-";

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
        if (!esDireccionSimple(limpia)) {
            throw invalida();
        }
        return limpia;
    }

    /**
     * One plain ASCII address: no display form, quotes, comments, lists or control characters, so the
     * value that is stored is exactly the one mail is delivered to. A hand-written scan, not a regex,
     * so hostile input cannot cost more than its length.
     */
    private static boolean esDireccionSimple(String direccion) {
        int arroba = direccion.indexOf('@');
        if (direccion.length() > MAX || arroba < 1 || arroba != direccion.lastIndexOf('@')) {
            return false;
        }
        String local = direccion.substring(0, arroba);
        if (local.length() > MAX_LOCAL || local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || !local.chars().allMatch(c -> esAlfanumerico(c) || CARACTERES_LOCALES.indexOf(c) >= 0)) {
            return false;
        }
        String[] etiquetas = StringUtils.splitPreserveAllTokens(direccion.substring(arroba + 1), '.');
        return etiquetas.length > 1 && Arrays.stream(etiquetas).allMatch(DireccionDeCorreo::esEtiqueta);
    }

    private static boolean esEtiqueta(String etiqueta) {
        return etiqueta.length() >= 1 && etiqueta.length() <= MAX_ETIQUETA
                && !etiqueta.startsWith("-") && !etiqueta.endsWith("-")
                && etiqueta.chars().allMatch(c -> esAlfanumerico(c) || c == '-');
    }

    private static boolean esAlfanumerico(int c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
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
