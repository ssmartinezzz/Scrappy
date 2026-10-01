package ar.scraper.web;

import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.web.dto.MlDtos;
import org.springframework.stereotype.Component;

@Component
public class MlEstadoView {

    private final ScraperService service;
    private final ProductPort productos;
    private final PythonRunner pythonRunner;

    public MlEstadoView(ScraperService service, ProductPort productos, PythonRunner pythonRunner) {
        this.service = service;
        this.productos = productos;
        this.pythonRunner = pythonRunner;
    }

    public MlDtos.Estado snapshot() {
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
        return estado;
    }
}
