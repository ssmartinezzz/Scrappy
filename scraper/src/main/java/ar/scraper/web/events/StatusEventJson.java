package ar.scraper.web.events;

import ar.scraper.scrape.StatusEvent;
import ar.scraper.web.dto.ScrapeDtos;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

final class StatusEventJson {

    static final String SNAPSHOT = "snapshot";
    static final String RESYNC = "resync";

    record Wire(String name, String json) {
        boolean isResync() {
            return RESYNC.equals(name);
        }
    }

    record StatusLine(String status, String mensaje) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MlLine(String kind, boolean running, String phase, int pct, String msg, String startedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DbLine(String table, String op, Long id, Long run, String site, Long job, String status) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StatusEventJson() {
    }

    static Wire of(StatusEvent event) {
        return switch (event) {
            case StatusEvent.ScrapeStatus s ->
                    new Wire("scrape.status", write(new StatusLine(s.status().name(), s.message())));
            case StatusEvent.ScrapeProgress p -> new Wire("scrape.progress", write(progress(p)));
            case StatusEvent.MlStatus m -> new Wire("ml.status", write(new MlLine(
                    m.kind().name().toLowerCase(), m.running(), m.phase(), m.pct(), m.message(),
                    m.startedAt() != null ? m.startedAt() : "")));
            case StatusEvent.DbChanged d -> new Wire("db.changed", write(new DbLine(
                    d.table(), d.op(), d.id(), d.run(), d.site(), d.job(), d.status())));
            case StatusEvent.Resync r -> new Wire(RESYNC, "{}");
        };
    }

    static Wire snapshot(Object body) {
        return new Wire(SNAPSHOT, write(body));
    }

    private static ScrapeDtos.Progreso progress(StatusEvent.ScrapeProgress p) {
        List<ScrapeDtos.SitioProgreso> sitios = p.sitios().stream()
                .map(s -> ScrapeDtos.SitioProgreso.desde(
                        s.nombre(), s.estado(), s.productos(), s.duracionMs(), s.error()))
                .toList();
        return new ScrapeDtos.Progreso(p.total(), p.completados(), p.productos(), sitios);
    }

    private static String write(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize a status event", e);
        }
    }
}
