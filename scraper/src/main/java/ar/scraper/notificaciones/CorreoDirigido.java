package ar.scraper.notificaciones;

/**
 * @param claveMensaje identifies this exact claim (recipient plus rows) so a resend after a crash
 *                     is the same message; null lets the transport pick its own identity
 * @param enlaceBaja   one-click unsubscribe URL (RFC 8058); null sends the mail without the headers
 */
public record CorreoDirigido(String destino, CorreoRenderizado correo, String claveMensaje, String enlaceBaja) {

    public CorreoDirigido(String destino, CorreoRenderizado correo, String claveMensaje) {
        this(destino, correo, claveMensaje, null);
    }

    public CorreoDirigido(String destino, CorreoRenderizado correo) {
        this(destino, correo, null, null);
    }
}
