package ar.scraper.indices;

/** A {@link FuenteIndicePort} could not produce a usable series for one refresh attempt. */
public class FuenteIndiceException extends Exception {

    public FuenteIndiceException(String message) {
        super(message);
    }

    public FuenteIndiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
