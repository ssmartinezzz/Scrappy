package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialImportPort;
import ar.scraper.catalog.HistorialPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PrecioHistoricoExporter implements HistorialParaMl {

    private static final Logger LOG = LoggerFactory.getLogger(PrecioHistoricoExporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** El archivo que el ML escribía cuando era dueño del historial; nombre distinto al derivado. */
    static final String LEGACY = "precio_historico.json";
    /** El pipeline retiene 60 puntos por producto. */
    static final int VENTANA_DIAS = 60;

    private final HistorialPort historial;
    private final HistorialImportPort importador;
    private final Clock clock;

    @Autowired
    public PrecioHistoricoExporter(HistorialPort historial, HistorialImportPort importador) {
        this(historial, importador, Clock.systemDefaultZone());
    }

    PrecioHistoricoExporter(HistorialPort historial, HistorialImportPort importador, Clock clock) {
        this.historial = historial;
        this.importador = importador;
        this.clock = clock;
    }

    @Override
    public void preparar(Path hist, String productosJson) {
        importarLegacy(hist);
        try {
            exportar(hist, urlsDe(productosJson));
        } catch (IOException e) {
            LOG.warn("[ML] No se pudo escribir el historial {} — el pipeline corre sin historia: {}",
                    hist, e.getMessage());
        }
    }

    @Override
    public void descartar(Path hist) {
        try {
            Files.deleteIfExists(hist);
        } catch (IOException e) {
            LOG.warn("[ML] No se pudo borrar {}: {}", hist, e.getMessage());
        }
    }

    /**
     * El derivado lleva otro nombre, así que un {@code precio_historico.json} sólo puede ser el que
     * dejaba el ML. Se aparta y se retira recién cuando la tabla lo absorbió.
     */
    private void importarLegacy(Path hist) {
        Path legacy = hist.resolveSibling(LEGACY);
        Path pendiente = vecino(legacy, ".legacy");
        try {
            if (Files.exists(legacy) && !Files.exists(pendiente)) {
                Files.move(legacy, pendiente);
            }
            if (!Files.exists(pendiente)) return;
            int insertadas = importador.importarCambios(cambiosLegacy(pendiente));
            Files.move(pendiente, vecino(legacy, ".imported"), StandardCopyOption.REPLACE_EXISTING);
            LOG.info("[ML] Historial legacy importado a precio_historico: {} cambios", insertadas);
        } catch (Exception e) {
            LOG.warn("[ML] No se pudo importar el historial legacy ({}); se reintenta en la próxima corrida: {}",
                    pendiente, e.getMessage());
        }
    }

    private static Map<String, List<HistorialEntry>> cambiosLegacy(Path archivo) throws IOException {
        var cambios = new LinkedHashMap<String, List<HistorialEntry>>();
        MAPPER.readTree(archivo.toFile()).fields().forEachRemaining(e -> {
            var puntos = new ArrayList<HistorialEntry>();
            for (JsonNode p : e.getValue()) {
                String fecha = p.path("fecha").asText("");
                if (StringUtils.isNotBlank(fecha)) {
                    puntos.add(new HistorialEntry(fecha, p.path("precio").asDouble()));
                }
            }
            cambios.put(e.getKey(), HistorialLegacy.colapsarACambios(puntos));
        });
        return cambios;
    }

    private static Path vecino(Path archivo, String sufijo) {
        return archivo.resolveSibling(archivo.getFileName() + sufijo);
    }

    private void exportar(Path hist, List<String> urls) throws IOException {
        LocalDate hoy = LocalDate.now(clock);
        Map<String, List<HistorialEntry>> cambios = historial.getHistorialPrecios(urls);
        Map<String, List<HistorialEntry>> diario = new LinkedHashMap<>();
        cambios.forEach((url, c) -> {
            var puntos = HistorialDensificador.densificar(c, hoy, VENTANA_DIAS);
            if (!puntos.isEmpty()) diario.put(url, puntos);
        });
        MAPPER.writeValue(hist.toFile(), diario);
    }

    private static List<String> urlsDe(String productosJson) throws IOException {
        Set<String> urls = new LinkedHashSet<>();
        for (JsonNode p : MAPPER.readTree(productosJson)) {
            String url = p.path("url").asText("");
            if (StringUtils.isNotBlank(url)) urls.add(url);
        }
        return new ArrayList<>(urls);
    }
}
