package ar.scraper.web.events;

import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatusEventJson — event names and bodies the client depends on")
class StatusEventJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode body(StatusEventJson.Wire w) throws Exception {
        return JSON.readTree(w.json());
    }

    @Test
    void scrapeStatusUsesTheStatusEndpointFieldNames() throws Exception {
        var w = StatusEventJson.of(new StatusEvent.ScrapeStatus(ScraperStatus.RUNNING, "1/3 sitios"));

        assertThat(w.name()).isEqualTo("scrape.status");
        assertThat(body(w).get("status").asText()).isEqualTo("RUNNING");
        assertThat(body(w).get("mensaje").asText()).isEqualTo("1/3 sitios");
    }

    @Test
    void scrapeProgressMatchesTheProgresoShapeOfApiStatus() throws Exception {
        var long80 = "x".repeat(100);
        var w = StatusEventJson.of(new StatusEvent.ScrapeProgress(2, 1, 40, List.of(
                new StatusEvent.SiteProgress("Freres", "DONE", 40, null, 1200),
                new StatusEvent.SiteProgress("VCP", "ERROR", 0, long80, 50))));

        assertThat(w.name()).isEqualTo("scrape.progress");
        JsonNode b = body(w);
        assertThat(b.get("total").asInt()).isEqualTo(2);
        assertThat(b.get("completados").asInt()).isEqualTo(1);
        assertThat(b.get("productos").asInt()).isEqualTo(40);
        JsonNode first = b.get("sitios").get(0);
        assertThat(first.get("nombre").asText()).isEqualTo("Freres");
        assertThat(first.get("estado").asText()).isEqualTo("done");
        assertThat(first.get("count").asInt()).isEqualTo(40);
        assertThat(first.get("durMs").asLong()).isEqualTo(1200);
        assertThat(first.has("error")).isFalse();
        assertThat(b.get("sitios").get(1).get("error").asText()).hasSize(63).endsWith("...");
    }

    @Test
    void mlStatusCarriesItsKindAndNeverANullStart() throws Exception {
        var w = StatusEventJson.of(new StatusEvent.MlStatus(
                StatusEvent.MlStatus.Kind.TRAINING, true, "starting", 0, "", null));

        assertThat(w.name()).isEqualTo("ml.status");
        assertThat(body(w).get("kind").asText()).isEqualTo("training");
        assertThat(body(w).get("running").asBoolean()).isTrue();
        assertThat(body(w).get("startedAt").asText()).isEmpty();
    }

    @Test
    void dbChangedOmitsTheFieldsThatDoNotApply() throws Exception {
        var w = StatusEventJson.of(new StatusEvent.DbChanged("scrape_run_site", "UPDATE", null, 7L, "freres", null, "DONE"));

        assertThat(w.name()).isEqualTo("db.changed");
        assertThat(body(w).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("table", "op", "run", "site", "status");
    }

    @Test
    void resyncIsAnEmptyObject() {
        var w = StatusEventJson.of(new StatusEvent.Resync());

        assertThat(w.name()).isEqualTo("resync");
        assertThat(w.json()).isEqualTo("{}");
        assertThat(w.isResync()).isTrue();
    }
}
