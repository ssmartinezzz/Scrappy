package ar.scraper.notificaciones;

/** What a connection or test-mail attempt found, in words an operator can act on. Never an address or a secret. */
public record EstadoSmtp(Nivel nivel, String mensaje, String pista) {

    public enum Nivel { OK, NO_CONFIGURADO, FALLO }

    public static EstadoSmtp ok(String mensaje) {
        return new EstadoSmtp(Nivel.OK, mensaje, null);
    }

    public static EstadoSmtp noConfigurado() {
        return new EstadoSmtp(Nivel.NO_CONFIGURADO,
                "SMTP_HOST no está configurado: los avisos se anuncian en el log y no se envían.",
                "Configurá SMTP_HOST, SMTP_USERNAME y SMTP_PASSWORD (CLI: Configurar mail).");
    }

    public static EstadoSmtp fallo(String mensaje, String pista) {
        return new EstadoSmtp(Nivel.FALLO, mensaje, pista);
    }

    public boolean ok() {
        return nivel == Nivel.OK;
    }
}
