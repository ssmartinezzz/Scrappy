package ar.scraper.web;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.catalog.CatalogoActualizado;
import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SitiosPort;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.health.SiteYieldGuard;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.pcs.TechSpecsIndexer;
import ar.scraper.scrapers.BaseScraper;
import ar.scraper.scrapers.ScraperFactory;
import com.microsoft.playwright.Playwright;
import com.opencsv.CSVWriter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

@Service
public class ScraperService implements CatalogSnapshotPort {

    private static final Logger LOG     = LoggerFactory.getLogger(ScraperService.class);
    private static final Logger RUN_LOG = LoggerFactory.getLogger("ar.scraper.run");

    private static final int TIMEOUT_GLOBAL_MIN  = 45;
    private static final int TIMEOUT_POR_SITIO_S = 600;

    /**
     * How often the cancellation flag gets a look while waiting for a site. Five seconds is the
     * responsiveness of cancel, not a timeout: the site budget above is untouched.
     */
    private static final long POLL_GRANULARIDAD_MS = 5_000;

    /** Gracia tras un timeout por sitio: puede terminar justo sobre el borde. */
    private static final long GRACIA_SITIO_MS = 2_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScraperConfig    config;
    private final ResultAggregator aggregator;

    private final AtomicReference<ScraperStatus> status =
            new AtomicReference<>(ScraperStatus.IDLE);
    private final AtomicReference<String> statusMsg =
            new AtomicReference<>("Listo");

    private volatile ProgressData progressData = null;
    private volatile AggregatedResult lastResult = null;
    // Null = no hay corrida, se sirve el vivo.
    private volatile AggregatedResult servedResult = null;
    private volatile java.util.Optional<java.time.Instant> cotaDeLectura =
            java.util.Optional.empty();
    private volatile int ultimasCategoriasRefinadas = 0;
    private volatile boolean forceRetrain = false;

    /**
     * {@code RunState} is null whenever the run bookkeeping failed to open its row, and
     * cancellation must keep working when the database does not. Cancelling is a safety control; it
     * cannot depend on accounting.
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelado =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Not a contingency: measured. Six chromium processes survived {@code exec.shutdownNow()} flat
     * for six minutes, still parented to the JVM — a leak, not orphans, in a process that never
     * restarts on a server.
     */
    private final java.util.Set<Playwright> playwrightsVivos =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Sin este lock, ambos escritores pueden interlevarse y descartar silenciosamente el catálogo
    // recién scrapeado.
    private final Object catalogLock = new Object();

    private final List<SitioExtra> sitiosExtras = new ArrayList<>();

    // SiteRegistry is injected as the @Component it always was, rather than read back through
    // DatabaseService.siteRegistry().
    private final ScrapeRunPort scrapeRun;
    private final SitiosPort sitios;
    private final MlOutputPort mlOutput;
    private final SiteRegistry siteRegistry;
    private final ProductPort productos;
    private final TechSpecsIndexer techSpecsIndexer;

    public record RunState(long runId, java.util.UUID scrapeUuid, java.time.Instant startedAt) {}

    private final java.util.concurrent.atomic.AtomicReference<RunState> runState =
            new java.util.concurrent.atomic.AtomicReference<>();

    public RunState getRunState() { return runState.get(); }

    private final ApplicationEventPublisher eventos;
    private final StatusEvents bus;
    private final AtomicLong snapshotVersion = new AtomicLong();
    private final AtomicReference<AggregatedResult> vistaPublicada = new AtomicReference<>();

    public ScraperService(ScraperConfig config, ResultAggregator aggregator,
                          ScrapeRunPort scrapeRun, SitiosPort sitios, MlOutputPort mlOutput,
                          SiteRegistry siteRegistry, ProductPort productos,
                          TechSpecsIndexer techSpecsIndexer) {
        this(config, aggregator, scrapeRun, sitios, mlOutput, siteRegistry, productos,
             techSpecsIndexer, evento -> { });
    }

    public ScraperService(ScraperConfig config, ResultAggregator aggregator,
                          ScrapeRunPort scrapeRun, SitiosPort sitios, MlOutputPort mlOutput,
                          SiteRegistry siteRegistry, ProductPort productos,
                          TechSpecsIndexer techSpecsIndexer, ApplicationEventPublisher eventos) {
        this(config, aggregator, scrapeRun, sitios, mlOutput, siteRegistry, productos,
             techSpecsIndexer, eventos, StatusEvents.NONE);
    }

    @Autowired
    public ScraperService(ScraperConfig config, ResultAggregator aggregator,
                          ScrapeRunPort scrapeRun, SitiosPort sitios, MlOutputPort mlOutput,
                          SiteRegistry siteRegistry, ProductPort productos,
                          TechSpecsIndexer techSpecsIndexer, ApplicationEventPublisher eventos,
                          StatusEvents bus) {
        this.eventos      = eventos;
        this.bus          = bus;
        this.config       = config;
        this.aggregator   = aggregator;
        this.scrapeRun    = scrapeRun;
        this.sitios       = sitios;
        this.mlOutput     = mlOutput;
        this.siteRegistry = siteRegistry;
        this.productos    = productos;
        this.techSpecsIndexer = techSpecsIndexer;
    }

