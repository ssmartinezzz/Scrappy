package ar.scraper.agent;

/**
 * Thrown when the chat provider cannot produce a usable response — an HTTP
 * status &gt;= 400, a connect/timeout exception, or an unparseable/empty
 * response body (agent-chat-response-quality, design Part 1). The type
 * system, not caller discipline, enforces that a provider failure can never
 * be delivered as a real answer: {@link OpenAiCompatProvider#next} throws
 * this instead of manufacturing a {@link ChatResponse} carrying error prose.
 *
 * <p>{@link #reason()} is logged only — it never crosses the wire.
 * {@code ApiController} maps every reason to the same stable
 * {@code codigo: "proveedor_no_disponible"} discriminator via a local
 * {@code try/catch}, not a global {@code @ExceptionHandler}.</p>
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
