package ar.scraper.scrapers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Contra un stub {@link HttpServer} que imita el POST /v1: no hace falta FlareSolverr real. */
@Epic("Scraping")
@Feature("FlareSolverr Client")
@DisplayName("FlareSolverrClient — challenge solver contract")
class FlareSolverrClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<Integer> statusToReturn = new AtomicReference<>(200);
    private final AtomicReference<String> bodyToReturn = new AtomicReference<>("");

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] req = ex.getRequestBody().readAllBytes();
        lastBody.set(new String(req, StandardCharsets.UTF_8));
        byte[] resp = bodyToReturn.get().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(statusToReturn.get(), resp.length);
        ex.getResponseBody().write(resp);
        ex.close();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Step("Configure the stub to answer a FlareSolverr solution payload")
    private void returnSolution(int solutionStatus, String cookiesJson) {
        bodyToReturn.set("""
                {"status":"ok","message":"","solution":{"url":"https://fullh4rd.com.ar/","status":%d,
                "headers":{},"response":"<html></html>","cookies":[%s],
                "userAgent":"Mozilla/5.0 (X11; Linux x86_64) Chrome/152.0.0.0 Safari/537.36"}}
                """.formatted(solutionStatus, cookiesJson));
    }

    @Test
    @DisplayName("Resuelve el challenge y devuelve cf_clearance + UA")
    void readsClearanceCookieAndUserAgent() {
        returnSolution(200, """
                {"name":"cf_clearance","value":"abc123"},
                {"name":"__cf_bm","value":"xyz789"},
                {"name":"_ga","value":"GA1.1.1"}""");

        Optional<FlareSolverrClient.Ticket> ticket = new FlareSolverrClient(baseUrl)
                .resolver("https://fullh4rd.com.ar/", 60_000);

        assertThat(ticket).isPresent();
        assertThat(ticket.get().cookies()).extracting(FlareSolverrClient.CfCookie::name)
                .containsExactlyInAnyOrder("cf_clearance", "__cf_bm");
        assertThat(ticket.get().clearance("cf_clearance")).contains("abc123");
        assertThat(ticket.get().userAgent()).contains("Chrome");
    }

    @Test
    @DisplayName("Envía POST /v1 con request.get y la URL pedida")
    void postsRequestGetContract() {
        returnSolution(200, """
                {"name":"cf_clearance","value":"abc123"}""");

        new FlareSolverrClient(baseUrl).resolver("https://fullh4rd.com.ar/productos", 30_000);

        assertThat(lastBody.get()).contains("\"cmd\":\"request.get\"");
        assertThat(lastBody.get()).contains("\"url\":\"https://fullh4rd.com.ar/productos\"");
        assertThat(lastBody.get()).contains("\"maxTimeout\":30000");
    }

    @Test
    @DisplayName("Solver caído no lanza: devuelve vacío")
    void solverDownReturnsEmpty() {
        // Point at a port with no listener: stop the stub, keep the URL.
        server.stop(0);
        Optional<FlareSolverrClient.Ticket> ticket = new FlareSolverrClient(baseUrl)
                .resolver("https://fullh4rd.com.ar/", 5_000);
        assertThat(ticket).isEmpty();
    }

    @Test
    @DisplayName("HTTP 500 del solver devuelve vacío")
    void solverHttp500ReturnsEmpty() {
        statusToReturn.set(500);
        Optional<FlareSolverrClient.Ticket> ticket = new FlareSolverrClient(baseUrl)
                .resolver("https://fullh4rd.com.ar/", 5_000);
        assertThat(ticket).isEmpty();
    }

    @Test
    @DisplayName("Solución sin cf_clearance devuelve vacío")
    void solutionWithoutClearanceReturnsEmpty() {
        returnSolution(200, """
                {"name":"_ga","value":"GA1.1.1"}""");
        Optional<FlareSolverrClient.Ticket> ticket = new FlareSolverrClient(baseUrl)
                .resolver("https://fullh4rd.com.ar/", 5_000);
        assertThat(ticket).isEmpty();
    }

    @Test
    @DisplayName("Solution status distinto de 200 devuelve vacío")
    void solutionNon200ReturnsEmpty() {
        returnSolution(403, """
                {"name":"cf_clearance","value":"abc123"}""");
        Optional<FlareSolverrClient.Ticket> ticket = new FlareSolverrClient(baseUrl)
                .resolver("https://fullh4rd.com.ar/", 5_000);
        assertThat(ticket).isEmpty();
    }

    @Test
    @DisplayName("Status error del solver devuelve vacío")
    void solverErrorStatusReturnsEmpty() {
        bodyToReturn.set("""
                {"status":"error","message":"error solving the challenge"}
                """);
        Optional<FlareSolverrClient.Ticket> ticket = new FlareSolverrClient(baseUrl)
                .resolver("https://fullh4rd.com.ar/", 5_000);
        assertThat(ticket).isEmpty();
    }
}