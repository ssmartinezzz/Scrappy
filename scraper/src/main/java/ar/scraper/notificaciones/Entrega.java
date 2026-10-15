package ar.scraper.notificaciones;

/**
 * What happened to one recipient's mail: delivered, or the reason it was not. A failure is
 * {@code permanente} when retrying cannot help (the mailbox does not exist); otherwise it is
 * transient and the outbox backs off.
 */
public record Entrega(String error, boolean permanente) {

    private static final Entrega OK = new Entrega(null, false);

    public static Entrega ok() {
        return OK;
    }

    public static Entrega fallo(String error) {
        return new Entrega(error, false);
    }

    public static Entrega rechazo(String error) {
        return new Entrega(error, true);
    }

    public boolean enviada() {
        return error == null;
    }
}
