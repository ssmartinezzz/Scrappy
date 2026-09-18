package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.indices.Deflactor;
import ar.scraper.indices.IndiceService;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalCompra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Precompute step for the buy-signal classification (mirrors
 * {@link MlEnricher}'s shape/pattern). Batch-loads price history for the
 * whole product list in a single DB round-trip (avoiding the N+1 that a
 * per-product {@code getHistorialPrecios(String)} call would cause), resolves
 * a date-range deflator per product via {@link IndiceService} (dates from the
 * product's own historial, index chosen by rubro — D1), then delegates the
 * classification itself to the pure {@link SenalCalculator}.
 *
 * <p>Invoked both from {@code ResultAggregator.agregar} (post-scrape, after
 * {@code upsertProductos} so the latest historial row exists) and from the
 * {@code fromDB} startup/restart path — without the latter, the grid badge
 * would stay empty until the next scrape run.</p>
 */
@Component
public class SenalEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(SenalEnricher.class);

    private final HistorialPort historial;
    private final IndiceService indiceService;

    public SenalEnricher(HistorialPort historial, IndiceService indiceService) {
        this.historial = historial;
        this.indiceService = indiceService;
    }

    public List<Product> enriquecer(List<Product> productos) {
        if (productos == null || productos.isEmpty()) return productos;

        List<String> urls = productos.stream()
                .map(Product::url)
                .filter(u -> u != null && !u.isBlank())
                .toList();

        Map<String, List<HistorialEntry>> historialPorUrl = historial.getHistorialPrecios(urls);

        List<Product> result = new ArrayList<>(productos.size());
        int enriquecidos = 0;
        for (Product p : productos) {
            if (p.url() == null || p.url().isBlank()) {
                result.add(p);
                continue;
            }

            SenalCompra senal = clasificar(historialPorUrl.get(p.url()), p.rubro());

            result.add(withSenal(p, senal));
            if (!"sin_datos".equals(senal.senal())) enriquecidos++;
        }

        LOG.info("[SENAL] {} productos con señal de compra calculada (de {})", enriquecidos, productos.size());
        return result;
    }

    /**
     * The anchor is the same {@code sorted.get(max(0, size-13))} point
     * {@link SenalCalculator} classifies against (D4: its signature/states
     * stay unchanged) — but here it resolves that point's DATE, not a
     * months-ago guess from how many rows happen to exist.
     */
    private SenalCompra clasificar(List<HistorialEntry> puntos, String rubro) {
        if (puntos == null || puntos.isEmpty()) return SenalCompra.EMPTY;

        List<HistorialEntry> ordenado = puntos.stream()
                .sorted(Comparator.comparing(HistorialEntry::fecha))
                .toList();
        LocalDate desde = LocalDate.parse(ordenado.get(Math.max(0, ordenado.size() - 13)).fecha());
        LocalDate hasta = LocalDate.parse(ordenado.get(ordenado.size() - 1).fecha());

        Deflactor deflactor = indiceService.deflactorParaRubro(rubro, desde, hasta);
        return SenalCalculator.compute(puntos, deflactor.factor()).conConfianza(deflactor.confianza());
    }

    private static Product withSenal(Product p, SenalCompra senal) {
        return new Product(
                p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), p.categoria(), p.genero(), p.talles(),
                p.ml(), p.marca(), p.rubro() != null ? p.rubro() : "indumentaria",
                p.gymrat(), p.marcaPremium(), senal, p.finan(), p.cantidadUnidades(),
                p.subCategoria() != null ? p.subCategoria() : "", p.visual()
        );
    }
}
