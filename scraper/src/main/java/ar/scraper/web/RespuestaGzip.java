package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.web.cache.CuerposGzip;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * Serves a cached view as a memoized gzip body when the client accepts it, so Tomcat does not deflate the same
 * megabytes on every request. The body is {@code ApiResponse.ok(view)} written by the very mapper the message
 * converter uses; the response carries {@code Content-Encoding}, which makes Tomcat leave it alone.
 */
@Component
public class RespuestaGzip {

    private static final String GZIP = "gzip";

    private final JsonMapper mapper;
    private final CuerposGzip cuerpos;
    private final boolean habilitada;

    public RespuestaGzip(JsonMapper mapper, CuerposGzip cuerpos,
            @Value("${server.compression.enabled:false}") boolean habilitada) {
        this.mapper = mapper;
        this.cuerpos = cuerpos;
        this.habilitada = habilitada;
    }

    /** Empty when compression is off, the client does not take gzip, or the view is empty (too small to deflate). */
    public <T> Optional<ResponseEntity<byte[]>> de(String acceptEncoding, List<T> vista) {
        if (!habilitada || vista == null || vista.isEmpty() || !acepta(acceptEncoding)) return Optional.empty();
        byte[] cuerpo = cuerpos.de(vista, () -> mapper.writeValueAsBytes(ApiResponse.ok(vista)));
        return Optional.of(ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_ENCODING, GZIP)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .contentLength(cuerpo.length)
                .body(cuerpo));
    }

    /**
     * RFC 9110: an explicit {@code gzip} entry decides, else {@code *} does, else the client takes no gzip. A
     * {@code q} of zero (or one that does not parse) refuses.
     */
    static boolean acepta(String acceptEncoding) {
        if (acceptEncoding == null) return false;
        Boolean gzip = null;
        Boolean comodin = null;
        for (String parte : acceptEncoding.split(",")) {
            String[] campos = parte.split(";");
            String codificacion = campos[0].trim();
            boolean acepta = pesoPositivo(campos);
            if (GZIP.equalsIgnoreCase(codificacion)) gzip = acepta;
            else if ("*".equals(codificacion)) comodin = acepta;
        }
        return gzip != null ? gzip : Boolean.TRUE.equals(comodin);
    }

    private static boolean pesoPositivo(String[] campos) {
        for (int i = 1; i < campos.length; i++) {
            String parametro = campos[i].trim();
            if (parametro.length() < 2 || !parametro.substring(0, 2).equalsIgnoreCase("q=")) continue;
            try {
                return Double.parseDouble(parametro.substring(2).trim()) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
