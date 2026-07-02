package ar.scraper.aggregator;

import ar.scraper.db.DatabaseService;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ResultAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(ResultAggregator.class);

    private final NormalizerService    normalizer;
    private final PythonRunner         pythonRunner;
    private final MlEnricher           mlEnricher;
    private final SenalEnricher        senalEnricher;
    private final FinanciacionEnricher financiacionEnricher;
    private final DatabaseService      db;

    // Estado del último run — leído por ScraperService sin inyección circular
    private volatile JsonNode lastMlOutput     = null;
    private volatile int      lastCatRefinadas = 0;

    public ResultAggregator(NormalizerService    normalizer,
                            PythonRunner         pythonRunner,
                            MlEnricher           mlEnricher,
                            SenalEnricher        senalEnricher,
                            FinanciacionEnricher financiacionEnricher,
                            DatabaseService      db) {
        this.normalizer          = normalizer;
        this.pythonRunner        = pythonRunner;
        this.mlEnricher          = mlEnricher;
        this.senalEnricher       = senalEnricher;
        this.financiacionEnricher = financiacionEnricher;
        this.db                  = db;
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    public JsonNode             getLastMlOutput()    { return lastMlOutput; }
    public void                 setLastMlOutput(JsonNode n) { lastMlOutput = n; }
    public void                 clearMlOutput()      { this.lastMlOutput = null; }
    public int                  getLastCatRefinadas(){ return lastCatRefinadas; }
    public MlEnricher           getMlEnricher()      { return mlEnricher; }
    public PythonRunner         getPythonRunner()    { return pythonRunner; }
    public FinanciacionEnricher financiacionEnricher() { return financiacionEnricher; }

    // ─── Records ─────────────────────────────────────────────────────────────

    public record Facets(
            Map<String, Long> talles,
            Map<String, Long> generos,
            Map<String, Long> categorias,
            Map<String, Long> marcas,
            Map<String, Long> badges,
            Map<String, Long> subCategorias
    ) {}

    /** Per-site extraction quality counters produced by {@link #agregar}. */
    public record ExtractionStats(String sitio, int total, int valid, int misses) {}

    public record AggregatedResult(
            List<Product>                productos,
            Map<String, Integer>         conteoPorSitio,
            Map<String, String>          erroresPorSitio,
            Facets                       facets,
            double                       minPrecio,
            double                       maxPrecio,
            Map<String, ExtractionStats> statsPorSitio
    ) {
        /** Legacy 6-arg constructor — defaults statsPorSitio to empty map for backward compatibility. */
        public AggregatedResult(List<Product> productos, Map<String, Integer> conteoPorSitio,
                                Map<String, String> erroresPorSitio, Facets facets,
                                double minPrecio, double maxPrecio) {
            this(productos, conteoPorSitio, erroresPorSitio, facets, minPrecio, maxPrecio, Map.of());
        }
    }

    // ─── Aggregation ─────────────────────────────────────────────────────────

    /** A product is valid iff nombre is non-blank, precio > 0, and url is non-blank. */
    private static boolean isValid(Product p) {
        return p.nombre() != null && !p.nombre().isBlank()
                && p.precio() > 0
                && p.url() != null && !p.url().isBlank();
    }

    /** Output of {@link #validarYContar}: per-site raw counts, errors, extraction stats, and the flattened list of valid products. */
    private record ValidationResult(
            Map<String, Integer>         conteo,
            Map<String, String>          errores,
            Map<String, ExtractionStats> stats,
            List<Product>                todos
    ) {}

    /** Output of {@link #ejecutarPipelineMl}: the pre-ML normalized list, the post-ML enriched list, and the raw ML output node. */
    private record MlPipelineResult(
            List<Product> normalizados,
            List<Product> enriquecidos,
            JsonNode      mlOut
    ) {}

    public AggregatedResult agregar(List<ScrapeResult> resultados, boolean forceRetrain) {
        ValidationResult validacion = validarYContar(resultados);
        List<Product>    sorted     = deduplicarYOrdenar(validacion.todos());

        MlPipelineResult pipeline = ejecutarPipelineMl(sorted);

        persistirCategoriasRefinadas(pipeline.normalizados(), pipeline.enriquecidos());

        db.upsertProductos(pipeline.enriquecidos());
        db.guardarMlOutput(pipeline.mlOut());
        if (pipeline.mlOut() != null && !pipeline.mlOut().path("categoriaStats").isMissingNode())
            db.guardarCategoriaStats(pipeline.mlOut().path("categoriaStats"));

        List<Product> conFinanciacion = enriquecerSenalYFinanciacion(pipeline.enriquecidos());

        // Facets, stats
        Facets facets = calcularFacets(conFinanciacion);
        double minP   = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(0).precio();
        double maxP   = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(conFinanciacion.size()-1).precio();

        LOG.info("Agregacion: {} brutos -> {} unicos (normalizado+ML)", validacion.todos().size(), conFinanciacion.size());

        // Entrenamiento background post-scraping
        String dbPath = System.getProperty("user.dir") + java.io.File.separator + "scraper.db";
        LOG.info("[AGG] Lanzando entrenamiento del modelo en background...");
        pythonRunner.entrenarEnBackground(dbPath, forceRetrain);

        return new AggregatedResult(conFinanciacion, validacion.conteo(), validacion.errores(), facets, minP, maxP, validacion.stats());
    }

    /** Filters valid products per site, tallies raw counts/errors/extraction stats, and flattens the valid subset. */
    private ValidationResult validarYContar(List<ScrapeResult> resultados) {
        Map<String, Integer>         conteo  = new LinkedHashMap<>();
        Map<String, String>          errores = new LinkedHashMap<>();
        Map<String, ExtractionStats> stats   = new LinkedHashMap<>();
        List<Product>                todos   = new ArrayList<>();

        for (ScrapeResult r : resultados) {
            List<Product> valid  = r.productos().stream().filter(ResultAggregator::isValid).toList();
            int           misses = r.productos().size() - valid.size();
            conteo.put(r.sitio(), r.productos().size());   // RAW count unchanged
            stats.put(r.sitio(), new ExtractionStats(r.sitio(), r.productos().size(), valid.size(), misses));
            if (misses > 0)
                LOG.warn("[METRICS] {}: {}/{} válidos ({} misses)", r.sitio(), valid.size(), r.productos().size(), misses);
            if (!r.exitoso()) errores.put(r.sitio(), r.error());
            todos.addAll(valid);   // only valid products flow downstream
        }

        return new ValidationResult(conteo, errores, stats, todos);
    }

    /** Dedups by sitio + normalized nombre (first occurrence wins), then sorts ascending by precio. */
    private List<Product> deduplicarYOrdenar(List<Product> todos) {
        Map<String, Product> deduped = new LinkedHashMap<>();
        for (Product p : todos) {
            String key = p.sitio() + "||" + p.nombre().toLowerCase().trim();
            deduped.putIfAbsent(key, p);
        }
        return deduped.values().stream()
                .sorted(Comparator.comparingDouble(Product::precio))
                .collect(Collectors.toList());
    }

    /** Runs Java-side normalization, then the Python ML pipeline (scoring + category refinement). */
    private MlPipelineResult ejecutarPipelineMl(List<Product> sorted) {
        List<Product> normalizados = normalizer.normalizar(sorted);

        String prodJson = mlEnricher.serializarProductos(normalizados);
        LOG.info("[AGG] Ejecutando pipeline ML (esto puede tomar hasta 2 minutos)...");
        JsonNode mlOut  = pythonRunner.ejecutar(prodJson);
        LOG.info("[AGG] Pipeline ML completado.");
        lastMlOutput    = mlOut;

        List<Product> enriquecidos = mlEnricher.enriquecer(normalizados, mlOut, db);

        return new MlPipelineResult(normalizados, enriquecidos, mlOut);
    }

    /**
     * Snapshots pre-ML categoria from {@code normalizados} and diffs it against
     * post-ML categoria in {@code enriquecidos} by url; persists only the deltas
     * and updates {@link #lastCatRefinadas}. {@code Product} is immutable, so
     * {@code normalizados} still holds pre-ML categorias after ML enrichment
     * returns a new list — ordering is load-bearing (ADR-3).
     */
    private void persistirCategoriasRefinadas(List<Product> normalizados, List<Product> enriquecidos) {
        Map<String, String> catOriginal = new HashMap<>();
        for (Product p : normalizados)
            if (p.url() != null && !p.url().isBlank())
                catOriginal.put(p.url(), p.categoria() != null ? p.categoria() : "");

        int catRefinadas = 0;
        for (Product p : enriquecidos) {
            String pid = p.url();
            if (pid == null || pid.isBlank()) continue;
            String antes = catOriginal.get(pid);
            String ahora = p.categoria() != null ? p.categoria() : "";
            if (antes != null && !ahora.equals(antes)) {
                try { db.actualizarCategoria(pid, ahora); catRefinadas++; }
                catch (Exception ignored) {}
            }
        }
        lastCatRefinadas = catRefinadas;
        LOG.info("[ML] Categorías persistidas en DB: {}", catRefinadas);
    }

    /** Precomputes buy-signal, then financing-signal (independent of buy-signal). */
    private List<Product> enriquecerSenalYFinanciacion(List<Product> enriquecidos) {
        // Precompute señal de compra (post-upsert: requiere que el historial de
        // precios de este run ya esté persistido en precio_historico)
        List<Product> conSenal = senalEnricher.enriquecer(enriquecidos);
        // Precompute señal de financiación (independiente de señal de compra)
        return financiacionEnricher.enriquecer(conSenal);
    }

    public AggregatedResult agregar(List<ScrapeResult> resultados) {
        return agregar(resultados, false);
    }

    // ─── Facets ──────────────────────────────────────────────────────────────

    /**
     * Delegates to {@link FacetCalculator} (Work Unit 9 extraction). Kept as a
     * thin public static forward — not a "permanent test-only facade" in the
     * ADR-2 sense, since it preserves a genuine external contract (~10 call
     * sites across {@code ar.scraper.web} tests build {@link AggregatedResult}
     * fixtures against this exact signature).
     */
    public static Facets calcularFacets(List<Product> productos) {
        return FacetCalculator.calcular(productos);
    }

    // Normalización rápida sin ML
    public List<Product> normalizarSolo(List<Product> productos) {
        return normalizer.normalizar(productos);
    }

    /**
     * Re-aplica las reglas actuales de {@link NormalizerService} sobre el
     * catálogo YA persistido en la DB, sin re-scrapear (no toca internet, no
     * usa Playwright). Cierra el gap entre "arreglamos una regla de
     * clasificación" y "el catálogo guardado sigue con valores viejos hasta
     * el próximo scrape". Pensado para correr automáticamente antes de cada
     * entrenamiento de imagen con GPU, así el clasificador no aprende de
     * etiquetas stale.
     */
    public Map<String, Integer> renormalizarCatalogo() {
        List<Product> actuales      = db.cargarProductos();
        List<Product> renormalizados = normalizer.normalizar(actuales);

        int totalRevisados   = 0;
        int categoriaCambiada = 0;
        int marcaCambiada     = 0;

        int n = Math.min(actuales.size(), renormalizados.size());
        for (int i = 0; i < n; i++) {
            Product antes  = actuales.get(i);
            Product ahora  = renormalizados.get(i);
            if (antes.url() == null || !antes.url().equals(ahora.url())) continue; // safety: mismo índice, misma URL

            totalRevisados++;
            String catAntes = antes.categoria() != null ? antes.categoria() : "";
            String catAhora = ahora.categoria() != null ? ahora.categoria() : "";
            String marcaAntes = antes.marca() != null ? antes.marca() : "";
            String marcaAhora = ahora.marca() != null ? ahora.marca() : "";
            String genAntes = antes.genero() != null ? antes.genero() : "";
            String genAhora = ahora.genero() != null ? ahora.genero() : "";
            List<String> tallesAntes = antes.talles() != null ? antes.talles() : List.of();
            List<String> tallesAhora = ahora.talles() != null ? ahora.talles() : List.of();

            boolean catCambio       = !catAntes.equals(catAhora);
            boolean marcaCambio     = !marcaAntes.equals(marcaAhora);
            boolean genCambio       = !genAntes.equals(genAhora);
            boolean tallesCambio    = !tallesAntes.equals(tallesAhora);
            String subCatAntes      = antes.subCategoria() != null ? antes.subCategoria() : "";
            String subCatAhora      = ahora.subCategoria() != null ? ahora.subCategoria() : "";
            boolean subCatCambio    = !subCatAntes.equals(subCatAhora);

            if (catCambio)   categoriaCambiada++;
            if (marcaCambio) marcaCambiada++;

            if (catCambio || marcaCambio || genCambio || tallesCambio || subCatCambio) {
                try {
                    db.actualizarNormalizacion(ahora.url(), catAhora, marcaAhora, genAhora, tallesAhora, subCatAhora);
                } catch (Exception ignored) {}
            }
        }

        LOG.info("[RENORM] Catálogo re-normalizado: {} revisados, {} con categoría cambiada, {} con marca cambiada",
                totalRevisados, categoriaCambiada, marcaCambiada);

        return Map.of(
                "totalRevisados", totalRevisados,
                "categoriaCambiada", categoriaCambiada,
                "marcaCambiada", marcaCambiada
        );
    }

    /**
     * Reconstruye un {@link AggregatedResult} desde productos cargados de la DB
     * (startup/restart o tras un rescrape parcial de favoritos). A diferencia de
     * {@link #agregar}, este camino NO corre el pipeline ML — pero SÍ debe correr
     * {@link SenalEnricher} y {@link FinanciacionEnricher}, porque de lo
     * contrario sus badges quedarían vacíos en el grid hasta el próximo
     * scrape completo.
     */
    public AggregatedResult fromDB(List<Product> productos) {
        List<Product> conSenal = senalEnricher.enriquecer(productos);
        List<Product> conFinanciacion = financiacionEnricher.enriquecer(conSenal);

        Map<String, Integer> conteo = new LinkedHashMap<>();
        conFinanciacion.forEach(p -> conteo.merge(p.sitio(), 1, Integer::sum));
        Facets facets = calcularFacets(conFinanciacion);
        double minP = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(0).precio();
        double maxP = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(conFinanciacion.size()-1).precio();
        return new AggregatedResult(conFinanciacion, conteo, Map.of(), facets, minP, maxP, Map.of());
    }
}
