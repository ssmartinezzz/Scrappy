package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.ServiceStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness/root probe: the backend is API-only, so {@code /} answers a small status payload. */
@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<ApiResponse<ServiceStatus>> root() {
        return ResponseEntity.ok(ApiResponse.ok(new ServiceStatus("fashion-scraper-api", "ok")));
    }
}
