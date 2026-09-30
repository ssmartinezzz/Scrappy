package ar.scraper.web;

import ar.scraper.json.HistorialJson;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.MlDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** ML pipeline operations: trends, price history, re-apply/renormalise and training. Mappings live in {@link ApiController}. */
class MlEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(MlEndpoints.class);

    private final ScraperService service;
    private final ar.scraper.ml.CategoriaStatsPort categoriaStats;
    private final ar.scraper.ml.MlOutputPort mlOutput;
    private final ar.scraper.catalog.HistorialPort historial;
    private final ar.scraper.catalog.ProductPort productos;
    private final ar.scraper.aggregator.ResultAggregator aggregator;
    private final ar.scraper.ml.PythonRunner pythonRunner;

    MlEndpoints(ScraperService service,
                ar.scraper.ml.CategoriaStatsPort categoriaStats,
                ar.scraper.ml.MlOutputPort mlOutput,
                ar.scraper.catalog.HistorialPort historial,
                ar.scraper.catalog.ProductPort productos,
                ar.scraper.aggregator.ResultAggregator aggregator,
                ar.scraper.ml.PythonRunner pythonRunner) {
        this.service = service;
        this.categoriaStats = categoriaStats;
        this.mlOutput = mlOutput;
        this.historial = historial;
        this.productos = productos;
        this.aggregator = aggregator;
        this.pythonRunner = pythonRunner;
    }

    // Payload is the trainer's JSON enriched with DB stats: dynamic, hence JsonNode.
    ResponseEntity<ApiResponse<JsonNode>> tendencias() {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        var ml = aggregator.getLastMlOutput();

        // 503 lets the UI tell "the pipeline failed" from "no data yet" (204).
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
     * 204 without history is for widgets (a sparkline with nothing to draw). The dedicated page
     * cannot use it: see {@code CatalogoEndpoints.productoDetalle}, which answers 200 with empty points.
     */
    ResponseEntity<ApiResponse<JsonNode>> historial(String url) {
        var hist = historial.cargarHistorial(url);
        if (hist.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(HistorialJson.construir(hist)));
    }

    /**
     * Re-applies the ML pipeline over the in-memory catalog in the background.
     * Scoring is not re-entrant: PythonRunner resolves ml_productos.json / ml_output.json in the
     * process cwd, so concurrent runs overwrite each other silently. The scrape owns the slot;
     * atomic exclusion lives in {@code PythonRunner.conReservaDeScoring}.
     */
    ResponseEntity<ApiResponse<MlDtos.Started>> mlAplicar() {
        var r = service.getLastResult();
        if (r == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "No hay datos. Ejecutá un scraping primero.");
        }
        if (service.getStatus() == ar.scraper.scrape.ScraperStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso",
                    "Hay un scraping en curso, que ya corre el pipeline ML. "
                            + "Esperá a que termine y volvé a intentar.");
        }
        if (pythonRunner.isScoringEnCurso()) {
            throw new ApiException(HttpStatus.CONFLICT, "ml_en_curso",
                    "Ya hay una corrida del pipeline ML en vuelo. "
                            + "Esperá a que termine y volvé a intentar.");
        }

        Thread.ofVirtual().start(() -> {
            try {
                String prodJson = aggregator.getMlEnricher().serializarProductos(r.productos());
                var mlOut = pythonRunner.ejecutar(prodJson);
                if (mlOut != null) {
                    var enriquecidos = aggregator.getMlEnricher().enriquecer(r.productos(), mlOut);
                    java.util.Map<String,String> catOrig = new java.util.HashMap<>();
                    r.productos().forEach(p -> { if(p.url()!=null) catOrig.put(p.url(), p.categoria()!=null?p.categoria():""); });
                    enriquecidos.forEach(p -> {
                        String orig = catOrig.get(p.url());
                        if (orig != null && !p.categoria().equals(orig))
                            try { productos.actualizarCategoria(p.url(), p.categoria()); } catch(Exception ignored){}
                    });
                    aggregator.setLastMlOutput(mlOut);
                    mlOutput.guardarMlOutput(mlOut);
                    LOG.info("[ML/aplicar] Pipeline re-aplicado: {} productos refinados", enriquecidos.size());
                }
            } catch (Exception e) {
                LOG.warn("[ML/aplicar] Error: {}", e.getMessage());
            }
        });
        return ResponseEntity.ok(ApiResponse.ok(new MlDtos.Started("started",
                "Pipeline ML re-ejecutándose en background. Refrescá la página en 30 segundos.")));
    }

    ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> mlRenormalizar() {
        return ResponseEntity.ok(ApiResponse.ok(aggregator.renormalizarCatalogo()));
    }

    ResponseEntity<ApiResponse<MlDtos.Estado>> mlEstado() {
        java.io.File modelsDir = new java.io.File("_models");
        java.io.File textModel = new java.io.File(modelsDir, "text_classifier.pkl");
        java.io.File imgModel  = new java.io.File(modelsDir, "image_model.pt");
        java.io.File textMeta  = new java.io.File(modelsDir, "text_meta.json");

        var estado = new MlDtos.Estado();
        estado.setHasTextModel(textModel.exists());
        estado.setHasImageModel(imgModel.exists());
        if (textMeta.exists()) {
            try {
                estado.setTextMeta(new com.fasterxml.jackson.databind.ObjectMapper().readTree(textMeta));
            } catch (Exception ignored) {}
        }
        var ts = pythonRunner.getTrainingStatus();
        estado.setTraining(new MlDtos.Training(ts.running(), ts.phase(), ts.pct(), ts.msg(),
                ts.startedAt() != null ? ts.startedAt() : ""));

        long embeddingsCount = productos.contarEmbeddings();
        var lastResult = service.getLastResult();
        int totalProductos = lastResult != null ? lastResult.productos().size() : 0;
        estado.setEmbeddingsCount(embeddingsCount);
        estado.setTotalProductos(totalProductos);
        estado.setCoveragePct(totalProductos > 0
                ? Math.round((double) embeddingsCount / totalProductos * 1000.0) / 10.0
                : 0.0);
        return ResponseEntity.ok(ApiResponse.ok(estado));
    }

    ResponseEntity<ApiResponse<MlDtos.Started>> mlEntrenar(boolean images, int epochs) {
        if (pythonRunner.isTrainingRunning()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ml_en_curso", "Entrenamiento ya en curso");
        }

        // Manual "Construir índice visual": text re-train first, then the embeddings backfill,
        // on one background thread. Both are forced: an explicit click is a deliberate full rebuild.
        // entrenarEnBackground stays live as the post-scrape auto-training path; do not delete it.
        boolean forceRetrainTexto = true;
        boolean forceBackfillEmbeddings = true;
        boolean iniciado = pythonRunner.construirIndiceVisualEnBackground(
                forceRetrainTexto, images, epochs, forceBackfillEmbeddings);
        // Two near-simultaneous POSTs can both pass the pre-check; the runner's CAS picks one winner.
        if (!iniciado) {
            throw new ApiException(HttpStatus.CONFLICT, "ml_en_curso", "Entrenamiento ya en curso");
        }
        return ResponseEntity.ok(ApiResponse.ok(new MlDtos.Started("started", null)));
    }

    ResponseEntity<ApiResponse<MlDtos.Resultado>> mlResultado() {
        var s = pythonRunner.getTrainingStatus();
        return ResponseEntity.ok(ApiResponse.ok(new MlDtos.Resultado(s.running(), s.phase(), s.pct(),
                s.msg(), !s.running() && !"idle".equals(s.phase()))));
    }
}
