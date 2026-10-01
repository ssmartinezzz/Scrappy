package ar.scraper.agent;

/**
 * Thrown when the chat provider cannot produce a usable response — an HTTP status &gt;= 400, a
 * connect/timeout exception, or an unparseable/empty response body.
 */
public class ProviderUnavailableException extends RuntimeException {

    public enum Reason {
        HTTP_ERROR,
        UNREACHABLE,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public ProviderUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ProviderUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
