package ar.scraper.scrapers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin client for a FlareSolverr instance. Never throws: any failure becomes
 * {@link Optional#empty()} so the caller reports an explicit Cloudflare block instead of a
 * silent empty run.
 */
public final class FlareSolverrClient {

    private static final Logger log = LoggerFactory.getLogger(FlareSolverrClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public record CfCookie(String name, String value) { }

    public record Ticket(List<CfCookie> cookies, String userAgent) {
        public Optional<String> clearance(String nombre) {
            return cookies.stream()
                    .filter(c -> c.name().equals(nombre))
                    .map(CfCookie::value)
                    .findFirst();
        }
    }

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public FlareSolverrClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), new ObjectMapper());
    }

    FlareSolverrClient(String baseUrl, HttpClient http, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = http;
        this.json = json;
    }

    public Optional<Ticket> resolver(String url, int maxTimeoutMs) {
        try {
            String body = """
                    {"cmd":"request.get","url":"%s","maxTimeout":%d}
                    """.formatted(url, maxTimeoutMs);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1"))
                    .timeout(Duration.ofMillis(Math.max(maxTimeoutMs + 15_000, 30_000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FlareSolverr HTTP {} contra {}", resp.statusCode(), baseUrl);
                return Optional.empty();
            }
            return parse(resp.body());
        } catch (Exception e) {
            log.warn("FlareSolverr no disponible ({}): {}", baseUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Ticket> parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (!"ok".equals(root.path("status").asText())) {
                log.warn("FlareSolverr rechazó el challenge: {}", root.path("message").asText("sin mensaje"));
                return Optional.empty();
            }
            JsonNode solution = root.path("solution");
            if (solution.path("status").asInt() != 200) {
                log.warn("FlareSolverr resolvió con status {} (esperado 200)", solution.path("status").asInt());
                return Optional.empty();
            }
            List<CfCookie> cookies = new ArrayList<>();
            for (JsonNode c : solution.path("cookies")) {
                String name = c.path("name").asText();
                // Solo las anti-bot: las de analytics/sesión contaminarían el contexto.
                if (name.startsWith("cf_") || name.startsWith("__cf_")) {
                    cookies.add(new CfCookie(name, c.path("value").asText()));
                }
            }
            String userAgent = solution.path("userAgent").asText("");
            if (cookies.stream().noneMatch(c -> c.name().equals("cf_clearance"))) {
                log.warn("FlareSolverr respondió sin cf_clearance; no hay ticket que reutilizar");
                return Optional.empty();
            }
            return Optional.of(new Ticket(cookies, userAgent));
        } catch (Exception e) {
            log.warn("FlareSolverr devolvió un payload inesperado: {}", e.getMessage());
            return Optional.empty();
        }
    }
}