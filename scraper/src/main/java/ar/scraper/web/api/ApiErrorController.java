package ar.scraper.web.api;

import ar.scraper.api.ApiError;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The forward to {@code /error} is permitted in SecurityConfig. */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = raw instanceof Integer code && HttpStatus.resolve(code) != null
                ? HttpStatus.valueOf((Integer) raw)
                : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = status.is5xxServerError() ? "Error interno del servidor." : status.getReasonPhrase();
        // Explicit content type: a browser forward carries Accept: text/html, which would otherwise
        // be a 406.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(ApiExceptionHandler.codigoPara(status), message));
    }
}
