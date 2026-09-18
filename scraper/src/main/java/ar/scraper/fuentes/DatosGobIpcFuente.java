package ar.scraper.fuentes;

import ar.scraper.indices.FuenteIndiceException;
import ar.scraper.indices.FuenteIndicePort;
import ar.scraper.indices.Indice;
import ar.scraper.indices.PuntoIndice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback IPC source (INDEC, official series). Unlike
 * {@link ArgentinaDatosIpcFuente}, {@code valor} here already is a level —
 * no rate-to-level conversion. The hardcoded series id is stale as of
 * 2026-09-18 ({@code {"errors": [...]}}), which this parser turns into an
 * ordinary {@link FuenteIndiceException} (so the chain falls through) rather
 * than an NPE on a missing {@code data} field.
 */
@Component
class DatosGobIpcFuente implements FuenteIndicePort {

    private static final String URL = "https://apis.datos.gob.ar/series/api/series/"
            + "?ids=148.3_INIVELGENERAL_DICI_M_26&format=json&limit=1000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<PuntoIndice> descargar(Indice indice) throws FuenteIndiceException {
        try {
            return parsear(HttpJson.get(URL));
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException("DatosGobIpcFuente: " + e.getMessage(), e);
        }
    }

    static List<PuntoIndice> parsear(String body) throws FuenteIndiceException {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new FuenteIndiceException("respuesta de datos.gob.ar sin campo 'data' (serie inexistente)");
            }
            List<PuntoIndice> puntos = new ArrayList<>();
            for (JsonNode row : data) {
                LocalDate fecha = LocalDate.parse(row.get(0).asText());
                double valor = row.get(1).asDouble();
                puntos.add(new PuntoIndice(Indice.IPC, fecha, valor));
            }
            return puntos;
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException("no se pudo parsear IPC (datos.gob.ar): " + e.getMessage(), e);
        }
    }
}
