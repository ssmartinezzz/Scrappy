package ar.scraper.aggregator;

import ar.scraper.catalog.ClasificacionBloqueada;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.MlOutputPort;
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
import org.apache.commons.lang3.StringUtils;

@Component
public class ResultAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(ResultAggregator.class);

    private final NormalizerService    normalizer;
    private final PythonRunner         pythonRunner;
    private final MlEnricher           mlEnricher;
    private final SenalEnricher        senalEnricher;
    private final FinanciacionEnricher financiacionEnricher;
    // Declared dual dependency: guardarMlOutput/ guardarCategoriaStats below belong to repositories
    // out of this slice's scope.
    // upsertProductos/cargarClasificacionBloqueada/actualizarCategoria/cargarProductos/
    // actualizarNormalizacion/estaBloqueado go through ProductPort instead.
    private final MlOutputPort         mlOutput;
    private final CategoriaStatsPort   categoriaStats;
    private final ProductPort          productos;

    // Estado del último run — leído por ScraperService sin inyección circular
    private volatile JsonNode lastMlOutput     = null;
    private volatile int      lastCatRefinadas = 0;

    public ResultAggregator(NormalizerService    normalizer,
                            PythonRunner         pythonRunner,
                            MlEnricher           mlEnricher,
                            SenalEnricher        senalEnricher,
                            FinanciacionEnricher financiacionEnricher,
                            MlOutputPort         mlOutput,
                            CategoriaStatsPort   categoriaStats,
                            ProductPort          productos) {
        this.normalizer          = normalizer;
        this.pythonRunner        = pythonRunner;
        this.mlEnricher          = mlEnricher;
        this.senalEnricher       = senalEnricher;
        this.financiacionEnricher = financiacionEnricher;
        this.mlOutput            = mlOutput;
        this.categoriaStats      = categoriaStats;
        this.productos           = productos;
    }

    public JsonNode             getLastMlOutput()    { return lastMlOutput; }
    public void                 setLastMlOutput(JsonNode n) { lastMlOutput = n; }
    public void                 clearMlOutput()      { this.lastMlOutput = null; }
    public int                  getLastCatRefinadas(){ return lastCatRefinadas; }
    public MlEnricher           getMlEnricher()      { return mlEnricher; }
    public PythonRunner         getPythonRunner()    { return pythonRunner; }
    public FinanciacionEnricher financiacionEnricher() { return financiacionEnricher; }

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
        public AggregatedResult(List<Product> productos, Map<String, Integer> conteoPorSitio,
                                Map<String, String> erroresPorSitio, Facets facets,
                                double minPrecio, double maxPrecio) {
            this(productos, conteoPorSitio, erroresPorSitio, facets, minPrecio, maxPrecio, Map.of());
        }
    }

    private static boolean isValid(Product p) {
        return StringUtils.isNotBlank(p.nombre())
                && p.precio() > 0
                && StringUtils.isNotBlank(p.url());
    }

    private record ValidationResult(
            Map<String, Integer>         conteo,
            Map<String, String>          errores,
            Map<String, ExtractionStats> stats,
            List<Product>                todos
    ) {}

    /**
     * Output of {@link #ejecutarPipelineMl}: the pre-ML normalized list, the post-ML enriched list,
     * and the raw ML output node.
     */
    private record MlPipelineResult(
            List<Product> normalizados,
            List<Product> enriquecidos,
            JsonNode      mlOut
    ) {}

    public AggregatedResult agregar(List<ScrapeResult> resultados, boolean forceRetrain) {
        return agregar(resultados, forceRetrain, null);
    }

    /**
     * Igual, informando el {@code started_at} de la corrida para que el soft-delete se acote a lo
     * que ella tocó y no a este batch.
     */
    public AggregatedResult agregar(List<ScrapeResult> resultados, boolean forceRetrain,
                                    ar.scraper.scrape.CorridaEnCurso corrida) {
        ValidationResult validacion = validarYContar(resultados);
        List<Product>    sorted     = deduplicarYOrdenar(validacion.todos());

        MlPipelineResult pipeline = ejecutarPipelineMl(sorted);

        persistirCategoriasRefinadas(pipeline.normalizados(), pipeline.enriquecidos());

        productos.upsertProductos(pipeline.enriquecidos(), corrida);
        mlOutput.guardarMlOutput(pipeline.mlOut());
        if (pipeline.mlOut() != null && !pipeline.mlOut().path("categoriaStats").isMissingNode())
            categoriaStats.guardarCategoriaStats(pipeline.mlOut().path("categoriaStats"));

        List<Product> conFinanciacion = enriquecerSenalYFinanciacion(pipeline.enriquecidos());

        Facets facets = calcularFacets(conFinanciacion);
        double minP   = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(0).precio();
        double maxP   = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(conFinanciacion.size()-1).precio();

        LOG.info("Agregacion: {} brutos -> {} unicos (normalizado+ML)", validacion.todos().size(), conFinanciacion.size());

        LOG.info("[AGG] Lanzando entrenamiento del modelo en background...");
        pythonRunner.entrenarEnBackground(forceRetrain);

        return new AggregatedResult(conFinanciacion, validacion.conteo(), validacion.errores(), facets, minP, maxP, validacion.stats());
    }

    private ValidationResult validarYContar(List<ScrapeResult> resultados) {
        Map<String, Integer>         conteo  = new LinkedHashMap<>();
        Map<String, String>          errores = new LinkedHashMap<>();
        Map<String, ExtractionStats> stats   = new LinkedHashMap<>();
        List<Product>                todos   = new ArrayList<>();

        for (ScrapeResult r : resultados) {
            List<Product> valid  = r.productos().stream().filter(ResultAggregator::isValid).toList();
            int           misses = r.productos().size() - valid.size();
            conteo.put(r.sitio(), r.productos().size());
            stats.put(r.sitio(), new ExtractionStats(r.sitio(), r.productos().size(), valid.size(), misses));
            if (misses > 0)
                LOG.warn("[METRICS] {}: {}/{} válidos ({} misses)", r.sitio(), valid.size(), r.productos().size(), misses);
            if (!r.exitoso()) errores.put(r.sitio(), r.error());
            todos.addAll(valid);   // only valid products flow downstream
        }

        return new ValidationResult(conteo, errores, stats, todos);
    }

    /**
     * Dedups by sitio + normalized nombre (first occurrence wins), then sorts ascending by precio.
     */
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

    /**
     * (2) after stage-1b, because the visual classifier can override {@code categoria} on its own.
     */
    private MlPipelineResult ejecutarPipelineMl(List<Product> sorted) {
        Map<String, ClasificacionBloqueada> bloqueos = productos.cargarClasificacionBloqueada();

        List<Product> normalizados = aplicarBloqueos(normalizer.normalizar(sorted), bloqueos);

        String prodJson = mlEnricher.serializarProductos(normalizados);
        LOG.info("[AGG] Ejecutando pipeline ML (esto puede tomar hasta 2 minutos)...");
        JsonNode mlOut  = pythonRunner.ejecutar(prodJson);
        LOG.info("[AGG] Pipeline ML completado.");
        lastMlOutput    = mlOut;

        List<Product> enriquecidos = aplicarBloqueos(mlEnricher.enriquecer(normalizados, mlOut), bloqueos);

        return new MlPipelineResult(normalizados, enriquecidos, mlOut);
    }

    /**
     * A {@code null} or empty lock map is a no-op (returns {@code productos} unchanged) — this
     * keeps every {@code agregar()} call site backward compatible with a mocked
     * {@code DatabaseService} that never stubs {@code cargarClasificacionBloqueada()}.
     */
    static List<Product> aplicarBloqueos(List<Product> productos, Map<String, ClasificacionBloqueada> bloqueos) {
        if (bloqueos == null || bloqueos.isEmpty() || productos == null || productos.isEmpty()) {
            return productos;
        }
        List<Product> result = new ArrayList<>(productos.size());
        for (Product p : productos) {
            ClasificacionBloqueada c = p.url() != null ? bloqueos.get(p.url()) : null;
            if (c == null) {
                result.add(p);
                continue;
            }
            result.add(Product.builder()
                    .sitio(p.sitio())
                    .nombre(p.nombre())
                    .precio(p.precio())
                    .precioOriginal(p.precioOriginal())
                    .url(p.url())
                    .imagenUrl(p.imagenUrl())
                    .categoria(c.categoria())
                    .genero(c.genero())
                    .talles(p.talles())
                    .ml(p.ml())
                    .marca(c.marca())
                    .rubro(c.rubro())
                    .gymrat(p.gymrat())
                    .marcaPremium(p.marcaPremium())
                    .senal(p.senal())
                    .finan(p.finan())
                    .cantidadUnidades(p.cantidadUnidades())
                    .subCategoria(c.subCategoria())
                    .visual(p.visual())
                    .build());
        }
        return result;
    }

    /**
     * Snapshots pre-ML categoria from {@code normalizados} and diffs it against post-ML categoria
     * in {@code enriquecidos} by url; persists only the deltas and updates
     * {@link #lastCatRefinadas}.
     */
    private void persistirCategoriasRefinadas(List<Product> normalizados, List<Product> enriquecidos) {
        Map<String, String> catOriginal = new HashMap<>();
        for (Product p : normalizados)
            if (StringUtils.isNotBlank(p.url()))
                catOriginal.put(p.url(), p.categoria() != null ? p.categoria() : "");

        int catRefinadas = 0;
        for (Product p : enriquecidos) {
            String pid = p.url();
            if (StringUtils.isBlank(pid)) continue;
            String antes = catOriginal.get(pid);
            String ahora = p.categoria() != null ? p.categoria() : "";
            if (antes != null && !ahora.equals(antes)) {
                try { productos.actualizarCategoria(pid, ahora); catRefinadas++; }
                catch (Exception ignored) {}
            }
        }
        lastCatRefinadas = catRefinadas;
        LOG.info("[ML] Categorías persistidas en DB: {}", catRefinadas);
    }

    private List<Product> enriquecerSenalYFinanciacion(List<Product> enriquecidos) {
        // Precompute señal de compra (post-upsert: requiere que el historial de precios de este run
        // ya esté persistido en precio_historico)
        List<Product> conSenal = senalEnricher.enriquecer(enriquecidos);
        return financiacionEnricher.enriquecer(conSenal);
    }

    public AggregatedResult agregar(List<ScrapeResult> resultados) {
        return agregar(resultados, false);
    }

    /**
     * Kept as a thin public static forward — not a "permanent test-only facade" in the ADR-2 sense,
     * since it preserves a genuine external contract (~10 call sites across {@code ar.scraper.web}
     * tests build {@link AggregatedResult} fixtures against this exact signature).
     */
    public static Facets calcularFacets(List<Product> productos) {
        return FacetCalculator.calcular(productos);
    }

    // Normalización rápida sin ML — usada por la actualización progresiva por sitio
    public List<Product> normalizarSolo(List<Product> productos) {
        return normalizer.normalizar(productos);
    }

    /**
     * Re-aplica las reglas actuales de {@link NormalizerService} sobre el catálogo YA persistido en
     * la DB, sin re-scrapear (no toca internet, no usa Playwright).
     */
    public Map<String, Integer> renormalizarCatalogo() {
        List<Product> actuales      = productos.cargarProductos();
        List<Product> renormalizados = normalizer.normalizar(actuales);
        Map<String, ClasificacionBloqueada> bloqueos = productos.cargarClasificacionBloqueada();

        int totalRevisados   = 0;
        int categoriaCambiada = 0;
        int marcaCambiada     = 0;

        // Antes de este fix, una excepción en el write se tragaba sin contarla en ningún lado, y un
        // UPDATE de 0 filas no se distinguía de uno exitoso.
        int escriturasIntentadas = 0;
        int escriturasAplicadas  = 0;
        int escriturasFallidas   = 0;
        // Contado aparte, nunca en escriturasFallidas.
        int escriturasOmitidasPorBloqueo = 0;

        int n = Math.min(actuales.size(), renormalizados.size());
        for (int i = 0; i < n; i++) {
            Product antes  = actuales.get(i);
            Product ahora  = renormalizados.get(i);
            if (antes.url() == null || !antes.url().equals(ahora.url())) continue;

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
                if (bloqueos != null && bloqueos.containsKey(ahora.url())) {
                    escriturasOmitidasPorBloqueo++;
                    continue;
                }
                escriturasIntentadas++;
                try {
                    int rows = productos.actualizarNormalizacion(ahora.url(), catAhora, marcaAhora, genAhora, tallesAhora, subCatAhora);
                    if (rows > 0) {
                        escriturasAplicadas++;
                    } else if (productos.estaBloqueado(ahora.url())) {
                        // A 0-row guarded write in that window is a correct lock skip, not a write
                        // failure; attribute it from a LIVE read taken right now, never from the
                        // stale snapshot.
                        escriturasOmitidasPorBloqueo++;
                    } else {
                        escriturasFallidas++;
                    }
                } catch (Exception e) {
                    escriturasFallidas++;
                    LOG.warn("[RENORM] Error escribiendo normalización para {}: {}", ahora.url(), e.getMessage());
                }
            }
        }

        if (escriturasFallidas > 0) {
            LOG.warn("[RENORM] Catálogo re-normalizado: {} revisados, {} con categoría cambiada, {} con marca cambiada — "
                    + "{}/{} escrituras aplicadas, {} fallidas, {} omitidas por bloqueo",
                    totalRevisados, categoriaCambiada, marcaCambiada,
                    escriturasAplicadas, escriturasIntentadas, escriturasFallidas, escriturasOmitidasPorBloqueo);
        } else {
            LOG.info("[RENORM] Catálogo re-normalizado: {} revisados, {} con categoría cambiada, {} con marca cambiada — "
                    + "{}/{} escrituras aplicadas, {} omitidas por bloqueo",
                    totalRevisados, categoriaCambiada, marcaCambiada, escriturasAplicadas, escriturasIntentadas,
                    escriturasOmitidasPorBloqueo);
        }

        Map<String, Integer> resultado = new LinkedHashMap<>();
        resultado.put("totalRevisados", totalRevisados);
        resultado.put("categoriaCambiada", categoriaCambiada);
        resultado.put("marcaCambiada", marcaCambiada);
        resultado.put("escriturasIntentadas", escriturasIntentadas);
        resultado.put("escriturasAplicadas", escriturasAplicadas);
        resultado.put("escriturasFallidas", escriturasFallidas);
        resultado.put("escriturasOmitidasPorBloqueo", escriturasOmitidasPorBloqueo);
        return resultado;
    }

    /**
     * A diferencia de {@link #agregar}, este camino NO corre el pipeline ML — pero SÍ debe correr
     * {@link SenalEnricher} y {@link FinanciacionEnricher}, porque de lo contrario sus badges
     * quedarían vacíos en el grid hasta el próximo scrape completo.
     */
    public AggregatedResult fromDB(List<Product> productos) {
        List<Product> conSenal = senalEnricher.enriquecer(productos);
        List<Product> conFinanciacion = financiacionEnricher.enriquecer(conSenal);
        return snapshot(conFinanciacion);
    }

    /**
     * Reusarla es correcto porque ambas señales son funciones puras de datos que no se movieron: la
     * de compra depende del historial del producto — y en esta corrida solo se insertaron filas de
     * historial para el sitio que terminó — y la de financiación depende del precio más el preset
     * activo.
     */
    public AggregatedResult fromDBParcial(List<Product> productos,
                                          AggregatedResult previo,
                                          Set<String> urlsRefrescadas) {
        if (productos == null || productos.isEmpty()) return fromDB(List.of());
        if (previo == null || previo.productos() == null || urlsRefrescadas == null)
            return fromDB(productos);

        Map<String, Product> anteriorPorUrl = new HashMap<>();
        for (Product p : previo.productos())
            if (StringUtils.isNotBlank(p.url())) anteriorPorUrl.putIfAbsent(p.url(), p);

        // Emparejamiento POSICIONAL, no por URL: un producto sin URL no tiene clave de reuso y
        // tiene que enriquecerse igual, como haría fromDB.
        List<Product>  resultado   = new ArrayList<>(Collections.nCopies(productos.size(), null));
        List<Integer>  posiciones  = new ArrayList<>();
        List<Product>  aEnriquecer = new ArrayList<>();

        for (int i = 0; i < productos.size(); i++) {
            Product p = productos.get(i);
            Product anterior = StringUtils.isNotBlank(p.url())
                    ? anteriorPorUrl.get(p.url()) : null;
            if (anterior == null || urlsRefrescadas.contains(p.url())) {
                posiciones.add(i);
                aEnriquecer.add(p);
            } else {
                resultado.set(i, conSenales(p, anterior.senal(), anterior.finan()));
            }
        }

        List<Product> frescos = financiacionEnricher.enriquecer(senalEnricher.enriquecer(aEnriquecer));
        if (frescos == null || frescos.size() != aEnriquecer.size()) {
            LOG.warn("[PARCIAL] El enricher devolvió {} productos para {} pedidos — " +
                     "refresco completo por seguridad",
                     frescos == null ? 0 : frescos.size(), aEnriquecer.size());
            return fromDB(productos);
        }
        for (int k = 0; k < posiciones.size(); k++) resultado.set(posiciones.get(k), frescos.get(k));

        LOG.debug("[PARCIAL] {} productos re-enriquecidos de {} en catálogo",
                aEnriquecer.size(), productos.size());
        return snapshot(resultado);
    }

    /**
     * Compartido por {@link #fromDB} y {@link #fromDBParcial} para que las dos no puedan divergir
     * en conteo, facets ni rango de precios. Asume la lista ordenada por precio ascendente, como la
     * devuelve {@code cargarProductos()}.
     */
    private AggregatedResult snapshot(List<Product> conFinanciacion) {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        conFinanciacion.forEach(p -> conteo.merge(p.sitio(), 1, Integer::sum));
        Facets facets = calcularFacets(conFinanciacion);
        double minP = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(0).precio();
        double maxP = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(conFinanciacion.size()-1).precio();
        return new AggregatedResult(conFinanciacion, conteo, Map.of(), facets, minP, maxP, Map.of());
    }

    /** Copia un producto reemplazando solo sus dos señales derivadas. */
    private static Product conSenales(Product p, Product.SenalCompra senal, Product.SenalFinanciacion finan) {
        return Product.builder()
                .sitio(p.sitio())
                .nombre(p.nombre())
                .precio(p.precio())
                .precioOriginal(p.precioOriginal())
                .url(p.url())
                .imagenUrl(p.imagenUrl())
                .categoria(p.categoria())
                .genero(p.genero())
                .talles(p.talles())
                .ml(p.ml())
                .marca(p.marca())
                .rubro(p.rubro())
                .gymrat(p.gymrat())
                .marcaPremium(p.marcaPremium())
                .senal(senal)
                .finan(finan)
                .cantidadUnidades(p.cantidadUnidades())
                .subCategoria(p.subCategoria())
                .visual(p.visual())
                .build();
    }
}
