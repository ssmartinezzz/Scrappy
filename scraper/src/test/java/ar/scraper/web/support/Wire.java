package ar.scraper.web.support;

import ar.scraper.api.ApiException;
import ar.scraper.web.api.ApiExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Reads a controller's return value the way a client sees it: serialized to JSON, then unwrapped from
 * the {@code {"data": ...}} / {@code {"error": ...}} envelope. Lets a handler-level test assert on the
 * wire shape without depending on which DTO class the handler happens to build.
 */
public final class Wire {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private Wire() {}

    /** The whole serialized body (envelope included), or a missing node when the body is null. */
    public static JsonNode body(ResponseEntity<?> resp) {
        return resp.getBody() == null ? MissingNode.getInstance() : MAPPER.valueToTree(resp.getBody());
    }

    /** The envelope's {@code data} member. */
    public static JsonNode data(ResponseEntity<?> resp) {
        return body(resp).path("data");
    }

    /** The envelope's {@code page} member. */
    public static JsonNode page(ResponseEntity<?> resp) {
        return body(resp).path("page");
    }

    /** The envelope's {@code error} member. */
    public static JsonNode error(ResponseEntity<?> resp) {
        return body(resp).path("error");
    }

    /**
     * The answer a client would get: the handler's own response, or, when it throws, the
     * response {@link ApiExceptionHandler} turns that into (status, headers and error envelope). An
     * unexpected exception becomes the generic 500.
     */
    public static ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            return new ApiExceptionHandler().apiException(e);
        } catch (RuntimeException e) {
            return new ApiExceptionHandler().inesperada(e);
        }
    }

    /** Runs the handler and returns the {@link ApiException} it throws; fails the test if it does not throw. */
    public static ApiException apiError(ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).as("handler should throw an ApiException").isInstanceOf(ApiException.class);
        return (ApiException) t;
    }

    /** Same as {@link #data(ResponseEntity)} for a bare envelope value (not wrapped in a ResponseEntity). */
    public static JsonNode data(Object envelope) {
        return envelope == null ? MissingNode.getInstance() : MAPPER.valueToTree(envelope).path("data");
    }
}
