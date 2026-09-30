package ar.scraper.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** Thrown by handlers to answer with an {@link ApiError} and the given status. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Object details;
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }

    public Object details() { return details; }

    /** Headers to send with the error (e.g. {@code Set-Cookie}, {@code Retry-After}). */
    public HttpHeaders headers() { return headers; }

    public ApiException withHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }
}
