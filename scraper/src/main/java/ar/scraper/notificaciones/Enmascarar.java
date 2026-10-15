package ar.scraper.notificaciones;

import org.apache.commons.lang3.StringUtils;

public final class Enmascarar {

    private Enmascarar() {
    }

    /** Enough to debug, not enough to identify. */
    public static String direccion(String direccion) {
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
