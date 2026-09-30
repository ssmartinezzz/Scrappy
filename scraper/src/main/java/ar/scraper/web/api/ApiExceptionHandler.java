package ar.scraper.web.api;

import ar.scraper.api.ApiError;
import ar.scraper.api.ApiException;
import ar.scraper.identity.Sujeto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * Every error leaves the API as {@link ApiError}. Extending {@link ResponseEntityExceptionHandler}
 * covers the framework's own 4xx (missing parameter, type mismatch, unreadable body, 404/405/415...)
 * with their status and headers (e.g. {@code Allow}); {@link #handleExceptionInternal} swaps its
 * ProblemDetail body for the envelope.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiException(ApiException e) {
        return ResponseEntity.status(e.status()).headers(e.headers())
                .body(ApiError.of(e.code(), e.getMessage(), e.details()));
    }

    /**
     * An owner-scoped route reached with no authenticated subject. Answered 401 rather than an empty
     * list (looks like data loss) or everybody's rows (the leak). The filter chain already guarantees a
     * subject on these routes; this catches a future route added to the wrong band.
     */
    @ExceptionHandler(Sujeto.SinSujeto.class)
    public ResponseEntity<ApiError> sinSujeto(Sujeto.SinSujeto e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("no_autenticado", "Esta operación es personal y necesita una sesión."));
    }

    /** Security exceptions must reach the filter chain (401/403 handlers), not be swallowed as a 500. */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void seguridad(RuntimeException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> inesperada(Exception e) {
        LOG.error("[API] Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("error_interno", "Error interno del servidor."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (statusCode.is5xxServerError()) {
            LOG.error("[API] Framework error {}", statusCode.value(), ex);
        }
        return ResponseEntity.status(statusCode).headers(headers)
                .body(ApiError.of(codigoPara(statusCode), mensajePara(ex, statusCode, status)));
    }

    static String codigoPara(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> "no_autenticado";
            case 403 -> "sin_permiso";
            case 404 -> "no_encontrado";
            case 405 -> "metodo_no_permitido";
            case 406 -> "no_aceptable";
            case 413 -> "payload_demasiado_grande";
            case 415 -> "media_no_soportada";
            case 503 -> "servicio_no_disponible";
            default -> status.is5xxServerError() ? "error_interno" : "solicitud_invalida";
        };
    }

    /** Never leaks parser internals or stack traces: only the framework's own short reason, or a fixed text. */
    private static String mensajePara(Exception ex, HttpStatusCode code, HttpStatus status) {
        if (code.is5xxServerError()) {
            return "Error interno del servidor.";
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return "El archivo supera el tamaño máximo permitido.";
        }
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return "El cuerpo de la solicitud falta o no es un JSON válido.";
        }
        if (ex instanceof MethodArgumentNotValidException m) {
            return m.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        if (ex instanceof org.springframework.web.bind.MissingServletRequestParameterException m) {
            return "Falta el parámetro requerido '" + m.getParameterName() + "'.";
        }
        if (ex instanceof org.springframework.beans.TypeMismatchException m) {
            return "El parámetro '" + m.getPropertyName() + "' tiene un valor inválido.";
        }
        return status != null ? status.getReasonPhrase() : "Solicitud inválida.";
    }
}
