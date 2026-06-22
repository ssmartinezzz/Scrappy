package ar.scraper.ml;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.Preset;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import ar.scraper.web.InflacionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Precompute step for the financing signal ("¿conviene en cuotas?"). Mirrors
 * {@link SenalEnricher}'s shape/pattern: reads the active financing preset
 * once, reads {@link InflacionService#getInflacionMensual()} once, then
 * delegates the per-product math to the pure {@link FinanciacionCalculator}.
 *
 * <p>Invoked both from {@code ResultAggregator.agregar} (post-scrape) and
 * from the {@code fromDB} startup/restart path — without the latter, the
 * financing badge would stay empty until the next scrape run.</p>
 *
 * <p>Fully independent from {@link SenalEnricher}/{@code SenalCompra}/{@code
 * scoreCompra} — never merged into the same field or badge.</p>
 */
@Component
public class FinanciacionEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(FinanciacionEnricher.class);

    private final DatabaseService db;
    private final InflacionService inflacionService;

    public FinanciacionEnricher(DatabaseService db, InflacionService inflacionService) {
        this.db = db;
        this.inflacionService = inflacionService;
    }

    public List<Product> enriquecer(List<Product> productos) {
        if (productos == null || productos.isEmpty()) return productos;

        Optional<Preset> activo = db.cargarPresetActivo();
        if (activo.isEmpty()) {
            LOG.info("[FINAN] Sin preset activo — señal de financiación queda en sin_preset_activo para {} productos", productos.size());
            return withEmptyFinanciacion(productos);
        }

        Preset preset = activo.get();
        double iMensual = inflacionService.getInflacionMensual() / 100.0;

        List<Product> result = new ArrayList<>(productos.size());
        int enriquecidos = 0;
        for (Product p : productos) {
            SenalFinanciacion finan = FinanciacionCalculator.compute(
                    p.precio(), preset.recargoPct(), preset.cuotas(), iMensual);
            result.add(withFinan(p, finan));
            if (!"sin_datos".equals(finan.senal())) enriquecidos++;
        }

        LOG.info("[FINAN] {} productos con señal de financiación calculada (de {}, preset \"{}\")",
                enriquecidos, productos.size(), preset.label());
        return result;
    }

    private List<Product> withEmptyFinanciacion(List<Product> productos) {
        List<Product> result = new ArrayList<>(productos.size());
        for (Product p : productos) {
            result.add(withFinan(p, SIN_PRESET_ACTIVO));
        }
        return result;
    }

    /**
     * Fallback value when no preset is active — distinct {@code senal} from
     * {@link SenalFinanciacion#EMPTY}'s {@code "sin_datos"} so the UI can
     * tell apart "no active preset configured" from "calculation guard hit".
     */
    private static final SenalFinanciacion SIN_PRESET_ACTIVO =
            new SenalFinanciacion("sin_preset_activo", 0, 0, 0, 0, 0);

    private static Product withFinan(Product p, SenalFinanciacion finan) {
        return new Product(
                p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), p.categoria(), p.genero(), p.talles(),
                p.ml(), p.marca(), p.rubro() != null ? p.rubro() : "indumentaria",
                p.gymrat(), p.marcaPremium(), p.senal(), finan, p.cantidadUnidades()
        );
    }
}
