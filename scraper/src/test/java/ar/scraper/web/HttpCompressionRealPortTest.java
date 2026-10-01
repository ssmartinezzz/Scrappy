package ar.scraper.web;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compression is a property of the real Tomcat, so only a real port shows it. The SSE case guards
 * the stream: a compressed event stream is buffered and stops arriving event by event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HttpCompressionRealPortTest.Ctx.class)
@Epic("Performance")
@Feature("HTTP compression")
@DisplayName("Response compression on a real port")
class HttpCompressionRealPortTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class})
    @Import(Endpoints.class)
    static class Ctx {
    }

    @RestController
    static class Endpoints {
        @GetMapping("/test/catalogo")
        List<Map<String, Object>> catalogo() {
            return IntStream.range(0, 200)
                    .mapToObj(i -> Map.<String, Object>of("nombre", "Producto " + i, "precio", 1000 + i))
                    .toList();
        }

        @GetMapping(path = "/test/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter eventos() throws Exception {
            SseEmitter e = new SseEmitter(5_000L);
            e.send(SseEmitter.event().name("snapshot").data("x".repeat(4_096)));
            e.complete();
            return e;
        }
    }

    @LocalServerPort
    int port;

    private HttpResponse<byte[]> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("a JSON body is sent gzipped")
    void jsonIsGzipped() throws Exception {
        HttpResponse<byte[]> r = get("/test/catalogo");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Encoding")).hasValue("gzip");
    }

    @Test
    @DisplayName("the event stream is never compressed")
    void eventStreamIsNotCompressed() throws Exception {
        HttpResponse<byte[]> r = get("/test/eventos");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Encoding")).isEmpty();
    }
}
