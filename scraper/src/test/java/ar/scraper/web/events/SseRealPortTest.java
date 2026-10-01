package ar.scraper.web.events;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
import ar.scraper.web.ApiController;
import ar.scraper.web.EventsController;
import ar.scraper.web.dto.MlDtos;
import ar.scraper.web.dto.ScrapeDtos;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The stream against a real Tomcat and the real security chain. MockMvc cannot see what happens when
 * the container re-dispatches a finished async request, nor how a client that does not read behaves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SseRealPortTest.Ctx.class,
        properties = {
                "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
                "app.cors.allowed-origins=http://localhost:5173",
                "spring.mvc.async.request-timeout=20000"
        })
@Epic("Status push")
@Feature("SSE endpoint")
@Story("Real port: heartbeat, async dispatch, slow clients, authorization")
@DisplayName("GET /api/events on a real port")
class SseRealPortTest {

    static final int CAPACITY = 8;
    static final long TIMEOUT_MS = 1_800;
    static final long PING_MS = 250;

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class, EventsController.class})
    static class Ctx {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        InProcessStatusEvents bus() {
            return new InProcessStatusEvents();
        }

        @Bean
        StatusStreams streams(InProcessStatusEvents bus) {
            return new StatusStreams(bus, CAPACITY, TIMEOUT_MS, PING_MS);
        }

        @Bean
        ApiController api() {
            ApiController api = Mockito.mock(ApiController.class);
            when(api.statusSnapshot()).thenReturn(ScrapeDtos.Status.builder()
                    .status("IDLE").mensaje("Listo").tieneData(false).build());
            when(api.mlEstadoSnapshot()).thenReturn(new MlDtos.Estado());
            return api;
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    InProcessStatusEvents bus;

    @Autowired
    StatusStreams streams;

    @Autowired
    TokenService tokens;

    @MockBean
    UsuarioRepository usuarios;

    private String tokenDe(String role) {
        UUID id = UUID.randomUUID();
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("u-" + role.toLowerCase(), List.of(role), null)));
        return tokens.emitir(id);
    }

    private HttpRequest.Builder request(String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/events"))
                .timeout(Duration.ofSeconds(15));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    /** Reads the stream on a background thread so a test can publish while it is open. */
    private final class Reading {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean ended;
        volatile int status;
        final Thread thread;

        Reading(String token) {
            thread = new Thread(() -> {
                try {
                    HttpResponse<java.util.stream.Stream<String>> r = HttpClient.newHttpClient()
                            .send(request(token).GET().build(), HttpResponse.BodyHandlers.ofLines());
                    status = r.statusCode();
                    r.body().forEach(lines::add);
                    ended = true;
                } catch (Throwable t) {
                    failure.set(t);
                    ended = true;
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        boolean has(String fragment) {
            return lines.stream().anyMatch(l -> l.contains(fragment));
        }

        void await(String what, java.util.function.BooleanSupplier c) throws Exception {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!c.getAsBoolean()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("timed out waiting for " + what + "; lines=" + lines);
                }
                Thread.sleep(10);
            }
        }
    }

    @BeforeEach
    void settle() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (streams.openStreams() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(25);
    }

    @Test
    @DisplayName("without a token the stream is refused with the standard 401 envelope")
    void withoutATokenItIs401() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient()
                .send(request(null).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("no_autenticado");
    }

    @Test
    @DisplayName("a snapshot arrives first and then a heartbeat comment, with the streaming headers")
    void snapshotThenHeartbeat() throws Exception {
        Reading stream = new Reading(tokenDe("VIEWER"));

        stream.await("the snapshot", () -> stream.has("event:snapshot"));
        stream.await("a heartbeat", () -> stream.has(": ping"));
        assertThat(stream.status).isEqualTo(200);

        HttpResponse<java.io.InputStream> headers = HttpClient.newHttpClient().send(
                request(tokenDe("VIEWER")).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(headers.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(headers.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(headers.headers().firstValue("X-Accel-Buffering")).contains("no");
        headers.body().close();
    }

    @Test
    @DisplayName("when the emitter times out the container's ASYNC dispatch is not denied: the stream ends cleanly")
    void timeoutEndsTheStreamCleanly() throws Exception {
        Reading stream = new Reading(tokenDe("VIEWER"));
        long t0 = System.currentTimeMillis();

        stream.await("the stream to end", () -> stream.ended);

        assertThat(stream.failure.get()).as("an abrupt end means the ASYNC dispatch was refused").isNull();
        assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(TIMEOUT_MS - 400);
        assertThat(stream.has("event:snapshot")).isTrue();
        stream.await("the subscription to be released", () -> streams.openStreams() == 0);
    }

    @Test
    @DisplayName("a VIEWER never hears cron_execution changes; an ADMIN does")
    void cronChangesAreAdminOnly() throws Exception {
        Reading viewer = new Reading(tokenDe("VIEWER"));
        Reading admin = new Reading(tokenDe("ADMIN"));
        viewer.await("viewer snapshot", () -> viewer.has("event:snapshot"));
        admin.await("admin snapshot", () -> admin.has("event:snapshot"));

        bus.publish(new StatusEvent.DbChanged("cron_execution", "INSERT", 1L, null, null, 9L, "running"));
        bus.publish(new StatusEvent.DbChanged("scrape_run", "UPDATE", 3L, null, null, null, "COMPLETED"));

        admin.await("the admin to hear both", () -> admin.has("cron_execution") && admin.has("COMPLETED"));
        viewer.await("the viewer to hear the run", () -> viewer.has("COMPLETED"));
        assertThat(viewer.has("cron_execution")).isFalse();
    }

    @Test
    @DisplayName("a client that does not read never stalls the bus, and is told to resync after the gap")
    void slowClientGetsDropOldestAndAResync() throws Exception {
        String token = tokenDe("VIEWER");
        Reading fast = new Reading(tokenDe("VIEWER"));
        fast.await("fast snapshot", () -> fast.has("event:snapshot"));

        try (Socket slow = new Socket("127.0.0.1", port)) {
            slow.getOutputStream().write(("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + token + "\r\nAccept: text/event-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            slow.getOutputStream().flush();
            long deadline = System.currentTimeMillis() + 10_000;
            while (streams.openStreams() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);

            String fat = "x".repeat(4_000);
            long t0 = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                bus.publish(new StatusEvent.MlStatus(StatusEvent.MlStatus.Kind.TRAINING, true, "training", i % 100,
                        fat, null));
            }
            long publishMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(publishMs).as("publishing 10000 events never waits for a reader").isLessThan(8_000);
            fast.await("the fast client to keep hearing", () -> fast.has("ml.status"));

            StringBuilder text = new StringBuilder();
            slow.setSoTimeout(3_000);
            InputStream in = slow.getInputStream();
            byte[] buf = new byte[16 * 1024];
            try {
                int n;
                while ((n = in.read(buf)) != -1) text.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } catch (IOException endOfStream) {
                // the emitter timeout closes the stream; whatever arrived is what we assert on
            }
            String all = text.toString();

            assertThat(all).contains("event:resync");
            long received = all.split("event:ml.status", -1).length - 1L;
            assertThat(received).as("events were dropped for the slow client").isLessThan(10_000);
        }
    }

    @Test
    @DisplayName("events published after a clean end are not sent to a closed stream")
    void nothingIsSentAfterTheStreamClosed() throws Exception {
        Reading stream = new Reading(tokenDe("VIEWER"));
        stream.await("the stream to end", () -> stream.ended);
        stream.await("the subscription to be released", () -> streams.openStreams() == 0);

        bus.publish(new StatusEvent.ScrapeStatus(ScraperStatus.DONE, "late"));

        assertThat(stream.has("late")).isFalse();
    }
}
