package ar.scraper.web;

import ar.scraper.web.events.StatusStreams;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-sent stream of status changes; replaces the UI's status polling. See docs/API_REFERENCE.md. */
@RestController
@RequestMapping("/api")
public class EventsController {

    private final StatusStreams streams;
    private final ApiController api;

    public EventsController(StatusStreams streams, ApiController api) {
        this.streams = streams;
        this.api = api;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(Authentication auth) {
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        SseEmitter emitter = streams.open(admin, () -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("status", api.statusSnapshot());
            snapshot.put("ml", api.mlEstadoSnapshot());
            return snapshot;
        });
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }
}
