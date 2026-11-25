package ar.scraper.aggregator;

import ar.scraper.catalog.ClasificacionBloqueada;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.Normalizacion;
import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.MlOutputLiviano;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDeCorrida;
import ar.scraper.model.promo.PromosDelSitio;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
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
    // actualizarNormalizaciones/estaBloqueado go through ProductPort instead.
    private final MlOutputPort         mlOutput;
    private final CategoriaStatsPort   categoriaStats;
    private final ProductPort          productos;

    // Estado del último run — leído por ScraperService sin inyección circular
    private volatile JsonNode lastMlOutput     = null;
    private volatile int      lastCatRefinadas = 0;

    public JsonNode             getLastMlOutput()    { return lastMlOutput; }
    /** Keeps what the readers use (see {@link MlOutputLiviano}), never the per-product scores. */
    public void                 setLastMlOutput(JsonNode n) { lastMlOutput = MlOutputLiviano.aligerar(n); }
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
        return p.precio() > 0
                && StringUtils.isNotBlank(p.url());
    }

    private record ValidationResult(
            Map<String, Integer>         conteo,
            Map<String, String>          errores,
            Map<String, ExtractionStats> stats,
            int                          validos
    ) {}

    /**
     * Output of {@link #ejecutarPipelineMl}: the post-ML enriched list, the category each product
     * had before the model, and the ML output without its per-product scores, which are spent once
     * they have been applied.
     */
    private record MlPipelineResult(
            List<Product>       enriquecidos,
            Map<String, String> categoriaPrevia,
            JsonNode            mlLiviano
    ) {}

    /** Products and an error together: the scraper gave up part-way, so absence proves nothing. */
    private static Set<String> sitiosParciales(List<ScrapeResult> resultados) {
        return resultados.stream()
                .filter(r -> StringUtils.isNotBlank(r.error()) && !r.productos().isEmpty())
                .map(ScrapeResult::sitio)
                .collect(Collectors.toSet());
    }

    /**
     * A site is promo-read only if its scraper flagged a successful read AND it ended clean: a failed
     * fetch must leave its stored promos alone, and wiping on doubt is the destructive direction.
     */
    static PromosDeCorrida promosDe(List<ScrapeResult> resultados) {
        Map<String, List<PromoFinanciacion>> delSitio = new LinkedHashMap<>();
        Set<String> leidos = new LinkedHashSet<>();
        for (ScrapeResult r : resultados) {
            PromosDelSitio p = r.promosDelSitio();
            if (!p.leido() || StringUtils.isNotBlank(r.error())) continue;
            leidos.add(r.sitio());
            if (!p.promos().isEmpty()) delSitio.put(r.sitio(), p.promos());
        }
        return new PromosDeCorrida(delSitio, leidos);
    }

    /** The 3-arg port call stays the path of a run with no promo signal, so that path is unchanged. */
    private void persistirProductos(List<Product> enriquecidos, ar.scraper.scrape.CorridaEnCurso corrida,
                                    List<ScrapeResult> resultados) {
        PromosDeCorrida promos = promosDe(resultados);
        if (promos.esVacio() && enriquecidos.stream().allMatch(p -> p.promos().isEmpty())) {
            productos.upsertProductos(enriquecidos, corrida, sitiosParciales(resultados));
        } else {
            productos.upsertProductos(enriquecidos, corrida, sitiosParciales(resultados), promos);
        }
    }

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

        List<Product> enriquecidos = enriquecerYPersistir(resultados, corrida);

        List<Product> conFinanciacion = enriquecerSenalYFinanciacion(enriquecidos);

        AggregatedResult resultado = resultado(conFinanciacion, validacion.conteo(), validacion.errores(),
                validacion.stats());

        LOG.info("Agregacion: {} brutos -> {} unicos (normalizado+ML)", validacion.validos(), conFinanciacion.size());

        LOG.info("[AGG] Lanzando entrenamiento del modelo en background...");
        pythonRunner.entrenarEnBackground(forceRetrain);

        return resultado;
    }

    /**
     * The ML stage and every write it feeds, in its own frame: what only they need (the category
     * each product had before the model, the output node) is garbage when they are done, before the
     * signals are computed and the whole catalogue is reloaded.
     */
    private List<Product> enriquecerYPersistir(List<ScrapeResult> resultados,
                                               ar.scraper.scrape.CorridaEnCurso corrida) {
        MlPipelineResult pipeline = ejecutarPipelineMl(resultados);

        persistirCategoriasRefinadas(pipeline.categoriaPrevia(), pipeline.enriquecidos());

        persistirProductos(pipeline.enriquecidos(), corrida, resultados);
        mlOutput.guardarMlOutput(pipeline.mlLiviano());
        if (pipeline.mlLiviano() != null && !pipeline.mlLiviano().path("categoriaStats").isMissingNode())
            categoriaStats.guardarCategoriaStats(pipeline.mlLiviano().path("categoriaStats"));

        return pipeline.enriquecidos();
    }

    private ValidationResult validarYContar(List<ScrapeResult> resultados) {
        Map<String, Integer>         conteo  = new LinkedHashMap<>();
        Map<String, String>          errores = new LinkedHashMap<>();
        Map<String, ExtractionStats> stats   = new LinkedHashMap<>();
        int                          validos = 0;

        for (ScrapeResult r : resultados) {
            int valid  = (int) r.productos().stream().filter(ResultAggregator::isValid).count();
            int misses = r.productos().size() - valid;
            conteo.put(r.sitio(), r.productos().size());
            stats.put(r.sitio(), new ExtractionStats(r.sitio(), r.productos().size(), valid, misses));
            if (misses > 0)
                LOG.warn("[METRICS] {}: {}/{} válidos ({} misses)", r.sitio(), valid, r.productos().size(), misses);
            if (!r.exitoso()) errores.put(r.sitio(), r.error());
            validos += valid;
        }

        return new ValidationResult(conteo, errores, stats, validos);
    }

    /** Only valid products flow downstream. */
    private static List<Product> productosValidos(List<ScrapeResult> resultados) {
        List<Product> todos = new ArrayList<>();
        for (ScrapeResult r : resultados)
            r.productos().stream().filter(ResultAggregator::isValid).forEach(todos::add);
        return todos;
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
    private MlPipelineResult ejecutarPipelineMl(List<ScrapeResult> resultados) {
        Map<String, ClasificacionBloqueada> bloqueos = productos.cargarClasificacionBloqueada();

        List<Product> normalizados = normalizarYBloquear(resultados, bloqueos);

        LOG.info("[AGG] Ejecutando pipeline ML (esto puede tomar hasta 2 minutos)...");
        JsonNode mlOut  = pythonRunner.ejecutarEscribiendo(mlEnricher.productosParaMl(normalizados));
        LOG.info("[AGG] Pipeline ML completado.");
        JsonNode mlLiviano = MlOutputLiviano.aligerar(mlOut);
        lastMlOutput = mlLiviano;

        List<Product> enriquecidos = aplicarBloqueos(mlEnricher.enriquecer(normalizados, mlOut), bloqueos);

        return new MlPipelineResult(enriquecidos, categoriaPorUrl(normalizados), mlLiviano);
    }

    /**
     * Its own frame so the raw lists it builds on the way are garbage once the normalized ones exist,
     * while the model runs.
     */
    private List<Product> normalizarYBloquear(List<ScrapeResult> resultados,
                                              Map<String, ClasificacionBloqueada> bloqueos) {
        List<Product> sorted = deduplicarYOrdenar(productosValidos(resultados));
        return aplicarBloqueos(normalizer.normalizar(sorted), bloqueos);
    }

    private static Map<String, String> categoriaPorUrl(List<Product> productos) {
        Map<String, String> categorias = new HashMap<>();
        for (Product p : productos)
            if (StringUtils.isNotBlank(p.url()))
                categorias.put(p.url(), StringUtils.defaultString(p.categoria()));
        return categorias;
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
            result.add(p.toBuilder()
                    .categoria(c.categoria())
                    .genero(c.genero())
                    .marca(c.marca())
                    .rubro(c.rubro())
                    .subCategoria(c.subCategoria())
                    .build());
        }
        return result;
    }

    /**
     * Diffs the pre-ML categoria ({@code catOriginal}, by url) against the post-ML one in
     * {@code enriquecidos}; persists only the deltas and updates {@link #lastCatRefinadas}.
     */
    private void persistirCategoriasRefinadas(Map<String, String> catOriginal, List<Product> enriquecidos) {
        int catRefinadas = 0;
        for (Product p : enriquecidos) {
            String pid = p.url();
            if (StringUtils.isBlank(pid)) continue;
            String antes = catOriginal.get(pid);
            String ahora = StringUtils.defaultString(p.categoria());
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

    private List<Product> conHojaDelSitio(List<Product> cargados) {
        Map<String, Integer> origenes = productos.origenDeCategoriaDelSitioPorUrl();
        if (origenes == null || origenes.isEmpty()) return cargados;
        return cargados.stream()
                .map(p -> origenes.containsKey(p.url())
                        ? p.toBuilder().sitioCategoriaOrigenId(origenes.get(p.url())).build()
                        : p)
                .collect(Collectors.toList());
    }

    /**
     * Re-aplica las reglas actuales de {@link NormalizerService} sobre el catálogo YA persistido en
     * la DB, sin re-scrapear (no toca internet, no usa Playwright). Todo lo que cambia se escribe en
     * UNA transacción: o entra entero o no entra nada, y una falla de la escritura se propaga.
     * El caller es dueño del turno de scrape y del slot de scoring (ver
     * {@code ScraperService#renormalizarCatalogo}).
     */
    public Map<String, Integer> renormalizarCatalogo() {
        List<Product> actuales      = productos.cargarProductos();
        List<Product> renormalizados = normalizer.normalizar(conHojaDelSitio(actuales));
        Map<String, ClasificacionBloqueada> bloqueos = productos.cargarClasificacionBloqueada();

        int totalRevisados   = 0;
        int categoriaCambiada = 0;
        int marcaCambiada     = 0;
        int rubroCambiado     = 0;
        int escriturasOmitidasPorBloqueo = 0;

        List<Normalizacion> aEscribir = new ArrayList<>();

        int n = Math.min(actuales.size(), renormalizados.size());
        for (int i = 0; i < n; i++) {
            Product antes  = actuales.get(i);
            Product ahora  = renormalizados.get(i);
            if (antes.url() == null || !antes.url().equals(ahora.url())) continue;

            totalRevisados++;
            String catAhora = StringUtils.defaultString(ahora.categoria());
            String marcaAhora = StringUtils.defaultString(ahora.marca());
            String genAhora = StringUtils.defaultString(ahora.genero());
            String subCatAhora = StringUtils.defaultString(ahora.subCategoria());
            String rubroAhora = StringUtils.defaultString(ahora.rubro());
            List<String> tallesAntes = antes.talles() != null ? antes.talles() : List.of();
            List<String> tallesAhora = ahora.talles() != null ? ahora.talles() : List.of();

            boolean catCambio    = !StringUtils.defaultString(antes.categoria()).equals(catAhora);
            boolean marcaCambio  = !StringUtils.defaultString(antes.marca()).equals(marcaAhora);
            boolean genCambio    = !StringUtils.defaultString(antes.genero()).equals(genAhora);
            boolean subCatCambio = !StringUtils.defaultString(antes.subCategoria()).equals(subCatAhora);
            boolean rubroCambio  = !StringUtils.defaultString(antes.rubro()).equals(rubroAhora);
            boolean tallesCambio = !tallesAntes.equals(tallesAhora);

            if (catCambio)   categoriaCambiada++;
            if (marcaCambio) marcaCambiada++;
            if (rubroCambio) rubroCambiado++;

            if (catCambio || marcaCambio || genCambio || tallesCambio || subCatCambio || rubroCambio) {
                if (bloqueos != null && bloqueos.containsKey(ahora.url())) {
                    escriturasOmitidasPorBloqueo++;
                    continue;
                }
                aEscribir.add(new Normalizacion(ahora.url(), catAhora, marcaAhora, genAhora,
                        tallesCambio ? tallesAhora : null, subCatAhora, rubroAhora));
            }
        }

        // A row neither updated nor locked is gone (or locked meanwhile: a LIVE read tells them apart,
        // never the stale snapshot).
        Set<String> actualizadas = productos.actualizarNormalizaciones(aEscribir);
        int escriturasIntentadas = aEscribir.size();
        int escriturasFallidas = 0;
        for (Normalizacion f : aEscribir) {
            if (actualizadas.contains(f.url())) continue;
            if (productos.estaBloqueado(f.url())) escriturasOmitidasPorBloqueo++;
            else escriturasFallidas++;
        }
        int escriturasAplicadas = actualizadas.size();

        LOG.info("[RENORM] Catálogo re-normalizado: {} revisados, {} con categoría cambiada, {} con marca cambiada, "
                + "{} con rubro cambiado — {}/{} escrituras aplicadas, {} sin fila, {} omitidas por bloqueo",
                totalRevisados, categoriaCambiada, marcaCambiada, rubroCambiado,
                escriturasAplicadas, escriturasIntentadas, escriturasFallidas, escriturasOmitidasPorBloqueo);

        Map<String, Integer> resultado = new LinkedHashMap<>();
        resultado.put("totalRevisados", totalRevisados);
        resultado.put("categoriaCambiada", categoriaCambiada);
        resultado.put("marcaCambiada", marcaCambiada);
        resultado.put("rubroCambiado", rubroCambiado);
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
     * The per-site refresh without reloading the catalogue: {@code previo} minus the refreshed URLs,
     * merged by price with {@code frescos} (the active rows of exactly those URLs, as the database
     * holds them now). A refreshed URL missing from {@code frescos} is gone from the snapshot. The
     * kept products are the same instances, with the signals they already had, as
     * {@link #fromDBParcial} reuses them; {@code previo} is not touched.
     *
     * <p>A previous product with no URL cannot come from the database, so it drops out, as it does
     * in a full reload. Throws if the enrichers answer with a different number of products: the
     * rows could not be told apart any more, and the caller keeps its previous snapshot.
     */
    public AggregatedResult fusionarRefrescados(AggregatedResult previo, Set<String> urlsRefrescadas,
                                                List<Product> frescos) {
        List<Product> enriquecidos = financiacionEnricher.enriquecer(senalEnricher.enriquecer(frescos));
        if (enriquecidos == null || enriquecidos.size() != frescos.size()) {
            throw new IllegalStateException("El enricher devolvió "
                    + (enriquecidos == null ? 0 : enriquecidos.size()) + " productos para "
                    + frescos.size() + " pedidos");
        }
        List<Product> nuevos = new ArrayList<>(enriquecidos);
        nuevos.sort(Comparator.comparingDouble(Product::precio));

        List<Product> anteriores = previo.productos();
        List<Product> fusionado = new ArrayList<>(anteriores.size() + nuevos.size());
        int j = 0;
        for (Product p : anteriores) {
            if (StringUtils.isBlank(p.url()) || urlsRefrescadas.contains(p.url())) continue;
            while (j < nuevos.size() && nuevos.get(j).precio() < p.precio()) fusionado.add(nuevos.get(j++));
            fusionado.add(p);
        }
        while (j < nuevos.size()) fusionado.add(nuevos.get(j++));
        return snapshot(fusionado);
    }

    /**
     * Compartido por {@link #fromDB} y {@link #fromDBParcial} para que las dos no puedan divergir
     * en conteo, facets ni rango de precios. Asume la lista ordenada por precio ascendente, como la
     * devuelve {@code cargarProductos()}.
     */
    private AggregatedResult snapshot(List<Product> conFinanciacion) {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        conFinanciacion.forEach(p -> conteo.merge(p.sitio(), 1, Integer::sum));
        return resultado(conFinanciacion, conteo, Map.of(), Map.of());
    }

    private AggregatedResult resultado(List<Product> conFinanciacion, Map<String, Integer> conteo,
            Map<String, String> errores, Map<String, ExtractionStats> stats) {
        Facets facets = calcularFacets(conFinanciacion);
        double minP = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(0).precio();
        double maxP = conFinanciacion.isEmpty() ? 0 : conFinanciacion.get(conFinanciacion.size()-1).precio();
        return new AggregatedResult(conFinanciacion, conteo, errores, facets, minP, maxP, stats);
    }

    /** Copia un producto reemplazando solo sus dos señales derivadas. */
    private static Product conSenales(Product p, Product.SenalCompra senal, Product.SenalFinanciacion finan) {
        return p.toBuilder()
                .senal(senal)
                .finan(finan)
                .build();
    }
}
