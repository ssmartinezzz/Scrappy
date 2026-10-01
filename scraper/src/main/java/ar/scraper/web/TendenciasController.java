package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.json.HistorialJson;
import ar.scraper.ml.CategoriaStatsPort;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TendenciasController {

    private final ScraperService service;
    private final CategoriaStatsPort categoriaStats;
    private final HistorialPort historial;
    private final ar.scraper.aggregator.ResultAggregator aggregator;

    public TendenciasController(ScraperService service, CategoriaStatsPort categoriaStats,
                                HistorialPort historial, ar.scraper.aggregator.ResultAggregator aggregator) {
        this.service = service;
        this.categoriaStats = categoriaStats;
        this.historial = historial;
        this.aggregator = aggregator;
    }

    @GetMapping("/tendencias")
    public ResponseEntity<ApiResponse<JsonNode>> tendencias() {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        var ml = aggregator.getLastMlOutput();

        if (ml == null || ml.isNull()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ml_failed",
                    "El pipeline ML falló en la última corrida.");
        }

        var scoresNode = ml.path("scores");
        var tendNode   = ml.path("tendencias");
        boolean valido = scoresNode.isObject() && !scoresNode.isEmpty() && tendNode.isObject();
        if (!valido) return ResponseEntity.noContent().build();

        com.fasterxml.jackson.databind.node.ObjectNode result =
                (com.fasterxml.jackson.databind.node.ObjectNode) tendNode.deepCopy();

        var catStats = categoriaStats.cargarCategoriaStats();
        if (!catStats.isEmpty()) {
            var catNode = result.putObject("distribucionCategorias");
            catStats.forEach((cat, s) -> {
                var n = catNode.putObject(cat);
                n.put("n", s.n());
                n.put("mean", s.mean());
                n.put("median", s.median());
                n.put("mode", s.mode());
                n.put("std", s.std());
                n.put("cv", Math.round(s.cv() * 10.0) / 10.0);
                n.put("q1", s.q1());
                n.put("q3", s.q3());
                n.put("iqr", s.iqr());
                n.put("mad", s.mad());
                n.put("fence_low", s.fenceLow());
                n.put("fence_high", s.fenceHigh());
            });
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * . 204 without history is for widgets (a sparkline with nothing to draw). The dedicated page
     * cannot use it: see {@code CatalogoController.productoDetalle}, which answers 200 with empty
     * points.
     */
    @GetMapping("/historial")
    public ResponseEntity<ApiResponse<JsonNode>> historial(@RequestParam String url) {
        var hist = historial.cargarHistorial(url);
        if (hist.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(HistorialJson.construir(hist)));
    }
}
