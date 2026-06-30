package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.db.DatabaseService;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.scrapers.BaseScraper;
import ar.scraper.scrapers.ScraperFactory;
import ar.scraper.scrapers.ShopifyScraper;
import ar.scraper.scrapers.VtexScraper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
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
                    lastResult.minPrecio(), lastResult.maxPrecio());
        }
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
                    actual.facets(), actual.minPrecio(), actual.maxPrecio());
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
            progressData = new ProgressData(totalSitios, 0, 0, List.copyOf(progSitios));

            final var site = todos.get(i);
            RUN_LOG.info("[INICIO]  {} scrapeando...", String.format("%-15s", site.nombre()));
            ecs.submit(() -> {
                try {
                    return withRetry(() -> {
                        try (Playwright pw = Playwright.create()) {
                            BaseScraper scraper = ScraperFactory.crear(config, site);
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
                            synchronized (catalogLock) { lastResult = aggregator.fromDB(todosActuales); }
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
        synchronized (catalogLock) { lastResult = aggregator.agregar(resultados, forceRetrain); }
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

    // ── Rescrape de favoritos (Fase 1: Shopify + VTEX Legacy) ────────────────

    /**
     * Lanza un rescrape dirigido de los productos favoritos.
     * Comparte el lock de {@link ScraperStatus} con el scraping completo:
     * si hay un scraping RUNNING, no inicia (ADR-1).
     */
    public boolean rescrapearFavoritos() {
        if (status.get() == ScraperStatus.RUNNING) return false;
        status.set(ScraperStatus.RUNNING);
        statusMsg.set("Actualizando favoritos...");
        Thread.ofVirtual().start(() -> {
            try { ejecutarRescrapeFavoritos(); }
            catch (Exception e) {
                LOG.error("[FAVORITOS] Error: {}", e.getMessage(), e);
                status.set(ScraperStatus.ERROR);
                statusMsg.set("Error favoritos: " + e.getMessage());
            }
        });
        return true;
    }

    private void ejecutarRescrapeFavoritos() throws Exception {
        var favs = db.listarFavoritos();

        Map<String, List<String>> porSitio = favs.stream()
                .collect(Collectors.groupingBy(
                        f -> f.get("sitio"),
                        LinkedHashMap::new,
                        Collectors.mapping(f -> f.get("url"), Collectors.toList())));

        int totalSitios = porSitio.size();
        List<SitioProgress> progSitios = Collections.synchronizedList(new ArrayList<>());
        for (String sitio : porSitio.keySet()) {
            progSitios.add(new SitioProgress(sitio, SitioEstado.ESPERANDO, 0, null, 0));
        }
        progressData = new ProgressData(totalSitios, 0, 0, List.copyOf(progSitios));

        int idx = 0;
        int completados = 0;
        for (var entry : porSitio.entrySet()) {
            String sitio = entry.getKey();
            List<String> urls = entry.getValue();
            actualizarProgreso(progSitios, idx, SitioEstado.EN_CURSO, 0, null, 0);
            progressData = new ProgressData(totalSitios, completados, 0, List.copyOf(progSitios));

            if (urls.isEmpty()) {
                actualizarProgreso(progSitios, idx, SitioEstado.DONE, 0, null, 0);
                completados++;
                progressData = new ProgressData(totalSitios, completados, 0, List.copyOf(progSitios));
                idx++;
                continue;
            }

            String productUrl = urls.get(0);
            BaseScraper sc = ScraperFactory.crearParaFavorito(config, sitio, productUrl);

            if (sc == null) {
                LOG.info("[FAVORITOS] favoritos rescrape no soportado para {} (Fase 2)", sitio);
                actualizarProgreso(progSitios, idx, SitioEstado.DONE, 0, null, 0);
                for (String url : urls) db.tocarFavorito(url);
                completados++;
                progressData = new ProgressData(totalSitios, completados, 0, List.copyOf(progSitios));
                idx++;
                continue;
            }

            int okCount = rescrapearSitio(sc, urls);
            actualizarProgreso(progSitios, idx, SitioEstado.DONE, okCount, null, 0);
            completados++;
            progressData = new ProgressData(totalSitios, completados, okCount, List.copyOf(progSitios));
            idx++;
        }

        var todos = db.cargarProductos();
        if (!todos.isEmpty()) {
            synchronized (catalogLock) { lastResult = aggregator.fromDB(todos); }
        }

        status.set(ScraperStatus.DONE);
        statusMsg.set("Favoritos actualizados");
    }

    /**
     * Abre UN contexto de Playwright para el sitio (ADR-3) y rescrapea cada
     * URL favorita de ese sitio reutilizando la misma Page.
     * Retorna la cantidad de productos resueltos correctamente.
     */
    private int rescrapearSitio(BaseScraper sc, List<String> urls) {
        int ok = 0;
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(config.isHeadless())
                     .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage",
                             "--disable-blink-features=AutomationControlled")));
             BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(1366, 768)
                     .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                     .setLocale("es-AR"));
             Page page = ctx.newPage()) {

            page.addInitScript(BaseScraper.STEALTH_INIT_SCRIPT);
            page.setDefaultTimeout(config.getTimeoutMs());
            page.route("**/*.{woff,woff2,ttf,otf}", r -> r.abort());
            page.route("**/analytics**", r -> r.abort());
            page.route("**/gtag**",       r -> r.abort());
            page.route("**/hotjar**",     r -> r.abort());

            List<Product> okPersist = new ArrayList<>();
            for (String url : urls) {
                Optional<Product> r;
                if (sc instanceof ShopifyScraper s) r = s.scrapeOne(page, url);
                else if (sc instanceof VtexScraper v) r = v.scrapeOne(page, url);
                else r = Optional.empty();

                if (r.isPresent()) {
                    okPersist.add(r.get());
                    ok++;
                } else {
                    db.marcarDescontinuado(url);
                }
                db.tocarFavorito(url);
            }

            if (!okPersist.isEmpty()) {
                var norm = aggregator.normalizarSolo(okPersist);
                db.upsertParcial(norm);
            }
        } catch (Exception e) {
            LOG.warn("[FAVORITOS] Error rescrapeando sitio: {}", e.getMessage());
        }
        return ok;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void actualizarProgreso(List<SitioProgress> lista, int idx,
                                    SitioEstado estado, int n, String error, long ms) {
        if (idx < 0 || idx >= lista.size()) return;
        SitioProgress old = lista.get(idx);
        lista.set(idx, new SitioProgress(old.nombre(), estado, n, error, ms));
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
                    p.precioOriginal() != null ? p.precioOriginal() : "",
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
