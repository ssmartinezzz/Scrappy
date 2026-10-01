package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.web.dto.MlDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MlController {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(MlController.class);

    private final ScraperService service;
    private final MlOutputPort mlOutput;
    private final ProductPort productos;
    private final ar.scraper.aggregator.ResultAggregator aggregator;
    private final PythonRunner pythonRunner;
    private final MlEstadoView estadoView;

    public MlController(ScraperService service, MlOutputPort mlOutput, ProductPort productos,
                        ar.scraper.aggregator.ResultAggregator aggregator, PythonRunner pythonRunner,
                        MlEstadoView estadoView) {
        this.service = service;
        this.mlOutput = mlOutput;
        this.productos = productos;
        this.aggregator = aggregator;
        this.pythonRunner = pythonRunner;
        this.estadoView = estadoView;
    }

    /**
     * Re-applies the ML pipeline over the in-memory catalog in the background. Scoring is not
     * re-entrant:
     */
    @PostMapping("/ml/aplicar")
    public ResponseEntity<ApiResponse<MlDtos.Started>> mlAplicar() {
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

    @PostMapping("/ml/renormalizar")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> mlRenormalizar() {
        return ResponseEntity.ok(ApiResponse.ok(aggregator.renormalizarCatalogo()));
    }

    @GetMapping("/ml/estado")
    public ResponseEntity<ApiResponse<MlDtos.Estado>> mlEstado() {
        return ResponseEntity.ok(ApiResponse.ok(estadoView.snapshot()));
    }

    @PostMapping("/ml/entrenar")
    public ResponseEntity<ApiResponse<MlDtos.Started>> mlEntrenar(@RequestParam(defaultValue = "false") boolean images,
            @RequestParam(defaultValue = "8") int epochs) {
        if (pythonRunner.isTrainingRunning()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ml_en_curso", "Entrenamiento ya en curso");
        }

        // Manual "Construir índice visual": text re-train first, then the embeddings backfill, on
        // one background thread.
        boolean forceRetrainTexto = true;
        boolean forceBackfillEmbeddings = true;
        boolean iniciado = pythonRunner.construirIndiceVisualEnBackground(
                forceRetrainTexto, images, epochs, forceBackfillEmbeddings);
        if (!iniciado) {
            throw new ApiException(HttpStatus.CONFLICT, "ml_en_curso", "Entrenamiento ya en curso");
        }
        return ResponseEntity.ok(ApiResponse.ok(new MlDtos.Started("started", null)));
    }

    @GetMapping("/ml/resultado")
    public ResponseEntity<ApiResponse<MlDtos.Resultado>> mlResultado() {
        var s = pythonRunner.getTrainingStatus();
        return ResponseEntity.ok(ApiResponse.ok(new MlDtos.Resultado(s.running(), s.phase(), s.pct(),
                s.msg(), !s.running() && !"idle".equals(s.phase()))));
    }
}
