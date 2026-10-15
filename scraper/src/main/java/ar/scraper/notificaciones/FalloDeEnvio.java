package ar.scraper.notificaciones;

/** Why one claimed row was not delivered; {@code permanente} rows are not retried. */
public record FalloDeEnvio(long id, String error, boolean permanente) {

    public FalloDeEnvio(long id, String error) {
        this(id, error, false);
    }
}
