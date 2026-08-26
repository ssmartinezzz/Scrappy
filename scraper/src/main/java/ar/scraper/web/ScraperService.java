package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.db.DatabaseService;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.health.SiteYieldGuard;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.scrapers.BaseScraper;
import ar.scraper.scrapers.ScraperFactory;
import com.microsoft.playwright.Playwright;
import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

@Service
public class ScraperService {

    private static final Logger LOG     = LoggerFactory.getLogger(ScraperService.class);
    private static final Logger RUN_LOG = LoggerFactory.getLogger("ar.scraper.run");

    private static final int TIMEOUT_GLOBAL_MIN  = 45;
    private static final int TIMEOUT_POR_SITIO_S = 600;

    /**
     * How often the cancellation flag gets a look while waiting for a site.
     *
     * <p>Five seconds is the responsiveness of cancel, not a timeout: the site
     * budget above is untouched. See {@link #esperarResultado} for why this is
     * NOT the same as shortening the budget.</p>
     */
    private static final long POLL_GRANULARIDAD_MS = 5_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScraperConfig    config;
    private final ResultAggregator aggregator;

    private final AtomicReference<ScraperStatus> status =
            new AtomicReference<>(ScraperStatus.IDLE);
    private final AtomicReference<String> statusMsg =
            new AtomicReference<>("Listo");

    // Progreso en tiempo real
    private volatile ProgressData progressData = null;
    private volatile AggregatedResult lastResult = null;
    // Lo que ven los lectores mientras hay una corrida abierta: la referencia a
    // lastResult tal como estaba al arrancar, no una copia — AggregatedResult ya
    // es copy-on-write. Null = no hay corrida, se sirve el vivo.
    private volatile AggregatedResult servedResult = null;
    private volatile java.util.Optional<java.time.Instant> cotaDeLectura =
            java.util.Optional.empty();
    private volatile int ultimasCategoriasRefinadas = 0;
    private volatile boolean forceRetrain = false;

