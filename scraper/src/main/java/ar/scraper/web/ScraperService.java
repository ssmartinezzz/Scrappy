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
    private volatile int ultimasCategoriasRefinadas = 0;
    private volatile boolean forceRetrain = false;

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
    public AggregatedResult getLastResult()            { return lastResult; }
    public int  getUltimasCategoriasRefinadas()         { return ultimasCategoriasRefinadas; }
    public void setUltimasCategoriasRefinadas(int n)    { ultimasCategoriasRefinadas = n; }
    public ProgressData     getProgressData() { return progressData; }
    public List<SitioExtra> getSitiosExtras() { return Collections.unmodifiableList(sitiosExtras); }
    public void             clearLastResult() { synchronized (catalogLock) { this.lastResult = null; } }

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
        }
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
            List<Product> parcheados = lastResult.productos().stream()
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
            lastResult = new AggregatedResult(parcheados, lastResult.conteoPorSitio(),
                    lastResult.erroresPorSitio(), ResultAggregator.calcularFacets(parcheados),
                    lastResult.minPrecio(), lastResult.maxPrecio(),
                    lastResult.statsPorSitio());
        }
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

            List<Product> reenriquecidos = aggregator.financiacionEnricher().enriquecer(actual.productos());
            this.lastResult = new AggregatedResult(
                    reenriquecidos, actual.conteoPorSitio(), actual.erroresPorSitio(),
                    actual.facets(), actual.minPrecio(), actual.maxPrecio(),
                    actual.statsPorSitio());
        }
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
        long runStart = System.currentTimeMillis();
        String ts = LocalDateTime.now().format(TS);

        List<ScraperConfig.SiteConfig> todos = buildSiteList(sitiosSeleccionados);
        int totalSitios = todos.size();

        abrirRun(todos);

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
                        try (Playwright pw = Playwright.create()) {
                            BaseScraper scraper = ScraperFactory.crear(config, site, db.siteRegistry());
                            return scraper.ejecutar(pw);
                        }
                    }, 3, 2000);
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
                Future<ScrapeResult> f = ecs.poll(wait, TimeUnit.SECONDS);
                if (f == null) {
                    // Poll timed out — el sitio puede haber terminado justo ahora
                    // Intentar un poll inmediato antes de abandonar
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

    // ── Bookkeeping de la corrida (V29) ─────────────────────────────────────
    //
    // Las cuatro tragan su excepción a propósito. Registrar una corrida es
    // contabilidad: que la contabilidad falle no puede abortar un scrape que
    // por lo demás anda. La consecuencia se acepta explícitamente — si `abrirRun`
    // falla, `runState` queda en null y las otras tres no hacen nada, así que
    // esa corrida no queda registrada en vez de quedar registrada a medias.
    // Media fila es peor que ninguna: la detección de interrumpidos la leería
    // como una corrida viva que nadie va a cerrar nunca.

    private void abrirRun(List<ScraperConfig.SiteConfig> sitios) {
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
            runState.set(new RunState(runId, uuid, persistido));
            LOG.info("[RUN] corrida {} abierta con {} sitios", runId, nombres.size());
        } catch (Exception e) {
            runState.set(null);
            LOG.warn("[RUN] no se pudo abrir la corrida, sigue sin registro: {}", e.getMessage());
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

    private void cerrarRun(String status, int productos) {
        RunState estado = runState.getAndSet(null);
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
    static ScrapeResult withRetry(java.util.concurrent.Callable<ScrapeResult> task,
                                  int maxAttempts, long baseDelayMs)
            throws InterruptedException {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return task.call();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                last = e;
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
