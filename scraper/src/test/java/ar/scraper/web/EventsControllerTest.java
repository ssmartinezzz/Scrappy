package ar.scraper.web;

import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.web.dto.MlDtos;
import ar.scraper.web.dto.ScrapeDtos;
import ar.scraper.web.events.InProcessStatusEvents;
import ar.scraper.web.events.StatusStreams;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Epic("Status push")
@Feature("SSE endpoint")
@Story("GET /api/events: snapshot first, then live events")
@DisplayName("EventsController")
class EventsControllerTest {

    private InProcessStatusEvents bus;
    private StatusStreams streams;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bus = new InProcessStatusEvents();
        streams = new StatusStreams(bus);
        ScrapeStatusView scrapeStatus = Mockito.mock(ScrapeStatusView.class);
        Mockito.when(scrapeStatus.snapshot()).thenReturn(ScrapeDtos.Status.builder()
                .status("IDLE").mensaje("Listo").tieneData(false).build());
        MlDtos.Estado ml = new MlDtos.Estado();
        ml.setTraining(new MlDtos.Training(false, "idle", 0, "", ""));
        MlEstadoView mlEstado = Mockito.mock(MlEstadoView.class);
        Mockito.when(mlEstado.snapshot()).thenReturn(ml);
        mvc = MockMvcBuilders.standaloneSetup(new EventsController(streams, scrapeStatus, mlEstado)).build();
    }

    private static Principal user(String role) {
        return new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static void await(String what, BooleanSupplier c) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private MvcResult open(String role) throws Exception {
        return mvc.perform(get("/api/events").principal(user(role)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    @DisplayName("answers as an event stream with the no-buffering headers, and starts with a snapshot")
    void startsWithASnapshot() throws Exception {
        MvcResult r = open("VIEWER");

        await("snapshot", () -> contentOf(r).contains("event:snapshot"));
        assertThat(contentOf(r)).startsWith("event:snapshot");
        assertThat(contentOf(r)).contains("\"status\":{\"status\":\"IDLE\",\"mensaje\":\"Listo\"")
                .contains("\"ml\":{");
        mvc.perform(get("/api/events").principal(user("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    @DisplayName("a published event reaches the open stream after the snapshot")
    void deliversALiveEvent() throws Exception {
        MvcResult r = open("VIEWER");
        await("snapshot", () -> contentOf(r).contains("event:snapshot"));

        bus.publish(new StatusEvent.ScrapeStatus(ScraperStatus.RUNNING, "2/5 sitios"));

        await("the live event", () -> contentOf(r).contains("2/5 sitios"));
        String content = contentOf(r);
        assertThat(content.indexOf("event:snapshot")).isLessThan(content.indexOf("event:scrape.status"));
        assertThat(content).contains("data:{\"status\":\"RUNNING\",\"mensaje\":\"2/5 sitios\"}");
    }

    @Test
    @DisplayName("cron_execution changes go to an ADMIN only; scrape changes go to everyone")
    void cronChangesAreAdminOnly() throws Exception {
        MvcResult admin = open("ADMIN");
        MvcResult viewer = open("VIEWER");
        await("both snapshots", () -> contentOf(admin).contains("event:snapshot")
                && contentOf(viewer).contains("event:snapshot"));

        bus.publish(new StatusEvent.DbChanged("cron_execution", "INSERT", 1L, null, null, 9L, "running"));
        bus.publish(new StatusEvent.DbChanged("scrape_run", "UPDATE", 3L, null, null, null, "COMPLETED"));

        await("the admin sees both", () -> contentOf(admin).contains("cron_execution")
                && contentOf(admin).contains("COMPLETED"));
        await("the viewer sees the run", () -> contentOf(viewer).contains("COMPLETED"));
        assertThat(contentOf(viewer)).doesNotContain("cron_execution");
    }

    @Test
    @DisplayName("finishing the request releases the bus subscription")
    void releasesTheSubscription() throws Exception {
        MvcResult r = open("VIEWER");
        await("snapshot", () -> contentOf(r).contains("event:snapshot"));
        assertThat(streams.openStreams()).isEqualTo(1);

        r.getRequest().getAsyncContext().complete();
        for (var l : r.getRequest().getAsyncContext() instanceof org.springframework.mock.web.MockAsyncContext m
                ? m.getListeners() : List.<jakarta.servlet.AsyncListener>of()) {
            l.onComplete(new jakarta.servlet.AsyncEvent(r.getRequest().getAsyncContext()));
        }

        await("the stream to close", () -> streams.openStreams() == 0);
    }

    private static String contentOf(MvcResult r) {
        try {
            return r.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