    /**
     * Set by {@code POST /api/scrape/cancel}, cleared when a run starts.
     *
     * <p>Deliberately a field on the service and NOT inside {@code RunState},
     * which the design suggested: {@code RunState} is null whenever the run
     * bookkeeping failed to open its row, and cancellation must keep working
     * when the database does not. Cancelling is a safety control; it cannot
     * depend on accounting.</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelado =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Every {@code Playwright} currently alive, so cancelling can close them.
     *
     * <p>Not a contingency: measured. Six chromium processes survived
     * {@code exec.shutdownNow()} flat for six minutes, still parented to the
     * JVM — a leak, not orphans, in a process that never restarts on a server.
     * {@code shutdownNow} interrupts threads, and Playwright's transport blocks
     * on pipe reads that are not guaranteed interruptible, so try-with-resources
     * never gets to run its close.</p>
     */
    private final java.util.Set<Playwright> playwrightsVivos =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Lock compartido entre el pipeline de scraping (que muta lastResult desde
    // un hilo en background, progresivamente y al finalizar) y
    // recomputarFinanciacion (que hace un read-modify-write sobre lastResult
    // al activar/editar un preset). Sin este lock, ambos escritores pueden
    // interlevarse y descartar silenciosamente el catálogo recién scrapeado.
    private final Object catalogLock = new Object();

    private final List<SitioExtra> sitiosExtras = new ArrayList<>();

    private final DatabaseService db;

    /**
     * The run currently open, or null when nothing is running.
     *
     * <p>Slice 3 adds the cancellation flag to this record; it is deliberately
     * absent rather than present-and-unused, so nothing can read a field that
     * no writer sets yet.</p>
     */
    public record RunState(long runId, java.util.UUID scrapeUuid, java.time.Instant startedAt) {}

    private final java.util.concurrent.atomic.AtomicReference<RunState> runState =
            new java.util.concurrent.atomic.AtomicReference<>();

    public RunState getRunState() { return runState.get(); }

    public ScraperService(ScraperConfig config, ResultAggregator aggregator, DatabaseService db) {
        this.config     = config;
        this.aggregator = aggregator;
        this.db         = db;
    }

    @PostConstruct
    public void cargarDesdeBD() {
        // Cargar sitios dinámicos persistidos
        try {
            for (var row : db.cargarSitiosDinamicos()) {
                sitiosExtras.add(new SitioExtra(
                        row.get("nombre"), row.get("url"), row.get("plataforma")));
            }
            LOG.info("[DB] {} sitios dinámicos cargados", sitiosExtras.size());
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando sitios: {}", e.getMessage());
        }

        // scrape-run-persistence-and-resume slice 1: whatever the previous process
        // left open is closed HERE, and only marked — this never starts a scrape.
        // Marking is also what keeps the signal single-valued: without it a second
        // restart finds two runs still claiming to be live and "the interrupted
        // run" stops naming one thing.
        try {
            var interrumpidos = db.marcarRunsInterrumpidos(java.time.Instant.now());
            if (!interrumpidos.isEmpty()) {
                LOG.warn("[DB] {} corrida(s) quedaron interrumpidas por un cierre anterior: {}",
                        interrumpidos.size(), interrumpidos);
            }
            interrumpida.set(db.ultimaCorridaInterrumpida().orElse(null));
            var det = interrumpida.get();
            if (det != null) {
                LOG.warn("[DB] corrida {} quedó interrumpida: {} sitio(s) atendidos, "
                         + "{} pendiente(s). Se OFRECE retomarla; no se retoma sola.",
                        det.runId(), det.atendidos().size(), det.pendientes().size());
            }
        } catch (Exception e) {
            LOG.warn("[DB] No se pudo revisar corridas interrumpidas: {}", e.getMessage());
        }

        // Cargar último resultado de scraping
        try {
            List<ar.scraper.model.Product> prods = db.cargarProductos();
            if (!prods.isEmpty()) {
                synchronized (catalogLock) { lastResult = aggregator.fromDB(prods); }
                // Restaurar ML output
                com.fasterxml.jackson.databind.JsonNode mlOut = db.cargarMlOutput();
                if (mlOut != null) aggregator.setLastMlOutput(mlOut);
                status.set(ScraperStatus.DONE);
                statusMsg.set("Datos restaurados: " + prods.size() + " productos");
                LOG.info("[DB] Datos restaurados: {} productos", prods.size());
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error restaurando resultados: {}", e.getMessage());
        }
    }

    public enum ScraperStatus { IDLE, RUNNING, DONE, ERROR }
    public record SitioExtra(String nombre, String url, String plataforma) {}

    // ── Estado de progreso por sitio ────────────────────────────────────────
    public enum SitioEstado { ESPERANDO, EN_CURSO, DONE, ERROR }

    public record SitioProgress(
        String nombre, SitioEstado estado, int productos, String error, long duracionMs) {}

    public record ProgressData(
        int total, int completados, int productosAcumulados,
        List<SitioProgress> sitios) {}

    public ScraperStatus    getStatus()       { return status.get(); }
    public String           getStatusMsg()    { return statusMsg.get(); }
    /**
     * El catálogo que se le sirve a un lector.
     *
     * <p>Durante una corrida es la foto previa: el rearmado progresivo muta
     * {@code lastResult} sitio por sitio, y sin esto el dashboard ve el catálogo
     * a medio reconstruir. Lo que el usuario hace él mismo sí llega — los cuatro
     * caminos de escritura parchean las dos fotos.</p>
     */
    public AggregatedResult getLastResult() {
        AggregatedResult servido = servedResult;
        return servido != null ? servido : lastResult;
    }

    /** La cota SQL de aislamiento, o vacía cuando se sirve todo. */
    public java.util.Optional<java.time.Instant> cotaDeLectura() { return cotaDeLectura; }
    public int  getUltimasCategoriasRefinadas()         { return ultimasCategoriasRefinadas; }
    public void setUltimasCategoriasRefinadas(int n)    { ultimasCategoriasRefinadas = n; }
    public ProgressData     getProgressData() { return progressData; }
    public List<SitioExtra> getSitiosExtras() { return Collections.unmodifiableList(sitiosExtras); }
    public void clearLastResult() {
        synchronized (catalogLock) {
            this.lastResult = null;
            // DELETE /api/db/productos. Sin esta línea el lector sigue viendo,
            // hasta que la corrida termine, un catálogo que ya no existe.
            this.servedResult = null;
        }
    }

    /** Saca un producto del catálogo en memoria tras un soft-delete manual en DB
     *  (db.marcarDescontinuado ya puso activo=0; /api/data lee de lastResult, no de
     *  la DB en cada request, así que sin esto el producto seguiría apareciendo
     *  hasta el próximo scrape/restart). */
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
    }

    /** Misma poda sobre la foto servida, si hay corrida abierta. */
    private static AggregatedResult sinProducto(AggregatedResult foto, String url) {
        if (foto == null) return null;
        List<Product> filtrados = foto.productos().stream()
                .filter(p -> !url.equals(p.url()))
                .toList();
        return new AggregatedResult(filtrados, foto.conteoPorSitio(), foto.erroresPorSitio(),
                foto.facets(), foto.minPrecio(), foto.maxPrecio(), foto.statsPorSitio());
    }

    /** Reemplaza la clasificación de un producto en el catálogo en memoria tras
     *  una reclasificación confirmada y ya persistida por
     *  {@code POST /api/agent/apply} (DatabaseService.aplicarReclasificacionAuditada).
     *  Mismo motivo que {@link #eliminarProductoDeMemoria}: {@code /api/data} y
     *  {@code /api/mejores} sirven de {@code lastResult}, no de la DB en cada
     *  request, así que sin esto el cambio no se vería hasta el próximo
     *  scrape/restart.
     *
     *  <p>Un valor nulo o en blanco deja el dato anterior — el endpoint ya aplica
     *  ese mismo fallback al persistir, así que memoria y DB no divergen. A
     *  diferencia del soft-delete, acá las facetas SE RECALCULAN: la
     *  reclasificación mueve al producto entre categorías/marcas y reusar los
     *  contadores viejos dejaría el filtro del catálogo ofreciendo la categoría
     *  que el producto ya no tiene. */
    /**
     * manual-classification-lock Phase 7: {@code rubro} was a pre-existing bug
     * (obs #773/design finding 4) — this method kept {@code p.rubro()}
     * unconditionally, so {@code rubro} diverged from a human-set
     * {@code categoria} the moment {@code POST /api/agent/apply} ran, before
     * any scrape. Now fill-only like every other patched field: a blank
     * incoming {@code rubro} preserves the prior value.
     */
    public void actualizarProductoEnMemoria(String url, String categoria, String marca,
                                            String genero, String subCategoria, String rubro) {
        synchronized (catalogLock) {
            if (lastResult == null || url == null) return;
            lastResult = reclasificado(lastResult, url, categoria, marca, genero, subCategoria, rubro);
            servedResult = reclasificado(servedResult, url, categoria, marca, genero, subCategoria, rubro);
        }
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
        return (nuevo != null && !nuevo.isBlank()) ? nuevo : anterior;
    }

    /**
     * Test seam — replaces the in-memory catalog directly, without going
     * through a scrape/fromDB cycle. Package-visible would suffice but this
     * stays public since {@code ScraperService} has no other test-only hooks
     * convention to mirror (unlike {@code DatabaseService.initEn}, which is
     * package-private because its test lives in the same package).
     */
    public void setLastResultParaTest(AggregatedResult result) { synchronized (catalogLock) { this.lastResult = result; } }

    /**
     * Synchronously re-runs {@link ar.scraper.ml.FinanciacionEnricher} over the
     * currently loaded in-memory catalog and replaces it in place — triggered by
     * preset activate/edit (ADR-5 of financing-buy-signal design). No async
     * machinery: this is cheap O(n) arithmetic (one inflation read + one VP
     * calculation per product), unlike {@code MlEnricher}/{@code PythonRunner}
     * which fork a multi-second Python subprocess.
     *
     * <p>Only the {@code productos} list changes — {@code conteoPorSitio},
     * {@code erroresPorSitio}, {@code facets}, {@code minPrecio}/{@code
     * maxPrecio} are untouched, since the financing signal does not affect
     * sitio counts, errors, or price range.</p>
     *
     * <p>No-op when no catalog is loaded yet (no scrape/restore has happened).</p>
     */
    public void recomputarFinanciacion(ResultAggregator aggregator) {
        synchronized (catalogLock) {
            AggregatedResult actual = this.lastResult;
            if (actual == null) return;

            this.lastResult = refinanciado(actual, aggregator);
            this.servedResult = refinanciado(this.servedResult, aggregator);
        }
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

    // ── Lanzar scraping ─────────────────────────────────────────────────────
    public boolean iniciarScraping(Set<String> sitiosSeleccionados, boolean forceRetrain) {
        if (status.get() == ScraperStatus.RUNNING) return false;
        this.forceRetrain = forceRetrain;
        status.set(ScraperStatus.RUNNING);
        statusMsg.set("Iniciando scrapers...");
        Thread.ofVirtual().start(() -> {
            try { ejecutarScraping(sitiosSeleccionados); }
            catch (Exception e) {
                RUN_LOG.error("[ERROR FATAL] {}", e.getMessage());
                cerrarRun("ERROR", 0);
                status.set(ScraperStatus.ERROR);
                statusMsg.set("Error: " + e.getMessage());
            }
        });
        return true;
    }

    public boolean iniciarScraping(Set<String> sitiosSeleccionados) {
        return iniciarScraping(sitiosSeleccionados, false);
    }

    private void ejecutarScraping(Set<String> sitiosSeleccionados) throws Exception {
        ejecutarScraping(sitiosSeleccionados, null);
    }

    /**
     * @param adoptada cuando no es null, la corrida se RETOMA: no se abre una
     *                 fila nueva y se conserva su {@code started_at}, que es la
     *                 cota del lector y el alcance del barrido final. Abrir una
     *                 corrida nueva haría que las dos nombraran sólo la mitad
     *                 retomada, y el barrido daría por ausente la primera mitad.
     */
    private void ejecutarScraping(Set<String> sitiosSeleccionados, RunState adoptada) throws Exception {
        long runStart = System.currentTimeMillis();
        String ts = LocalDateTime.now().format(TS);

        List<ScraperConfig.SiteConfig> todos = buildSiteList(sitiosSeleccionados);
        int totalSitios = todos.size();

        cancelado.set(false);
        playwrightsVivos.clear();
        if (adoptada != null) {
            adoptarCorrida(adoptada);
            RUN_LOG.info("[RETOMA]  corrida {} retomada con {} sitio(s) pendientes",
                    adoptada.runId(), totalSitios);
        } else {
            abrirRun(todos);
        }

        // Inicializar progreso
        List<SitioProgress> progSitios = Collections.synchronizedList(new ArrayList<>());
        for (var site : todos) {
            progSitios.add(new SitioProgress(site.nombre(), SitioEstado.ESPERANDO, 0, null, 0));
        }
        progressData = new ProgressData(totalSitios, 0, 0, progSitios);

        RUN_LOG.info("════════════════════════════════════════════════════════");
        RUN_LOG.info("[INICIO] {} | Sitios: {} | Precio: ${} - ${}",
                ts, totalSitios, fmt(config.getPrecioMinimo()), fmt(config.getPrecioMaximo()));
        RUN_LOG.info("────────────────────────────────────────────────────────");

        int threads = Math.min(config.getThreadsParalelos(), totalSitios);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        ExecutorCompletionService<ScrapeResult> ecs = new ExecutorCompletionService<>(exec);

        // Mapa nombre → índice para actualizar progreso
        Map<String, Integer> idxMap = new LinkedHashMap<>();
        for (int i = 0; i < todos.size(); i++) {
            String nombre = todos.get(i).nombre();
            idxMap.put(nombre, i);

            // Marcar como EN_CURSO al lanzar
            actualizarProgreso(progSitios, i, SitioEstado.EN_CURSO, 0, null, 0);
            registrarSitioEnCurso(nombre);
            progressData = new ProgressData(totalSitios, 0, 0, List.copyOf(progSitios));

            final var site = todos.get(i);
            RUN_LOG.info("[INICIO]  {} scrapeando...", String.format("%-15s", site.nombre()));
            ecs.submit(() -> {
                try {
                    return withRetry(() -> {
                        // Registrado ANTES de usarse y sacado en el finally: si
                        // cancelar llega en el medio, tiene a quién cerrarle.
                        Playwright pw = Playwright.create();
                        playwrightsVivos.add(pw);
                        try {
                            BaseScraper scraper = ScraperFactory.crear(config, site, db.siteRegistry());
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
        List<ScrapeResult> resultados = new ArrayList<>();
        AtomicInteger completados = new AtomicInteger(0);
        AtomicInteger productosAcumulados = new AtomicInteger(0);

        for (int i = 0; i < totalSitios; i++) {
            long remaining = (deadline - System.currentTimeMillis()) / 1000;
            long wait = Math.min(TIMEOUT_POR_SITIO_S, remaining);

            if (wait <= 0) {
                // Timeout global — marcar todos los pendientes
                for (SitioProgress sp : progSitios) {
                    if (sp.estado() == SitioEstado.EN_CURSO || sp.estado() == SitioEstado.ESPERANDO) {
                        int idx = idxMap.getOrDefault(sp.nombre(), -1);
                        if (idx >= 0) actualizarProgreso(progSitios, idx, SitioEstado.ERROR, 0, "Deadline", 0);
                        resultados.add(new ScrapeResult(sp.nombre(), List.of(), "Deadline global", 0));
                        RUN_LOG.warn("[SITIO]   {} →    0 productos  (deadline global)",
                                String.format("%-15s", sp.nombre()));
                    }
                }
                break;
            }

            try {
                long deadlineSitio = System.currentTimeMillis() + wait * 1000L;
                Future<ScrapeResult> f =
                        esperarResultado(ecs, deadlineSitio, POLL_GRANULARIDAD_MS, cancelado);

                if (cancelado.get()) {
                    RUN_LOG.warn("[CANCEL]  Cancelación pedida — se deja de esperar sitios");
                    break;
                }
                if (f == null) {
                    // Gracia de 2s, igual que antes: un sitio puede terminar justo
                    // sobre el vencimiento del presupuesto.
                    f = ecs.poll(2, TimeUnit.SECONDS);
                    if (f == null) {
                        RUN_LOG.warn("[ESPERA]  Sin respuesta en {}s, continuando...", wait);
                        continue;
                    }
                }

                ScrapeResult r = f.get();
                resultados.add(r);

                int n = r.productos().size();
                boolean tieneError = r.error() != null && !r.error().isBlank();
                SitioEstado estado = (tieneError && n == 0) ? SitioEstado.ERROR : SitioEstado.DONE;

                int idx = idxMap.getOrDefault(r.sitio(), -1);
                if (idx >= 0) actualizarProgreso(progSitios, idx, estado, n, r.error(), r.duracionMs());
                registrarSitioTerminado(r.sitio(), estado == SitioEstado.ERROR ? "ERROR" : "DONE",
                        n, r.error());

                int comp = completados.incrementAndGet();
                int prods = productosAcumulados.addAndGet(n);
                progressData = new ProgressData(totalSitios, comp, prods, List.copyOf(progSitios));
                statusMsg.set(comp + "/" + totalSitios + " sitios — " + prods + " productos (en curso)");
                logSitioResult(r);

                // ── Actualización progresiva ──────────────────────────────
                // upsertParcial NO hace soft-delete → todos los sitios acumulan
                if (!r.productos().isEmpty()) {
                    try {
                        var normalizados = aggregator.normalizarSolo(r.productos());
                        db.upsertParcial(normalizados);
                        var todosActuales = db.cargarProductos();
                        if (!todosActuales.isEmpty()) {
                            // Solo este sitio pudo cambiar algo, así que solo sus URLs
                            // necesitan re-enriquecerse. Con fromDB completo, cada sitio
                            // que terminaba volvía a cargar el historial de precios del
                            // catálogo entero: 23 barridos completos por corrida.
                            Set<String> urlsDelSitio = normalizados.stream()
                                    .map(Product::url)
                                    .filter(u -> u != null && !u.isBlank())
                                    .collect(Collectors.toSet());
                            synchronized (catalogLock) {
                                lastResult = aggregator.fromDBParcial(todosActuales, lastResult, urlsDelSitio);
                            }
                            LOG.debug("[PARCIAL] {} → {} productos totales",
                                    r.sitio(), todosActuales.size());
                        }
                    } catch (Exception ex) {
                        LOG.warn("[PARCIAL] Error: {}", ex.getMessage());
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrumpido esperando sitio {}", i);
            } catch (Exception e) {
                LOG.warn("Error en completionService ciclo {}: {}", i, e.getMessage());
            }
        }
        exec.shutdownNow();

        if (cancelado.get()) {
            // `aggregator.agregar` NO corre, y eso es el punto entero. Adentro
            // vive el soft-delete, que da por ausente todo lo que no vino en
            // ESTA tanda de resultados — y una corrida cancelada tiene, por
            // definición, sitios que nunca llegaron a hablar. Agregar acá
            // desactivaría el catálogo de todos ellos. Cancelar deja el catálogo
            // exactamente como estaba, que es lo que alguien espera al cancelar.
            cerrarPlaywrightsHuerfanos();
            cerrarRun("CANCELLED", 0);
            status.set(ScraperStatus.DONE);
            statusMsg.set("Cancelado — el catálogo quedó como estaba");
            RUN_LOG.warn("[CANCEL]  Corrida cancelada: no se agregó ni se hizo soft-delete.");
            RUN_LOG.info("════════════════════════════════════════════════════════");
            return;
        }

        // ── Agregación ───────────────────────────────────────────────────────
        statusMsg.set("Procesando y agregando resultados...");
        // Baseline for the yield guard, captured before aggregation overwrites
        // it. No query needed: cargarDesdeBD() rebuilds this from the database
        // on startup, so it survives restarts.
        Map<String, Integer> conteoPrevio = lastResult != null
                ? lastResult.conteoPorSitio() : Map.of();
        Set<String> sitiosDeEstaCorrida = resultados.stream()
                .map(ScrapeResult::sitio).collect(Collectors.toSet());

        // El soft-delete se acota al started_at de la corrida, no a este batch:
        // un resume trae sólo la mitad reanudada (design D4). Sin corrida
        // persistida el alcance vuelve a derivarse del batch, como antes.
        RunState corrida = runState.get();
        java.time.Instant arranqueDeLaCorrida = corrida != null ? corrida.startedAt() : null;

        synchronized (catalogLock) {
            lastResult = aggregator.agregar(resultados, forceRetrain, arranqueDeLaCorrida);
        }

        // ── Guardia de rendimiento por sitio ─────────────────────────────────
        // A broken scraper returns an empty or truncated list without throwing,
        // so the run reports success either way. Compare each site against its
        // own previous yield and surface the collapse.
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
            for (SiteYieldGuard.Alerta a : alertas) {
                LOG.warn("[SALUD] {}", a.mensaje());
                RUN_LOG.warn("[SALUD]   {}", a.mensaje());
            }
        }
        cerrarRun("COMPLETED", lastResult != null ? lastResult.productos().size() : 0);

        statusMsg.set("Entrenando modelo ML en background...");
        ultimasCategoriasRefinadas = aggregator.getLastCatRefinadas();
        long durMs = System.currentTimeMillis() - runStart;

        long conFoto = lastResult.productos().stream()
                .filter(p -> p.imagenUrl() != null && !p.imagenUrl().isBlank()).count();
        long sinFoto = lastResult.productos().size() - conFoto;

        RUN_LOG.info("────────────────────────────────────────────────────────");
        RUN_LOG.info("[FIN]     Productos: {} únicos  |  Con foto: {}  Sin foto: {}  |  Duración: {}",
                lastResult.productos().size(), conFoto, sinFoto, formatDuracion(durMs));

        List<String> vacios = resultados.stream()
                .filter(r -> r.productos().isEmpty() && (r.error() == null || r.error().isBlank()))
                .map(ScrapeResult::sitio).toList();
        if (!vacios.isEmpty())
            RUN_LOG.info("[AVISO]   Sitios sin productos: {}", String.join(", ", vacios));

        List<String> conError = resultados.stream()
                .filter(r -> r.error() != null && !r.error().isBlank())
                .map(r -> r.sitio() + " (" + truncar(r.error(), 50) + ")").toList();
        if (!conError.isEmpty())
            RUN_LOG.info("[ERRORES] {}", String.join(" | ", conError));

        RUN_LOG.info("════════════════════════════════════════════════════════");

        status.set(ScraperStatus.DONE);
        statusMsg.set("Completado: " + lastResult.productos().size() + " productos");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void actualizarProgreso(List<SitioProgress> lista, int idx,
                                    SitioEstado estado, int n, String error, long ms) {
        if (idx < 0 || idx >= lista.size()) return;
        SitioProgress old = lista.get(idx);
        lista.set(idx, new SitioProgress(old.nombre(), estado, n, error, ms));
    }

    /**
     * La corrida que un proceso muerto dejó abierta, detectada al arrancar.
     *
     * <p>Es una BANDERA, no un disparador. Detectar no reanuda: un reinicio que
     * retomara trabajo solo sería una falla peor que la caída que está
     * atendiendo — nadie pidió ese scrape, y arrancaría browsers en un servidor
     * que quizá se reinició justo para dejar de hacerlo.</p>
     */
    private final java.util.concurrent.atomic.AtomicReference<
            ar.scraper.db.CorridaInterrumpida> interrumpida =
            new java.util.concurrent.atomic.AtomicReference<>();

    public ar.scraper.db.CorridaInterrumpida getInterrumpida() {
        return interrumpida.get();
    }

    /**
     * Retoma la corrida interrumpida: sólo los sitios que faltan.
     *
     * @return false si no hay nada que retomar o ya hay un scrape corriendo
     */
    public boolean reanudar() {
        var det = interrumpida.get();
        if (det == null) return false;
        if (status.get() == ScraperStatus.RUNNING) return false;

        try {
            // Un sitio puede haber salido del registro entre la caída y el
            // reinicio. Se marca SKIPPED y se NOMBRA: desaparecer en silencio de
            // una corrida que lo debía es peor que no retomarlo.
            List<String> nombresActuales = buildSiteList(null).stream()
                    .map(ScraperConfig.SiteConfig::nombre).toList();
            db.marcarSitiosAusentesDelRegistro(det.runId(), nombresActuales);

            var actualizada = db.ultimaCorridaInterrumpida().orElse(det);
            db.reabrirScrapeRun(det.runId());
            interrumpida.set(null);

            RunState adoptada = new RunState(det.runId(), det.uuid(), det.startedAt());
            status.set(ScraperStatus.RUNNING);
            cancelado.set(false);
            playwrightsVivos.clear();

            if (actualizada.pendientes().isEmpty()) {
                statusMsg.set("Retomando: sólo la pasada final");
                Thread.ofVirtual().start(() -> soloPasadaFinal(adoptada));
            } else {
                statusMsg.set("Retomando " + actualizada.pendientes().size() + " sitio(s)...");
                Set<String> pendientes = new HashSet<>(actualizada.pendientes());
                Thread.ofVirtual().start(() -> {
                    try { ejecutarScraping(pendientes, adoptada); }
                    catch (Exception e) {
                        RUN_LOG.error("[ERROR FATAL] al retomar: {}", e.getMessage());
                        cerrarRun("ERROR", 0);
                        status.set(ScraperStatus.ERROR);
                        statusMsg.set("Error al retomar: " + e.getMessage());
                    }
                });
            }
            return true;
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo retomar la corrida {}: {}", det.runId(), e.getMessage());
            status.set(ScraperStatus.ERROR);
            statusMsg.set("No se pudo retomar: " + e.getMessage());
            return false;
        }
    }

    /**
     * El caso que se olvida: la caída fue DESPUÉS de que todos los sitios
     * terminaron, durante la pasada de ML/agregación. Re-scrapear acá es trabajo
     * puro perdido — lo único que quedó debiendo es el barrido final.
     *
     * <p>Se llama a {@code upsertProductos} con lista VACÍA y el
     * {@code started_at} de la corrida: con una cota presente el alcance del
     * barrido se deriva de la base ({@code touched_at >= started_at}), no del
     * batch, así que una lista vacía barre exactamente lo que la corrida vio en
     * sus dos mitades. Y se reconstruye {@code lastResult} desde la base en vez
     * de dejar que {@code agregar} lo arme con un batch vacío, que lo dejaría
     * VACÍO — o sea, borraría el catálogo en memoria.</p>
     *
     * <p><b>Lo que esto NO hace</b>: no vuelve a correr el pipeline de ML. Esa
     * mitad se recupera sola en la próxima corrida normal. Lo que no se puede
     * postergar es el barrido: sin él, los productos ausentes quedan activos
     * para siempre.</p>
     */
    private void soloPasadaFinal(RunState corrida) {
        try {
            adoptarCorrida(corrida);
            statusMsg.set("Barrido final de la corrida retomada...");
            db.upsertProductos(List.of(), corrida.startedAt());

            List<ar.scraper.model.Product> prods = db.cargarProductos();
            synchronized (catalogLock) { lastResult = aggregator.fromDB(prods); }

            cerrarRun("COMPLETED", prods.size());
            status.set(ScraperStatus.DONE);
            statusMsg.set("Corrida retomada y cerrada: " + prods.size() + " productos");
            RUN_LOG.info("[RETOMA]  pasada final completada, {} productos", prods.size());
        } catch (Exception e) {
            RUN_LOG.error("[ERROR FATAL] en la pasada final: {}", e.getMessage());
            cerrarRun("ERROR", 0);
            status.set(ScraperStatus.ERROR);
            statusMsg.set("Error en la pasada final: " + e.getMessage());
        }
    }

    /**
     * Asks the running scrape to stop. Idempotent; a no-op when nothing runs.
     *
     * @return false when there was nothing to cancel
     */
    public boolean cancelar() {
        if (status.get() != ScraperStatus.RUNNING) return false;
        cancelado.set(true);
        statusMsg.set("Cancelando...");
        RUN_LOG.warn("[CANCEL]  Cancelación pedida por el usuario");
        return true;
    }

    public boolean estaCancelado() { return cancelado.get(); }

    /**
     * Closes whatever browsers outlived the executor.
     *
     * <p>The count is logged rather than assumed: this is the one place that can
     * tell us whether the interrupt-based teardown ever starts working, and a
     * silent close would hide both the leak and its eventual fix.</p>
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

    // ── Bookkeeping de la corrida (V29) ─────────────────────────────────────
    //
    // Las cuatro tragan su excepción a propósito. Registrar una corrida es
    // contabilidad: que la contabilidad falle no puede abortar un scrape que
    // por lo demás anda. La consecuencia se acepta explícitamente — si `abrirRun`
    // falla, `runState` queda en null y las otras tres no hacen nada, así que
    // esa corrida no queda registrada en vez de quedar registrada a medias.
    // Media fila es peor que ninguna: la detección de interrumpidos la leería
    // como una corrida viva que nadie va a cerrar nunca.

    void abrirRun(List<ScraperConfig.SiteConfig> sitios) {
        try {
            java.util.UUID uuid = java.util.UUID.randomUUID();
            java.time.Instant arranque = java.time.Instant.now();
            List<String> nombres = sitios.stream().map(ScraperConfig.SiteConfig::nombre).toList();
            long runId = db.crearScrapeRun(uuid, arranque, null, null, nombres);
            // El started_at que vale es el que quedó EN LA BASE, no el que mandamos:
            // el repositorio lo trunca al segundo para que la cota de aislamiento
            // case con la resolución de `touched_at`. Leerlo de vuelta evita que
            // este objeto y la fila digan cosas distintas.
            java.time.Instant persistido = db.startedAtDeRun(runId).orElse(arranque);
            adoptarCorrida(new RunState(runId, uuid, persistido));
            LOG.info("[RUN] corrida {} abierta con {} sitios", runId, nombres.size());
        } catch (Exception e) {
            runState.set(null);
            liberarLectores();
            LOG.warn("[RUN] no se pudo abrir la corrida, sigue sin registro: {}", e.getMessage());
        }
    }

    /**
     * El ÚNICO lugar donde una corrida pasa a ser la corrida en curso.
     *
     * <p>Son tres los caminos que abren una: la normal, la retomada, y la que
     * sólo debe el barrido final. Entran los tres por acá porque de la apertura
     * cuelga el aislamiento del lector, y tres {@code runState.set()} sueltos
     * dejarían a dos de ellos sirviendo un catálogo a medio rearmar — justo en
     * el escenario donde más importa, porque una retoma corre sobre un catálogo
     * que ya quedó a medias.</p>
     *
     * <p>Adoptar la corrida y aislar al lector son <b>una sola operación</b>, no
     * dos que hay que acordarse de llamar juntas.</p>
     */
    private void adoptarCorrida(RunState corrida) {
        runState.set(corrida);
        aislarLectores(corrida.startedAt());
    }

    /**
     * Congela lo que se sirve y arma la cota SQL, las dos mitades del mismo
     * aislamiento (slice 4).
     *
     * <p>La cota se suprime hasta que exista una corrida COMPLETED: puesta antes
     * de eso, nada cumple {@code touched_at < started_at} y la primera corrida de
     * una instalación nueva sirve una pantalla vacía. La foto en memoria no
     * necesita ese guard porque degrada sola — sin catálogo previo queda null y
     * el lector cae al vivo, que es justamente ver el progreso.</p>
     */
    private void aislarLectores(java.time.Instant arranque) {
        boolean hayCorridaCompletada;
        try {
            hayCorridaCompletada = db.existeCorridaCompletada();
        } catch (Exception e) {
            // Sin respuesta no se aísla: servir de más es recuperable, servir una
            // pantalla vacía por un error de contabilidad no.
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
    }

    private void liberarLectores() {
        synchronized (catalogLock) {
            servedResult = null;
            cotaDeLectura = java.util.Optional.empty();
        }
    }

    private void registrarSitioEnCurso(String sitio) {
        RunState estado = runState.get();
        if (estado == null) return;
        try {
            db.marcarSitioEnCurso(estado.runId(), sitio, java.time.Instant.now());
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo marcar '{}' en curso: {}", sitio, e.getMessage());
        }
    }

    private void registrarSitioTerminado(String sitio, String status, int productos, String error) {
        RunState estado = runState.get();
        if (estado == null) return;
        try {
            db.marcarSitioTerminado(estado.runId(), sitio, status, productos, error,
                    java.time.Instant.now());
        } catch (Exception e) {
            LOG.warn("[RUN] no se pudo cerrar '{}': {}", sitio, e.getMessage());
        }
    }

    void cerrarRun(String status, int productos) {
        RunState estado = runState.getAndSet(null);
        // Antes del early-return: si la contabilidad falló a mitad, el aislamiento
        // igual tiene que soltarse o el lector queda congelado para siempre.
        liberarLectores();
        if (estado == null) return;
        try {
            db.finalizarScrapeRun(estado.runId(), status, productos, java.time.Instant.now());
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
        boolean err = r.error() != null && !r.error().isBlank();
        long conFoto = r.productos().stream()
                .filter(p -> p.imagenUrl() != null && !p.imagenUrl().isBlank()).count();
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

    // ── Retry ────────────────────────────────────────────────────────────────

    /**
     * Executes {@code task} up to {@code maxAttempts} times, sleeping
     * {@code baseDelayMs * attemptNumber} milliseconds between failures.
     * Re-throws {@link InterruptedException} immediately to preserve thread
     * interrupt semantics. On exhaustion returns a {@link ScrapeResult} with
     * an empty products list and the last exception message as the error field.
     *
     * <p>Package-private so {@code ScraperServiceRetryTest} (same package) can
     * call it directly without exposing it as a public API.</p>
     */
    /**
     * Waits for one site result, in short hops against an accumulated deadline.
     *
     * <p>The budget is unchanged — still per-site, still
     * {@code min(TIMEOUT_POR_SITIO_S, global remaining)}. What changes is that
     * the wait is no longer <b>one</b> blocking {@code poll} of up to ten
     * minutes, so a cancellation flag is seen within a poll window instead of
     * whenever the current site happens to finish.</p>
     *
     * <p><b>Do not "simplify" this back into a single short poll.</b> An empty
     * poll returning to the caller makes it {@code continue}, and that
     * {@code continue} advances the outer per-site loop — so every empty poll
     * would spend a site's slot. At five seconds a run exhausts all 26 slots in
     * about 130 seconds and finishes having collected almost nothing while every
     * site is still working. Empty hops must cost nothing; only the deadline
     * ends the wait. {@code ScraperServicePollGranularityTest} fixes this.</p>
     *
     * @return the completed site, or {@code null} if the budget ran out or the
     *         run was cancelled — the caller distinguishes them by the flag.
     */
    static Future<ScrapeResult> esperarResultado(
            ExecutorCompletionService<ScrapeResult> ecs, long deadlineMs,
            long granularidadMs, java.util.concurrent.atomic.AtomicBoolean cancelado)
            throws InterruptedException {
        while (true) {
            // Checked BEFORE polling, so a cancel arriving between sites is not
            // made to sit through a poll window it did not need to.
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
     * Same, but abandons the retries once the run is cancelled.
     *
     * <p>This overload exists because cancelling closes surviving
     * {@code Playwright} instances from the outside, which makes the blocking
     * call in flight throw — and a retry loop reads a throw as "try again". Each
     * attempt builds a fresh browser, so without this check <b>cancelling would
     * open up to two more browsers per site instead of closing them</b>, and the
     * more sites were in flight the worse it would get.</p>
     *
     * <p>The flag is read in two places on purpose: before the first attempt, so
     * a site whose turn comes after the cancel never launches at all; and after a
     * failure, so a cancellation arriving mid-attempt does not buy a retry.</p>
     */
    static ScrapeResult withRetry(java.util.concurrent.Callable<ScrapeResult> task,
                                  int maxAttempts, long baseDelayMs,
                                  java.util.function.BooleanSupplier cancelado)
            throws InterruptedException {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancelado.getAsBoolean()) {
                return new ScrapeResult("", List.of(), "cancelado", 0);
            }
            try {
                return task.call();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                last = e;
                if (cancelado.getAsBoolean()) break;
                if (attempt < maxAttempts && baseDelayMs > 0) {
                    Thread.sleep(baseDelayMs * attempt);
                }
            }
        }
        return new ScrapeResult("", List.of(),
                last != null ? last.getMessage() : "retry exhausted", 0);
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

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
