package ar.scraper.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

/**
 * ML pipeline operations: the trends payload, price history, re-running the
 * pipeline over the current catalog, renormalisation, and the training /
 * visual-index endpoints.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3), where these
 * endpoints were spread across three separate regions of the file. This class
 * holds no request mappings: {@link ApiController} keeps them and delegates
 * here, so the routes and every existing caller are untouched.</p>
 *
 * <p>{@code /api/historial} is grouped here rather than with the catalog because
 * that is how the project's own API reference groups it — it feeds the same
 * analysis surfaces as {@code /api/tendencias}.</p>
 */
class MlEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(MlEndpoints.class);

    private final ScraperService service;
    private final ar.scraper.db.DatabaseService db;
    private final ar.scraper.aggregator.ResultAggregator aggregator;
    private final ar.scraper.ml.PythonRunner pythonRunner;

    MlEndpoints(ScraperService service,
                ar.scraper.db.DatabaseService db,
                ar.scraper.aggregator.ResultAggregator aggregator,
                ar.scraper.ml.PythonRunner pythonRunner) {
        this.service = service;
        this.db = db;
        this.aggregator = aggregator;
        this.pythonRunner = pythonRunner;
    }

    // ---------------------------------------------------------------
    // Tendencias ML
    // ---------------------------------------------------------------
    ResponseEntity<com.fasterxml.jackson.databind.JsonNode> tendencias() {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        var ml = aggregator.getLastMlOutput();

        // NOT_RUN: pipeline ML falló (null) → 503 con marcador, para que la UI distinga
        // "falló" de "sin datos todavía"
        if (ml == null || ml.isNull()) {
            var err = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            err.put("error", "ml_failed");
            return ResponseEntity.status(503).body(err);
        }

        // EMPTY: corrió pero scores/tendencias no son usables → 204 "sin datos"
        var scoresNode = ml.path("scores");
        var tendNode   = ml.path("tendencias");
        boolean valido = scoresNode.isObject() && !scoresNode.isEmpty() && tendNode.isObject();
        if (!valido) return ResponseEntity.noContent().build();

        // VALID: 200 con payload (deepCopy de tendencias + enriquecido)
        com.fasterxml.jackson.databind.node.ObjectNode result =
                (com.fasterxml.jackson.databind.node.ObjectNode) tendNode.deepCopy();

        // Enriquecer con categoriaStats desde DB — V16 (design DD6): 12 columnas
        // tipadas, ya no un payload JSON a reparsear. cv se redondea a 1 decimal,
        // los otros 11 campos son enteros por construcción (columnas INTEGER/BIGINT).
        var catStats = db.cargarCategoriaStats();
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
        return ResponseEntity.ok(result);
    }

    // ---------------------------------------------------------------
    // Historial de precios
    // ---------------------------------------------------------------
    ResponseEntity<Object> historial(String url) {
        var hist = db.cargarHistorial(url);
        if (hist.isEmpty()) return ResponseEntity.noContent().build();
        // Enriquecer con stats básicas del historial
        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var arr  = node.putArray("puntos");
        hist.forEach(h -> {
            var p = arr.addObject();
            p.put("fecha",  (String) h.get("fecha"));
            p.put("precio", ((Number) h.get("precio")).doubleValue());
        });
        if (hist.size() >= 2) {
            double min = hist.stream().mapToDouble(h -> ((Number) h.get("precio")).doubleValue()).min().orElse(0);
            double max = hist.stream().mapToDouble(h -> ((Number) h.get("precio")).doubleValue()).max().orElse(0);
            double avg = hist.stream().mapToDouble(h -> ((Number) h.get("precio")).doubleValue()).average().orElse(0);
            double first = ((Number) hist.get(0).get("precio")).doubleValue();
            double last  = ((Number) hist.get(hist.size()-1).get("precio")).doubleValue();
            node.put("min", min).put("max", max).put("avg", avg);
            node.put("deltaPct", first > 0 ? Math.round((last - first) / first * 1000.0) / 10.0 : 0);
        }
        return ResponseEntity.ok(node);
    }

    ResponseEntity<Object> mlAplicar() {
        var r = service.getLastResult();
        if (r == null) return ResponseEntity.badRequest()
            .body(java.util.Map.of("error", "No hay datos. Ejecutá un scraping primero."));

        // Re-ejecutar pipeline ML sobre datos actuales en background
        Thread.ofVirtual().start(() -> {
            try {
                String prodJson = aggregator.getMlEnricher().serializarProductos(r.productos());
                var mlOut = aggregator.getPythonRunner().ejecutar(prodJson);
                if (mlOut != null) {
                    var enriquecidos = aggregator.getMlEnricher().enriquecer(r.productos(), mlOut, db);
                    // Persistir categorías refinadas
                    java.util.Map<String,String> catOrig = new java.util.HashMap<>();
                    r.productos().forEach(p -> { if(p.url()!=null) catOrig.put(p.url(), p.categoria()!=null?p.categoria():""); });
                    enriquecidos.forEach(p -> {
                        String orig = catOrig.get(p.url());
                        if (orig != null && !p.categoria().equals(orig))
                            try { db.actualizarCategoria(p.url(), p.categoria()); } catch(Exception ignored){}
                    });
                    aggregator.setLastMlOutput(mlOut);
                    db.guardarMlOutput(mlOut);
                    LOG.info("[ML/aplicar] Pipeline re-aplicado: {} productos refinados", enriquecidos.size());
                }
            } catch (Exception e) {
                LOG.warn("[ML/aplicar] Error: {}", e.getMessage());
            }
        });
        return ResponseEntity.ok(java.util.Map.of(
            "status", "started",
            "mensaje", "Pipeline ML re-ejecutándose en background. Refrescá la página en 30 segundos."
        ));
    }

    ResponseEntity<Object> mlRenormalizar() {
        var resultado = aggregator.renormalizarCatalogo();
        return ResponseEntity.ok(resultado);
    }

    // ─── ML Training ─────────────────────────────────────────────────────────────

    ResponseEntity<Object> mlEstado() {
        java.io.File modelsDir = new java.io.File("_models");
        java.io.File textModel = new java.io.File(modelsDir, "text_classifier.pkl");
        java.io.File imgModel  = new java.io.File(modelsDir, "image_model.pt");
        java.io.File textMeta  = new java.io.File(modelsDir, "text_meta.json");

        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var root   = MAPPER.createObjectNode();
        root.put("hasTextModel",  textModel.exists());
        root.put("hasImageModel", imgModel.exists());
        if (textMeta.exists()) {
            try {
                var meta = MAPPER.readTree(textMeta);
                root.set("textMeta", meta);
            } catch (Exception ignored) {}
        }
        var ts = pythonRunner.getTrainingStatus();
        ObjectNode training = root.putObject("training");
        training.put("running",   ts.running());
        training.put("phase",     ts.phase());
        training.put("pct",       ts.pct());
        training.put("msg",       ts.msg());
        training.put("startedAt", ts.startedAt() != null ? ts.startedAt() : "");

        // T6.3/T6.4 (fashion-image-classification PR6): visual-index coverage —
        // additive fields, backward-compatible with MlStatusPanel's existing
        // hasTextModel/hasImageModel/training.* consumption.
        long embeddingsCount = db.contarEmbeddings();
        var lastResult = service.getLastResult();
        int totalProductos = lastResult != null ? lastResult.productos().size() : 0;
        double coveragePct = totalProductos > 0
                ? Math.round((double) embeddingsCount / totalProductos * 1000.0) / 10.0
                : 0.0;
        root.put("embeddingsCount", embeddingsCount);
        root.put("totalProductos",  totalProductos);
        root.put("coveragePct",     coveragePct);

        return ResponseEntity.ok(root);
    }

    ResponseEntity<Object> mlEntrenar(boolean images, int epochs) {

        if (pythonRunner.isTrainingRunning())
            return ResponseEntity.badRequest()
                .body(java.util.Map.of("error", "Entrenamiento ya en curso"));

        // T6.2 (fashion-image-classification PR6): "Construir índice visual"
        // drives PythonRunner.construirIndiceVisualEnBackground (T5.4's
        // sequencing entrypoint), NOT the standalone entrenarEnBackground.
        // READ-005: entrenarEnBackground is NOT retired — it remains live as
        // the post-scrape auto-training path (ResultAggregator.agregar); it's
        // just no longer what this manual endpoint invokes. Do not delete it
        // in a future cleanup. This endpoint runs text re-training FIRST,
        // then the embeddings backfill, on ONE background thread, without
        // blocking this HTTP response.
        // decouple-services-postgres Batch 3 (task 3.6): no filesystem DB
        // path to resolve anymore — the subprocess reads DATABASE_URL from
        // its own env (see PythonRunner.aplicarEnvBaseDatosYModelos).
        // READ-004: named args instead of naked booleans. forceRetrainTexto
        // stays true (unchanged observable behavior: every manual button
        // click forces a fresh text re-train, same as before);
        // forceBackfillEmbeddings is also true — an explicit "Construir
        // índice visual" click is a deliberate full-rebuild action, not a
        // passive cache-first pass.
        boolean forceRetrainTexto = true;
        boolean forceBackfillEmbeddings = true;
        boolean iniciado = pythonRunner.construirIndiceVisualEnBackground(
                forceRetrainTexto, images, epochs, forceBackfillEmbeddings);
        // RESI-002 ≡ RELY-001: two near-simultaneous POSTs can both pass the
        // isTrainingRunning() pre-check above; the atomic CAS inside the
        // runner picks exactly one winner. The loser's request was NOT
        // started — answer 409 Conflict instead of a false "started".
        if (!iniciado)
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .body(java.util.Map.of("error", "Entrenamiento ya en curso"));
        return ResponseEntity.ok(java.util.Map.of("status", "started"));
    }

    ResponseEntity<Object> mlResultado() {
        var s = pythonRunner.getTrainingStatus();
        return ResponseEntity.ok(java.util.Map.of(
            "running", s.running(),
            "phase",   s.phase(),
            "pct",     s.pct(),
            "msg",     s.msg(),
            "done",    !s.running() && !"idle".equals(s.phase())
        ));
    }
}
