package ar.scraper.model;

/**
 * A persistence failure, translated by the adapter so the domain never sees {@code SQLException}.
 */
public class PersistenciaException extends RuntimeException {

    public PersistenciaException(String mensaje) {
        super(mensaje);
    }

    public PersistenciaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public PersistenciaException(Throwable causa) {
        super(causa.getMessage(), causa);
    }
}