    @PostConstruct
    public void cargarDesdeBD() {
        try {
            for (var row : sitios.cargarSitiosDinamicos()) {
                sitiosExtras.add(new SitioExtra(
                        row.get("nombre"), row.get("url"), row.get("plataforma")));
            }
            LOG.info("[DB] {} sitios dinámicos cargados", sitiosExtras.size());
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando sitios: {}", e.getMessage());
        }

        // Marking is also what keeps the signal single-valued: without it a second restart finds
        // two runs still claiming to be live and "the interrupted run" stops naming one thing.
        try {
            var interrumpidos = scrapeRun.marcarInterrumpidosAlArrancar(java.time.Instant.now());
            if (!interrumpidos.isEmpty()) {
                LOG.warn("[DB] {} corrida(s) quedaron interrumpidas por un cierre anterior: {}",
                        interrumpidos.size(), interrumpidos);
            }
            interrumpida.set(scrapeRun.ultimaInterrumpida().orElse(null));
            var det = interrumpida.get();
            if (det != null) {
                LOG.warn("[DB] corrida {} quedó interrumpida: {} sitio(s) atendidos, "
                         + "{} pendiente(s). Se OFRECE retomarla; no se retoma sola.",
                        det.runId(), det.atendidos().size(), det.pendientes().size());
            }
        } catch (Exception e) {
            LOG.warn("[DB] No se pudo revisar corridas interrumpidas: {}", e.getMessage());
        }

        try {
            List<ar.scraper.model.Product> prods = productos.cargarProductos();
            if (!prods.isEmpty()) {
                synchronized (catalogLock) { lastResult = aggregator.fromDB(prods); }
                publicarCambio();
                com.fasterxml.jackson.databind.JsonNode mlOut = mlOutput.cargarMlOutput();
                if (mlOut != null) aggregator.setLastMlOutput(mlOut);
                transition(ScraperStatus.DONE, "Datos restaurados: " + prods.size() + " productos");
                LOG.info("[DB] Datos restaurados: {} productos", prods.size());
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error restaurando resultados: {}", e.getMessage());
        }
    }

    /**
     * Bumps on every change to what readers are served; caches derived from the snapshot key on it.
     */
    public long snapshotVersion() { return snapshotVersion.get(); }

    /**
     * Must follow every assignment of {@code lastResult} / {@code servedResult}, outside
     * {@code catalogLock}.
     */
    private void publicarCambio() {
        AggregatedResult servido = getLastResult();
        if (vistaPublicada.getAndSet(servido) == servido) return;
        eventos.publishEvent(new CatalogoActualizado(snapshotVersion.incrementAndGet()));
    }

    public record SitioExtra(String nombre, String url, String plataforma) {}

    public enum SitioEstado { ESPERANDO, EN_CURSO, DONE, ERROR }

    public record SitioProgress(
        String nombre, SitioEstado estado, int productos, String error, long duracionMs) {}

    public record ProgressData(
        int total, int completados, int productosAcumulados,
        List<SitioProgress> sitios) {}

    public ScraperStatus    getStatus()       { return status.get(); }
    public String           getStatusMsg()    { return statusMsg.get(); }

    private void transition(ScraperStatus nuevo, String msg) {
        status.set(nuevo);
        statusMsg.set(msg);
        bus.publish(new StatusEvent.ScrapeStatus(nuevo, msg));
    }

    private void anunciar(String msg) {
        statusMsg.set(msg);
        bus.publish(new StatusEvent.ScrapeStatus(status.get(), msg));
    }

    private void progreso(ProgressData p) {
        progressData = p;
        bus.publish(new StatusEvent.ScrapeProgress(p.total(), p.completados(), p.productosAcumulados(),
                p.sitios().stream()
                        .map(x -> new StatusEvent.SiteProgress(
                                x.nombre(), x.estado().name(), x.productos(), x.error(), x.duracionMs()))
                        .toList()));
    }
    /**
     * Durante una corrida es la foto previa: el rearmado progresivo muta {@code lastResult} sitio
     * por sitio, y sin esto el dashboard ve el catálogo a medio reconstruir.
     */
    @Override
    public AggregatedResult getLastResult() {
        AggregatedResult servido = servedResult;
        return servido != null ? servido : lastResult;
    }

    public java.util.Optional<java.time.Instant> cotaDeLectura() { return cotaDeLectura; }
    public int  getUltimasCategoriasRefinadas()         { return ultimasCategoriasRefinadas; }
    public void setUltimasCategoriasRefinadas(int n)    { ultimasCategoriasRefinadas = n; }
    public ProgressData     getProgressData() { return progressData; }
    public List<SitioExtra> getSitiosExtras() { return Collections.unmodifiableList(sitiosExtras); }
    public void clearLastResult() {
        synchronized (catalogLock) {
            this.lastResult = null;
            this.servedResult = null;
        }
        publicarCambio();
    }

    /**
     * Saca un producto del catálogo en memoria tras un soft-delete manual en DB
     * (db.marcarDescontinuado ya puso activo=0; /api/data lee de lastResult, no de la DB en cada
     * request, así que sin esto el producto seguiría apareciendo hasta el próximo scrape/restart).
     */
    public void eliminarProductoDeMemoria(String url) {
        synchronized (catalogLock) {
            if (lastResult == null || url == null) return;
            List<Product> filtrados = lastResult.productos().stream()
                    .filter(p -> !url.equals(p.url()))
                    .toList();
            lastResult = new AggregatedResult(filtrados, lastResult.conteoPorSitio(),
                    lastResult.erroresPorSitio(), lastResult.facets(),
                    lastResult.minPrecio(), lastResult.maxPrecio(),
                    lastResult.statsPorSitio());
            servedResult = sinProducto(servedResult, url);
        }
        publicarCambio();
    }

    private static AggregatedResult sinProducto(AggregatedResult foto, String url) {
        if (foto == null) return null;
        List<Product> filtrados = foto.productos().stream()
                .filter(p -> !url.equals(p.url()))
                .toList();
        return new AggregatedResult(filtrados, foto.conteoPorSitio(), foto.erroresPorSitio(),
                foto.facets(), foto.minPrecio(), foto.maxPrecio(), foto.statsPorSitio());
    }

    /**
     * {@code /api/data} y {@code /api/mejores} sirven de {@code lastResult}, no de la DB en cada
     * request, así que sin esto el cambio no se vería hasta el próximo scrape/restart.
     */
    /**
     * {@code rubro} was a pre-existing bug — this method kept {@code p.rubro()} unconditionally, so
     * {@code rubro} diverged from a human-set {@code categoria} the moment
     * {@code POST /api/agent/apply} ran, before any scrape.
     */
    public void actualizarProductoEnMemoria(String url, String categoria, String marca,
                                            String genero, String subCategoria, String rubro) {
        synchronized (catalogLock) {
            if (lastResult == null || url == null) return;
            lastResult = reclasificado(lastResult, url, categoria, marca, genero, subCategoria, rubro);
            servedResult = reclasificado(servedResult, url, categoria, marca, genero, subCategoria, rubro);
        }
        publicarCambio();
    }

    private static AggregatedResult reclasificado(AggregatedResult foto, String url,
                                                  String categoria, String marca, String genero,
                                                  String subCategoria, String rubro) {
        if (foto == null) return null;
        List<Product> parcheados = foto.productos().stream()
                .map(p -> url.equals(p.url())
                        ? new Product(p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                                p.url(), p.imagenUrl(),
                                noVacio(categoria, p.categoria()), noVacio(genero, p.genero()),
                                p.talles(), p.ml(), noVacio(marca, p.marca()), noVacio(rubro, p.rubro()),
                                p.gymrat(), p.marcaPremium(), p.senal(), p.finan(),
                                p.cantidadUnidades(), noVacio(subCategoria, p.subCategoria()),
                                p.visual())
                        : p)
                .toList();
        return new AggregatedResult(parcheados, foto.conteoPorSitio(), foto.erroresPorSitio(),
                ResultAggregator.calcularFacets(parcheados), foto.minPrecio(), foto.maxPrecio(),
                foto.statsPorSitio());
    }

    private static String noVacio(String nuevo, String anterior) {
        return StringUtils.isNotBlank(nuevo) ? nuevo : anterior;
    }

    /**
     * Test seam — replaces the in-memory catalog directly, without going through a scrape/fromDB
     * cycle.
     */
    public void setLastResultParaTest(AggregatedResult result) {
        synchronized (catalogLock) { this.lastResult = result; }
        publicarCambio();
    }

    /**
     * Synchronously re-runs {@link ar.scraper.ml.FinanciacionEnricher} over the currently loaded
     * in-memory catalog and replaces it in place — triggered by preset activate/edit.
     */
    public void recomputarFinanciacion(ResultAggregator aggregator) {
        synchronized (catalogLock) {
            AggregatedResult actual = this.lastResult;
            if (actual == null) return;

            this.lastResult = refinanciado(actual, aggregator);
            this.servedResult = refinanciado(this.servedResult, aggregator);
        }
        publicarCambio();
    }

    private static AggregatedResult refinanciado(AggregatedResult foto, ResultAggregator aggregator) {
        if (foto == null) return null;
        List<Product> reenriquecidos = aggregator.financiacionEnricher().enriquecer(foto.productos());
        return new AggregatedResult(reenriquecidos, foto.conteoPorSitio(), foto.erroresPorSitio(),
                foto.facets(), foto.minPrecio(), foto.maxPrecio(), foto.statsPorSitio());
    }

    public void agregarSitio(String nombre, String url, String plataforma) {
        sitiosExtras.removeIf(s -> s.nombre().equalsIgnoreCase(nombre));
        sitiosExtras.add(new SitioExtra(nombre, url, plataforma));
    }
    public boolean eliminarSitio(String nombre) {
        return sitiosExtras.removeIf(s -> s.nombre().equalsIgnoreCase(nombre));
    }

    public boolean iniciarScraping(Set<String> sitiosSeleccionados, boolean forceRetrain) {
        // `runState` sólo puede nombrar una, así que la 21 quedó RUNNING para siempre.
        if (!tomarElTurno()) return false;
        this.forceRetrain = forceRetrain;
        anunciar("Iniciando scrapers...");
        Thread.ofVirtual().start(() -> {
            try { ejecutarScraping(sitiosSeleccionados); }
            catch (Exception e) {
                RUN_LOG.error("[ERROR FATAL] {}", e.getMessage());
                cerrarRun("ERROR", 0);
                transition(ScraperStatus.ERROR, "Error: " + e.getMessage());
            }
        });
        return true;
    }

    public boolean iniciarScraping(Set<String> sitiosSeleccionados) {
        return iniciarScraping(sitiosSeleccionados, false);
    }

    /** RUNNING sólo si no lo estaba ya, atómicamente: gana exactamente uno. */
    private boolean tomarElTurno() {
        for (ScraperStatus libre : new ScraperStatus[]{
                ScraperStatus.IDLE, ScraperStatus.DONE, ScraperStatus.ERROR}) {
            if (status.compareAndSet(libre, ScraperStatus.RUNNING)) return true;
        }
        return false;
    }

    private void ejecutarScraping(Set<String> sitiosSeleccionados) throws Exception {
        ejecutarScraping(sitiosSeleccionados, null);
    }

    private void ejecutarScraping(Set<String> sitiosSeleccionados, RunState adoptada) throws Exception {
        long runStart = System.currentTimeMillis();
        String ts = LocalDateTime.now().format(TS);

        List<ScraperConfig.SiteConfig> todos = buildSiteList(sitiosSeleccionados);
        int totalSitios = todos.size();

        // `pendientes` trae `sitio_key` y `buildSiteList` filtra por `nombre`: si no matchea
        // ninguno, `newFixedThreadPool(0)` tira y la corrida recién adoptada queda abierta otra
        // vez.
        if (totalSitios == 0) {
            RUN_LOG.warn("[AVISO]   No hay ningún sitio que scrapear ({}). "
                         + "La corrida se cierra sin tocar el catálogo.",
                    sitiosSeleccionados == null ? "registro vacío"
                            : "ninguno de " + sitiosSeleccionados + " está en el registro");
            if (adoptada != null) adoptarCorrida(adoptada);
            cerrarRun("CANCELLED", lastResult != null ? lastResult.productos().size() : 0);
            transition(ScraperStatus.DONE, "No había sitios que scrapear");
            return;
        }

        cancelado.set(false);
        playwrightsVivos.clear();
        if (adoptada != null) {
            adoptarCorrida(adoptada);
            RUN_LOG.info("[RETOMA]  corrida {} retomada con {} sitio(s) pendientes",
                    adoptada.runId(), totalSitios);
        } else {
            abrirRun(todos);
        }

        List<SitioProgress> progSitios = Collections.synchronizedList(new ArrayList<>());
        for (var site : todos) {
            progSitios.add(new SitioProgress(site.nombre(), SitioEstado.ESPERANDO, 0, null, 0));
        }
        progreso(new ProgressData(totalSitios, 0, 0, progSitios));

        RUN_LOG.info("════════════════════════════════════════════════════════");
        RUN_LOG.info("[INICIO] {} | Sitios: {} | Precio: ${} - ${}",
                ts, totalSitios, fmt(config.getPrecioMinimo()), fmt(config.getPrecioMaximo()));
        RUN_LOG.info("────────────────────────────────────────────────────────");

        int threads = Math.min(config.getThreadsParalelos(), totalSitios);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        ExecutorCompletionService<ScrapeResult> ecs = new ExecutorCompletionService<>(exec);

        Map<String, Integer> idxMap = new LinkedHashMap<>();
        for (int i = 0; i < todos.size(); i++) {
            String nombre = todos.get(i).nombre();
            idxMap.put(nombre, i);

            actualizarProgreso(progSitios, i, SitioEstado.EN_CURSO, 0, null, 0);
            registrarSitioEnCurso(nombre);
            progreso(new ProgressData(totalSitios, 0, 0, List.copyOf(progSitios)));

            final var site = todos.get(i);
            RUN_LOG.info("[INICIO]  {} scrapeando...", String.format("%-15s", site.nombre()));
            ecs.submit(() -> {
                try {
                    return withRetry(() -> {
                        // Registrado ANTES de usarse y sacado en el finally: si cancelar llega en
                        // el medio, tiene a quién cerrarle.
                        Playwright pw = Playwright.create();
                        playwrightsVivos.add(pw);
                        try {
                            BaseScraper scraper = ScraperFactory.crear(config, site, siteRegistry);
                            return scraper.ejecutar(pw);
                        } finally {
                            playwrightsVivos.remove(pw);
                            pw.close();
                        }
                    }, 3, 2000, cancelado::get);
                } catch (Exception e) {
                    return new ScrapeResult(site.nombre(), List.of(), e.getMessage(), 0);
                }
            });
        }
        exec.shutdown();

        long deadline = System.currentTimeMillis() + TIMEOUT_GLOBAL_MIN * 60_000L;
        AtomicInteger completados = new AtomicInteger(0);
        AtomicInteger productosAcumulados = new AtomicInteger(0);

        List<ScrapeResult> resultados = SiteResultCollector.recolectar(
                ecs, totalSitios, deadline, TIMEOUT_POR_SITIO_S, POLL_GRANULARIDAD_MS, GRACIA_SITIO_MS,
                cancelado,
                r -> {
                    int n = r.productos().size();
                    boolean tieneError = StringUtils.isNotBlank(r.error());
                    SitioEstado estado = (tieneError && n == 0) ? SitioEstado.ERROR : SitioEstado.DONE;

                    int idx = idxMap.getOrDefault(r.sitio(), -1);
                    if (idx >= 0) actualizarProgreso(progSitios, idx, estado, n, r.error(), r.duracionMs());
                    registrarSitioTerminado(r.sitio(), estado == SitioEstado.ERROR ? "ERROR" : "DONE",
                            n, r.error());

                    int comp = completados.incrementAndGet();
                    int prods = productosAcumulados.addAndGet(n);
                    progreso(new ProgressData(totalSitios, comp, prods, List.copyOf(progSitios)));
                    anunciar(comp + "/" + totalSitios + " sitios — " + prods + " productos (en curso)");
                    logSitioResult(r);

                    // ── Actualización progresiva ────────────────────────────── upsertParcial NO
                    // hace soft-delete → todos los sitios acumulan
                    if (!r.productos().isEmpty()) {
                        try {
                            var normalizados = aggregator.normalizarSolo(r.productos());
                            productos.upsertParcial(normalizados);
                            var todosActuales = productos.cargarProductos();
                            if (!todosActuales.isEmpty()) {
                                // Solo este sitio pudo cambiar algo, así que solo sus URLs
                                // necesitan re-enriquecerse.
                                Set<String> urlsDelSitio = normalizados.stream()
                                        .map(Product::url)
                                        .filter(u -> StringUtils.isNotBlank(u))
                                        .collect(Collectors.toSet());
                                synchronized (catalogLock) {
                                    lastResult = aggregator.fromDBParcial(todosActuales, lastResult, urlsDelSitio);
                                }
                                publicarCambio();
                                LOG.debug("[PARCIAL] {} → {} productos totales",
                                        r.sitio(), todosActuales.size());
                            }
                        } catch (Exception ex) {
                            LOG.warn("[PARCIAL] Error: {}", ex.getMessage());
                        }
                    }
                });
        exec.shutdownNow();

        // registrarSitioTerminado también acá: sin él, scrape_run_site queda RUNNING para siempre
        // en una corrida COMPLETED.
        if (!cancelado.get()) {
            for (SitioProgress sp : progSitios) {
                if (sp.estado() == SitioEstado.EN_CURSO || sp.estado() == SitioEstado.ESPERANDO) {
                    int idx = idxMap.getOrDefault(sp.nombre(), -1);
                    if (idx >= 0) actualizarProgreso(progSitios, idx, SitioEstado.ERROR, 0, "Deadline", 0);
                    resultados.add(new ScrapeResult(sp.nombre(), List.of(), "Deadline global", 0));
                    registrarSitioTerminado(sp.nombre(), "ERROR", 0, "Deadline global");
                    RUN_LOG.warn("[SITIO]   {} →    0 productos  (deadline global)",
                            String.format("%-15s", sp.nombre()));
                }
            }
            progreso(new ProgressData(totalSitios, completados.get(), productosAcumulados.get(),
                    List.copyOf(progSitios)));
        }

        if (cancelado.get()) {
            // Adentro vive el soft-delete, que da por ausente todo lo que no vino en ESTA tanda de
            // resultados — y una corrida cancelada tiene, por definición, sitios que nunca llegaron
            // a hablar.
            cerrarPlaywrightsHuerfanos();
            cerrarRun("CANCELLED", 0);
            transition(ScraperStatus.DONE, "Cancelado — el catálogo quedó como estaba");
            RUN_LOG.warn("[CANCEL]  Corrida cancelada: no se agregó ni se hizo soft-delete.");
            RUN_LOG.info("════════════════════════════════════════════════════════");
            return;
        }

        anunciar("Procesando y agregando resultados...");
        // Baseline for the yield guard, captured before aggregation overwrites it.
        Map<String, Integer> conteoPrevio = lastResult != null
                ? lastResult.conteoPorSitio() : Map.of();
        Set<String> sitiosDeEstaCorrida = resultados.stream()
                .map(ScrapeResult::sitio).collect(Collectors.toSet());

        // El soft-delete se acota al started_at de la corrida, no a este batch: un resume trae sólo
        // la mitad reanudada. Sin corrida persistida el alcance vuelve a derivarse del batch, como
        // antes.
        RunState corrida = runState.get();
        ar.scraper.scrape.CorridaEnCurso enCurso = corrida != null
                ? new ar.scraper.scrape.CorridaEnCurso(corrida.runId(), corrida.startedAt())
                : null;

        AggregatedResult delBatch = aggregator.agregar(resultados, forceRetrain, enCurso);
        synchronized (catalogLock) {
            lastResult = catalogoEntero(delBatch);
        }
        publicarCambio();

        // Own write path: a broken parse here can never take down the run that just aggregated the
        // whole catalog.
        try {
            techSpecsIndexer.indexar(lastResult.productos());
        } catch (Exception e) {
            LOG.warn("[TECH-SPECS] No se pudieron indexar las specs de producto: {}", e.getMessage());
        }

        // ── Guardia de rendimiento por sitio ───────────────────────────────── A broken scraper
        // returns an empty or truncated list without throwing, so the run reports success either
        // way.
        List<SiteYieldGuard.Alerta> alertas = SiteYieldGuard.evaluar(
                conteoPrevio, lastResult.conteoPorSitio(), sitiosDeEstaCorrida);
        if (!alertas.isEmpty()) {
            synchronized (catalogLock) {
                lastResult = new AggregatedResult(
                        lastResult.productos(), lastResult.conteoPorSitio(),
                        SiteYieldGuard.fusionarEnErrores(lastResult.erroresPorSitio(), alertas),
                        lastResult.facets(), lastResult.minPrecio(), lastResult.maxPrecio(),
                        lastResult.statsPorSitio());
            }
            publicarCambio();
            for (SiteYieldGuard.Alerta a : alertas) {
                LOG.warn("[SALUD] {}", a.mensaje());
                RUN_LOG.warn("[SALUD]   {}", a.mensaje());
            }
        }
        cerrarRun("COMPLETED", lastResult != null ? lastResult.productos().size() : 0);

        anunciar("Entrenando modelo ML en background...");
        ultimasCategoriasRefinadas = aggregator.getLastCatRefinadas();
        long durMs = System.currentTimeMillis() - runStart;

        long conFoto = lastResult.productos().stream()
                .filter(p -> StringUtils.isNotBlank(p.imagenUrl())).count();
        long sinFoto = lastResult.productos().size() - conFoto;

        RUN_LOG.info("────────────────────────────────────────────────────────");
        RUN_LOG.info("[FIN]     Productos: {} únicos  |  Con foto: {}  Sin foto: {}  |  Duración: {}",
                lastResult.productos().size(), conFoto, sinFoto, formatDuracion(durMs));

        List<String> vacios = resultados.stream()
                .filter(r -> r.productos().isEmpty() && StringUtils.isBlank(r.error()))
                .map(ScrapeResult::sitio).toList();
        if (!vacios.isEmpty())
            RUN_LOG.info("[AVISO]   Sitios sin productos: {}", String.join(", ", vacios));

        List<String> conError = resultados.stream()
                .filter(r -> StringUtils.isNotBlank(r.error()))
                .map(r -> r.sitio() + " (" + truncar(r.error(), 50) + ")").toList();
        if (!conError.isEmpty())
            RUN_LOG.info("[ERRORES] {}", String.join(" | ", conError));

        RUN_LOG.info("════════════════════════════════════════════════════════");

        transition(ScraperStatus.DONE, "Completado: " + lastResult.productos().size() + " productos");
    }

    /** El catálogo entero, no sólo los sitios de esta corrida. */
    AggregatedResult catalogoEntero(AggregatedResult delBatch) {
        try {
            List<Product> activos = productos.cargarProductos();
            if (activos.isEmpty()) return delBatch;

            Set<String> urlsDelBatch = delBatch.productos().stream()
                    .map(Product::url)
                    .filter(u -> StringUtils.isNotBlank(u))
                    .collect(Collectors.toSet());

            AggregatedResult completo = aggregator.fromDBParcial(activos, lastResult, urlsDelBatch);
            return new AggregatedResult(
                    completo.productos(), completo.conteoPorSitio(),
                    delBatch.erroresPorSitio(), completo.facets(),
                    completo.minPrecio(), completo.maxPrecio(), delBatch.statsPorSitio());
        } catch (Exception e) {
            LOG.warn("[AGG] no se pudo recargar el catálogo completo tras agregar, "
                     + "queda sólo lo de esta corrida: {}", e.getMessage());
            return delBatch;
        }
    }

    private void actualizarProgreso(List<SitioProgress> lista, int idx,
                                    SitioEstado estado, int n, String error, long ms) {
        if (idx < 0 || idx >= lista.size()) return;
        SitioProgress old = lista.get(idx);
        lista.set(idx, new SitioProgress(old.nombre(), estado, n, error, ms));
    }

    /**
     * Detectar no reanuda: un reinicio que retomara trabajo solo sería una falla peor que la caída
     * que está atendiendo — nadie pidió ese scrape, y arrancaría browsers en un servidor que quizá
     * se reinició justo para dejar de hacerlo.
     */
    private final java.util.concurrent.atomic.AtomicReference<
            ar.scraper.scrape.CorridaInterrumpida> interrumpida =
            new java.util.concurrent.atomic.AtomicReference<>();

    public ar.scraper.scrape.CorridaInterrumpida getInterrumpida() {
        return interrumpida.get();
    }

    /** Retoma la corrida interrumpida: sólo los sitios que faltan.. */
    public boolean reanudar() {
        var det = interrumpida.get();
        if (det == null) return false;
        if (!tomarElTurno()) return false;

        try {
            // Se marca SKIPPED y se NOMBRA: desaparecer en silencio de una corrida que lo debía es
            // peor que no retomarlo.
            List<String> nombresActuales = buildSiteList(null).stream()
                    .map(ScraperConfig.SiteConfig::nombre).toList();
            scrapeRun.marcarAusentesDelRegistro(det.runId(), nombresActuales);

            var actualizada = scrapeRun.ultimaInterrumpida().orElse(det);
            scrapeRun.reabrir(det.runId());
            interrumpida.set(null);

            RunState adoptada = new RunState(det.runId(), det.uuid(), det.startedAt());
            cancelado.set(false);
            playwrightsVivos.clear();

            if (actualizada.pendientes().isEmpty()) {
                anunciar("Retomando: sólo la pasada final");
                Thread.ofVirtual().start(() -> soloPasadaFinal(adoptada));
            } else {
                anunciar("Retomando " + actualizada.pendientes().size() + " sitio(s)...");
                Set<String> pendientes = new HashSet<>(actualizada.pendientes());
                Thread.ofVirtual().start(() -> {
                    try { ejecutarScraping(pendientes, adoptada); }
                    catch (Exception e) {
                        RUN_LOG.error("[ERROR FATAL] al retomar: {}", e.getMessage());
                        cerrarRun("ERROR", 0);
                        transition(ScraperStatus.ERROR, "Error al retomar: " + e.getMessage());
                    }
                });
            }
            return true;
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo retomar la corrida {}: {}", det.runId(), e.getMessage());
            transition(ScraperStatus.ERROR, "No se pudo retomar: " + e.getMessage());
            return false;
        }
    }

    /** Cierra como CANCELLED toda corrida interrumpida, sin scrapear ni tocar el catálogo. */
    public int descartarInterrumpidas() {
        try {
            List<Long> cerradas = scrapeRun.descartarInterrumpidas(java.time.Instant.now());
            interrumpida.set(null);
            if (!cerradas.isEmpty())
                RUN_LOG.warn("[DESCARTE] {} corrida(s) interrumpida(s) cerradas sin retomar: {}",
                        cerradas.size(), cerradas);
            return cerradas.size();
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudieron descartar las corridas interrumpidas: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * El caso que se olvida: la caída fue DESPUÉS de que todos los sitios terminaron, durante la
     * pasada de ML/agregación. Lo que esto NO hace: no vuelve a correr el pipeline de ML.
     */
    private void soloPasadaFinal(RunState corrida) {
        try {
            adoptarCorrida(corrida);
            anunciar("Barrido final de la corrida retomada...");
            productos.upsertProductos(List.of(),
                    new ar.scraper.scrape.CorridaEnCurso(corrida.runId(), corrida.startedAt()));

            List<ar.scraper.model.Product> prods = productos.cargarProductos();
            synchronized (catalogLock) { lastResult = aggregator.fromDB(prods); }
            publicarCambio();

            cerrarRun("COMPLETED", prods.size());
            transition(ScraperStatus.DONE, "Corrida retomada y cerrada: " + prods.size() + " productos");
            RUN_LOG.info("[RETOMA]  pasada final completada, {} productos", prods.size());
        } catch (Exception e) {
            RUN_LOG.error("[ERROR FATAL] en la pasada final: {}", e.getMessage());
            cerrarRun("ERROR", 0);
            transition(ScraperStatus.ERROR, "Error en la pasada final: " + e.getMessage());
        }
    }

    /** Idempotent; a no-op when nothing runs.. */
    public boolean cancelar() {
        if (status.get() != ScraperStatus.RUNNING) return false;
        cancelado.set(true);
        anunciar("Cancelando...");
        RUN_LOG.warn("[CANCEL]  Cancelación pedida por el usuario");
        return true;
    }

    public boolean estaCancelado() { return cancelado.get(); }

    /**
     * The count is logged rather than assumed: this is the one place that can tell us whether the
     * interrupt-based teardown ever starts working, and a silent close would hide both the leak and
     * its eventual fix.
     */
    private void cerrarPlaywrightsHuerfanos() {
        int sobrevivientes = playwrightsVivos.size();
        if (sobrevivientes == 0) {
            LOG.info("[CANCEL] no quedaron instancias de Playwright vivas");
            return;
        }
        LOG.warn("[CANCEL] cerrando {} instancia(s) de Playwright que sobrevivieron "
                 + "a shutdownNow()", sobrevivientes);
        for (Playwright pw : playwrightsVivos) {
            try {
                pw.close();
            } catch (Exception e) {
                LOG.warn("[CANCEL] no se pudo cerrar una instancia: {}", e.getMessage());
            }
        }
        playwrightsVivos.clear();
    }

    // Registrar una corrida es contabilidad: que la contabilidad falle no puede abortar un scrape
    // que por lo demás anda.

    void abrirRun(List<ScraperConfig.SiteConfig> sitios) {
        try {
            java.util.UUID uuid = java.util.UUID.randomUUID();
            java.time.Instant arranque = java.time.Instant.now();
            List<String> nombres = sitios.stream().map(ScraperConfig.SiteConfig::nombre).toList();
            long runId = scrapeRun.crear(uuid, arranque, null, null, nombres);
            // El started_at que vale es el que quedó EN LA BASE, no el que mandamos: el repositorio
            // lo trunca al segundo para que la cota de aislamiento case con la resolución de
            // `touched_at`.
            java.time.Instant persistido = scrapeRun.startedAtDe(runId).orElse(arranque);
            adoptarCorrida(new RunState(runId, uuid, persistido));
            LOG.info("[RUN] corrida {} abierta con {} sitios", runId, nombres.size());
        } catch (Exception e) {
            runState.set(null);
            liberarLectores();
            LOG.warn("[RUN] no se pudo abrir la corrida, sigue sin registro: {}", e.getMessage());
        }
    }

    /**
     * Son tres los caminos que abren una: la normal, la retomada, y la que sólo debe el barrido
     * final.
     */
    private void adoptarCorrida(RunState corrida) {
        runState.set(corrida);
        aislarLectores(corrida.startedAt());
    }

    /**
     * La cota se suprime hasta que exista una corrida COMPLETED: puesta antes de eso, nada cumple
     * {@code touched_at < started_at} y la primera corrida de una instalación nueva sirve una
     * pantalla vacía.
     */
    private void aislarLectores(java.time.Instant arranque) {
        boolean hayCorridaCompletada;
        try {
            hayCorridaCompletada = scrapeRun.existeCorridaCompletada();
        } catch (Exception e) {
            // Sin respuesta no se aísla: servir de más es recuperable, servir una pantalla vacía
            // por un error de contabilidad no.
            hayCorridaCompletada = false;
            LOG.warn("[RUN] no se pudo resolver la cota de lectura, se sirve todo: {}",
                    e.getMessage());
        }
        synchronized (catalogLock) {
            servedResult = lastResult;
            cotaDeLectura = hayCorridaCompletada
                    ? java.util.Optional.of(arranque)
                    : java.util.Optional.empty();
        }
        publicarCambio();
    }

    private void liberarLectores() {
        synchronized (catalogLock) {
            servedResult = null;
            cotaDeLectura = java.util.Optional.empty();
        }
        publicarCambio();
    }

    private void registrarSitioEnCurso(String sitio) {
        RunState estado = runState.get();
        if (estado == null) return;
        try {
            scrapeRun.marcarSitioEnCurso(estado.runId(), sitio, java.time.Instant.now());
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo marcar '{}' en curso: {}", sitio, e.getMessage());
        }
    }

    private void registrarSitioTerminado(String sitio, String status, int productos, String error) {
        RunState estado = runState.get();
        if (estado == null) return;
        try {
            scrapeRun.marcarSitioTerminado(estado.runId(), sitio, status, productos, error,
                    java.time.Instant.now());
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo cerrar '{}': {}", sitio, e.getMessage());
        }
    }

    void cerrarRun(String status, int productos) {
        RunState estado = runState.getAndSet(null);
        // Antes del early-return: si la contabilidad falló a mitad, el aislamiento igual tiene que
        // soltarse o el lector queda congelado para siempre.
        liberarLectores();
        if (estado == null) return;
        try {
            scrapeRun.finalizar(estado.runId(), status, productos, java.time.Instant.now());
            LOG.info("[RUN] corrida {} cerrada como {} con {} productos",
                    estado.runId(), status, productos);
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo cerrar la corrida {}: {}", estado.runId(), e.getMessage());
        }
    }

    private List<ScraperConfig.SiteConfig> buildSiteList(Set<String> seleccionados) {
        List<ScraperConfig.SiteConfig> todos = new ArrayList<>(config.getSitiosActivos());
        for (SitioExtra extra : sitiosExtras)
            todos.add(new ScraperConfig.SiteConfig(extra.nombre(), extra.url(), "indumentaria"));
        if (seleccionados != null && !seleccionados.isEmpty()) {
            todos = todos.stream()
                    .filter(s -> seleccionados.stream()
                            .anyMatch(sel -> sel.equalsIgnoreCase(s.nombre())))
                    .collect(Collectors.toList());
        }
        return todos;
    }

    private void logSitioResult(ScrapeResult r) {
        int n = r.productos().size();
        long ms = r.duracionMs();
        boolean err = StringUtils.isNotBlank(r.error());
        long conFoto = r.productos().stream()
                .filter(p -> StringUtils.isNotBlank(p.imagenUrl())).count();
        String nombre = String.format("%-15s", r.sitio());
        String dur    = String.format("%.1fs", ms / 1000.0);
        if (err && n == 0)
            RUN_LOG.error("[SITIO]   {} →    0 productos  ({})  ERROR: {}", nombre, dur, truncar(r.error(), 80));
        else if (n == 0)
            RUN_LOG.warn("[SITIO]   {} →    0 productos  ({})  sin resultados", nombre, dur);
        else
            RUN_LOG.info("[SITIO]   {} → {} productos  ({})  fotos: {}/{}",
                    nombre, String.format("%4d", n), dur, conFoto, n);
    }

    private static String fmt(double v)       { return String.format("%,.0f", v); }
    private static String formatDuracion(long ms) {
        long s = ms / 1000; return s < 60 ? s + "s" : (s/60) + "m " + (s%60) + "s";
    }
    private static String truncar(String s, int max) {
        if (s == null) return ""; return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Re-throws {@link InterruptedException} immediately to preserve thread interrupt semantics.
     * Package-private so {@code ScraperServiceRetryTest} (same package) can call it directly
     * without exposing it as a public API.
     */
    /**
     * The budget is unchanged — still per-site, still
     * {@code min(TIMEOUT_POR_SITIO_S, global remaining)}. Do not "simplify" this back into a single
     * short poll.
     */
    static Future<ScrapeResult> esperarResultado(
            ExecutorCompletionService<ScrapeResult> ecs, long deadlineMs,
            long granularidadMs, java.util.concurrent.atomic.AtomicBoolean cancelado)
            throws InterruptedException {
        while (true) {
            // Checked BEFORE polling, so a cancel arriving between sites is not made to sit through
            // a poll window it did not need to.
            if (cancelado.get()) return null;

            long restanteMs = deadlineMs - System.currentTimeMillis();
            if (restanteMs <= 0) return null;

            Future<ScrapeResult> f =
                    ecs.poll(Math.min(granularidadMs, restanteMs), TimeUnit.MILLISECONDS);
            if (f != null) return f;
        }
    }

    static ScrapeResult withRetry(java.util.concurrent.Callable<ScrapeResult> task,
                                  int maxAttempts, long baseDelayMs)
            throws InterruptedException {
        return withRetry(task, maxAttempts, baseDelayMs, () -> false);
    }

    /**
     * Each attempt builds a fresh browser, so without this check cancelling would open up to two
     * more browsers per site instead of closing them, and the more sites were in flight the worse
     * it would get.
     */
    static ScrapeResult withRetry(java.util.concurrent.Callable<ScrapeResult> task,
                                  int maxAttempts, long baseDelayMs,
                                  java.util.function.BooleanSupplier cancelado)
            throws InterruptedException {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(attempt -> baseDelayMs * attempt)
                .retryOnException(e -> !(e instanceof InterruptedException)
                        && !(e instanceof RunCancelled) && !cancelado.getAsBoolean())
                .build();
        try {
            return Retry.of("site-scrape", config).executeCallable(() -> {
                if (cancelado.getAsBoolean()) throw new RunCancelled();
                return task.call();
            });
        } catch (RunCancelled c) {
            return new ScrapeResult("", List.of(), "cancelado", 0);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception e) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException();
            }
            return new ScrapeResult("", List.of(), e.getMessage(), 0);
        }
    }

    /** Thrown inside the retried callable when the run was cancelled before an attempt began. */
    private static final class RunCancelled extends RuntimeException {
        RunCancelled() {
            super(null, null, false, false);
        }
    }

    public String generarCsv() throws Exception {
        if (lastResult == null) return "";
        StringWriter sw = new StringWriter();
        try (CSVWriter w = new CSVWriter(sw)) {
            w.writeNext(new String[]{
                "Sitio","Nombre","Precio","Precio Original",
                "Categoria","Genero","Talles","URL","Imagen"});
            for (Product p : lastResult.productos()) {
                String tallesStr = p.talles() != null ? String.join("|", p.talles()) : "";
                w.writeNext(new String[]{
                    p.sitio(), p.nombre(), String.valueOf((long) p.precio()),
                    p.precioOriginal() != null ? String.valueOf(p.precioOriginal()) : "",
                    p.categoria()  != null ? p.categoria()  : "",
                    p.genero()     != null ? p.genero()     : "",
                    tallesStr,
                    p.url()        != null ? p.url()        : "",
                    p.imagenUrl()  != null ? p.imagenUrl()  : ""
                });
            }
        }
        return sw.toString();
    }
}
