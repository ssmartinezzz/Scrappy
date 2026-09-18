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

/** USD oficial (BNA), D2: a single series, no brecha opinion. Uses {@code venta}. */
@Component
class ArgentinaDatosDolarFuente implements FuenteIndicePort {

    private static final String URL = "https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<PuntoIndice> descargar(Indice indice) throws FuenteIndiceException {
        try {
            return parsear(HttpJson.get(URL));
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException("ArgentinaDatosDolarFuente: " + e.getMessage(), e);
        }
    }

    static List<PuntoIndice> parsear(String body) throws FuenteIndiceException {
        try {
            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray()) {
                throw new FuenteIndiceException("respuesta de argentinadatos dólar no es un array");
            }
            List<PuntoIndice> puntos = new ArrayList<>();
            for (JsonNode n : arr) {
                LocalDate fecha = LocalDate.parse(n.path("fecha").asText());
                double venta = n.path("venta").asDouble();
                puntos.add(new PuntoIndice(Indice.USD_OFICIAL, fecha, venta));
            }
            return puntos;
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException("no se pudo parsear dólar oficial: " + e.getMessage(), e);
        }
    }
}
