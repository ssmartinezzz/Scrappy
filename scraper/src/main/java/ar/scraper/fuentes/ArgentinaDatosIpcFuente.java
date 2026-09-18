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

// The source publishes the monthly RATE (%), from 1943. Compounding all of it
// overflows NUMERIC(14,4) by 1984 (verified 2026-09-18: 1.1e10), and the whole
// batch aborts. The level is anchored where INDEC's national CPI is: Dec 2016 = 100.
@Component
class ArgentinaDatosIpcFuente implements FuenteIndicePort {

    private static final String URL = "https://api.argentinadatos.com/v1/finanzas/indices/inflacion";
    private static final double NIVEL_BASE = 100.0;
    private static final LocalDate MES_BASE = LocalDate.of(2016, 12, 31);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<PuntoIndice> descargar(Indice indice) throws FuenteIndiceException {
        try {
            return parsear(HttpJson.get(URL));
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException("ArgentinaDatosIpcFuente: " + e.getMessage(), e);
        }
    }

    static List<PuntoIndice> parsear(String body) throws FuenteIndiceException {
        try {
            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray()) {
                throw new FuenteIndiceException("respuesta de argentinadatos IPC no es un array");
            }
            List<PuntoIndice> puntos = new ArrayList<>();
            double nivel = NIVEL_BASE;
            for (JsonNode n : arr) {
                LocalDate fecha = LocalDate.parse(n.path("fecha").asText());
                if (fecha.isBefore(MES_BASE)) continue;
                if (!fecha.equals(MES_BASE)) {
                    nivel = nivel * (1.0 + n.path("valor").asDouble() / 100.0);
                }
                puntos.add(new PuntoIndice(Indice.IPC, fecha, nivel));
            }
            return puntos;
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException("no se pudo parsear IPC (argentinadatos): " + e.getMessage(), e);
        }
    }
}
