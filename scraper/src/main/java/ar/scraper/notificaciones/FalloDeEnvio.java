package ar.scraper.notificaciones;

/** Why one claimed row was not delivered; {@code permanente} rows are not retried. */
public record FalloDeEnvio(Reclamo reclamo, String error, boolean permanente) {

    public FalloDeEnvio(Reclamo reclamo, String error) {
        this(reclamo, error, false);
    }
}
