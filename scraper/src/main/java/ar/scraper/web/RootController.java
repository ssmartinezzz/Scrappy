package ar.scraper.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Root endpoint for the now API-only backend (decouple-services-postgres,
 * Batch 3, design D6). Replaces {@code SpaController}: the backend no longer
 * serves the React SPA (removed alongside {@code static/}) — the frontend is
 * its own independently-deployed service, so a request to {@code /} is no
 * longer a client-side route to forward to {@code index.html}. This returns
 * a small JSON status payload instead, useful as a liveness/root probe.
 */
@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<Object> root() {
        return ResponseEntity.ok(Map.of(
                "service", "fashion-scraper-api",
                "status", "ok"
        ));
    }
}
