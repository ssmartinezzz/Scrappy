package ar.scraper.promos;

/** A page could not be read as a real storefront page: fetch error, block page or empty body. */
public class LecturaFallidaException extends RuntimeException {
    public LecturaFallidaException(String message) {
        super(message);
    }

    public LecturaFallidaException(String message, Throwable cause) {
        super(message, cause);
    }
}
