package ar.scraper.web;

import ar.scraper.web.cache.CuerposGzip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Accept-Encoding negotiation for the memoized gzip body")
class RespuestaGzipNegociacionTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" takes gzip")
    @ValueSource(strings = {"gzip", "GZIP", "Gzip", "  gzip  ", "gzip, deflate, br", "br, gzip", "deflate,gzip;q=0.5",
            "gzip;q=1", "gzip ; q=0.001", "gzip;Q=0.8", "gzip;level=5;q=0.9", "*", "*;q=0.1", "br, *", "identity;q=0, gzip"})
    void takes(String header) {
        assertThat(RespuestaGzip.acepta(header)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" does not take gzip")
    @ValueSource(strings = {"", "  ", "identity", "deflate, br", "gzip;q=0", "gzip; q=0.0", "gzip;q=0.000", "GZIP;Q=0",
            "*;q=0", "gzip;q=0, *", "gzip;q=0, *;q=1", "br, gzip;q=0", "gzip;q=abc", "gzip;q=", "x-gzip", "gzipp", "xgzip, br",
            "compress"})
    void doesNotTake(String header) {
        assertThat(RespuestaGzip.acepta(header)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    void absentHeaderDoesNotTake(String header) {
        assertThat(RespuestaGzip.acepta(header)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] explicit gzip entry \"{0}\" beats the wildcard")
    @CsvSource(delimiter = '|', value = {"gzip;q=0, *;q=1|false", "*;q=1, gzip;q=0|false", "*;q=0, gzip|true",
            "gzip, *;q=0|true"})
    void explicitEntryBeatsWildcard(String header, boolean esperado) {
        assertThat(RespuestaGzip.acepta(header)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("with server.compression.enabled=false the identity path is always taken")
    void disabledNeverCompresses() {
        var respuesta = new RespuestaGzip(JsonMapper.builder().build(), new CuerposGzip(), false);

        assertThat(respuesta.de("gzip", List.of("a", "b"))).isEmpty();
    }

    @Test
    @DisplayName("an empty view stays identity: it is below Tomcat's own compression threshold too")
    void emptyViewIsIdentity() {
        var respuesta = new RespuestaGzip(JsonMapper.builder().build(), new CuerposGzip(), true);

        assertThat(respuesta.de("gzip", List.of())).isEmpty();
    }

    @Test
    @DisplayName("enabled, a gzip client gets gzip headers and a client without it gets nothing to override")
    void enabledNegotiates() {
        var respuesta = new RespuestaGzip(JsonMapper.builder().build(), new CuerposGzip(), true);
        var vista = List.of("a", "b");

        var con = respuesta.de("gzip", vista);
        assertThat(con).isPresent();
        assertThat(con.get().getHeaders().getFirst("Content-Encoding")).isEqualTo("gzip");
        assertThat(con.get().getHeaders().getFirst("Vary")).isEqualTo("Accept-Encoding");
        assertThat(con.get().getHeaders().getContentLength()).isEqualTo(con.get().getBody().length);
        assertThat(respuesta.de("identity", vista)).isEmpty();
        assertThat(respuesta.de(null, vista)).isEmpty();
    }
}
