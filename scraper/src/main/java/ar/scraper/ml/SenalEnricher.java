package ar.scraper.ml;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.HistorialEntry;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalCompra;
import ar.scraper.web.InflacionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Precompute step for the buy-signal classification (mirrors
 * {@link MlEnricher}'s shape/pattern). Batch-loads price history for the
 * whole product list in a single DB round-trip (avoiding the N+1 that a
 * per-product {@code getHistorialPrecios(String)} call would cause), then
 * delegates the actual classification to the pure {@link SenalCalculator}.
 *
 * <p>Invoked both from {@code ResultAggregator.agregar} (post-scrape, after
 * {@code upsertProductos} so the latest historial row exists) and from the
 * {@code fromDB} startup/restart path — without the latter, the grid badge
 * would stay empty until the next scrape run.</p>
 */
@Component
public class SenalEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(SenalEnricher.class);

    private final DatabaseService db;
    private final InflacionService inflacionService;

    public SenalEnricher(DatabaseService db, InflacionService inflacionService) {
        this.db = db;
        this.inflacionService = inflacionService;
    }

    public List<Product> enriquecer(List<Product> productos) {
        if (productos == null || productos.isEmpty()) return productos;

        List<String> urls = productos.stream()
                .map(Product::url)
                .filter(u -> u != null && !u.isBlank())
                .toList();

        Map<String, List<HistorialEntry>> historialPorUrl = db.getHistorialPrecios(urls);

        List<Product> result = new ArrayList<>(productos.size());
        int enriquecidos = 0;
        for (Product p : productos) {
            if (p.url() == null || p.url().isBlank()) {
                result.add(p);
                continue;
            }

            List<HistorialEntry> historial = historialPorUrl.get(p.url());
            int mesesAtras = historial != null ? Math.max(1, historial.size() / 4) : 1;
            double factor = inflacionService.factorInflacion(mesesAtras);
            SenalCompra senal = SenalCalculator.compute(historial, factor);

            result.add(withSenal(p, senal));
            if (!"sin_datos".equals(senal.senal())) enriquecidos++;
        }

        LOG.info("[SENAL] {} productos con señal de compra calculada (de {})", enriquecidos, productos.size());
        return result;
    }

    private static Product withSenal(Product p, SenalCompra senal) {
        return new Product(
                p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), p.categoria(), p.genero(), p.talles(),
                p.ml(), p.marca(), p.rubro() != null ? p.rubro() : "indumentaria",
                p.gymrat(), p.marcaPremium(), senal
        );
    }
}
