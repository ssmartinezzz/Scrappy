package ar.scraper.db;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.catalog.CategoriaStats;
import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.classification.SitiosPort;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.catalog.CatalogResumen;
import ar.scraper.catalog.ClasificacionBloqueada;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.catalog.ProductPort;
import ar.scraper.catalog.UpsertStats;
import ar.scraper.favoritos.FavoritosPort;
import ar.scraper.catalog.PreciosExternosPort;
import ar.scraper.feedback.FeedbackPort;
import ar.scraper.feedback.OutfitItemRow;
import ar.scraper.outfits.SavedOutfitsPort;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PcPick;
import ar.scraper.pcs.PreferenciaArmadorPort;
import ar.scraper.pcs.SavedPcsPort;
import ar.scraper.financiacion.Preset;
import ar.scraper.financiacion.PresetPort;
import ar.scraper.scheduling.CronExecution;
import ar.scraper.scheduling.CronJob;
import ar.scraper.scheduling.CronPort;
import ar.scraper.scrape.CorridaInterrumpida;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * Postgres MVCC permite lectores y escritor conviviendo sin el lock-dance que SQLite necesitaba.
 */
@Service
public class DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseService.class);

    private final DataSource dataSource;

    private final CronPort cronPort;
    private final PresetPort presetPort;
    private final FavoritosPort favoritosPort;
    private final FeedbackPort feedbackPort;
    private final SavedOutfitsPort savedOutfitsPort;
    private final SavedPcsPort savedPcsPort;
    private final PreferenciaArmadorPort preferenciaArmadorPort;
    private final MlOutputPort mlOutputPort;
    private final HistorialPort historialPort;
    private final SitiosPort sitiosPort;
    private final CategoriaStatsPort categoriaStatsPort;
    private final PreciosExternosPort preciosExternosPort;
    private final ProductPort productPort;
    private final CatalogQueryPort catalogQueryPort;
    private final ScrapeRunPort scrapeRunPort;
    private final SiteRegistry siteRegistry;
    private final RubroResolver rubroResolver;

    public DatabaseService(DataSource dataSource, SiteRegistry siteRegistry, RubroResolver rubroResolver,
            CronPort cronPort,
            FavoritosPort favoritosPort, PresetPort presetPort, HistorialPort historialPort,
            CatalogQueryPort catalogQueryPort, ProductPort productPort,
            CategoriaStatsPort categoriaStatsPort, MlOutputPort mlOutputPort,
            ScrapeRunPort scrapeRunPort, SitiosPort sitiosPort,
            FeedbackPort feedbackPort, SavedOutfitsPort savedOutfitsPort,
            SavedPcsPort savedPcsPort,
            PreferenciaArmadorPort preferenciaArmadorPort,
            PreciosExternosPort preciosExternosPort) {
        this.dataSource = dataSource;
        this.siteRegistry = siteRegistry;
        this.rubroResolver = rubroResolver;
        this.cronPort = cronPort;
        this.favoritosPort = favoritosPort;
        this.presetPort = presetPort;
        this.historialPort = historialPort;
        this.catalogQueryPort = catalogQueryPort;
        this.productPort = productPort;
        this.feedbackPort = feedbackPort;
        this.savedOutfitsPort = savedOutfitsPort;
        this.savedPcsPort = savedPcsPort;
        this.preferenciaArmadorPort = preferenciaArmadorPort;
        this.mlOutputPort = mlOutputPort;
        this.sitiosPort = sitiosPort;
        this.categoriaStatsPort = categoriaStatsPort;
        this.preciosExternosPort = preciosExternosPort;
        this.scrapeRunPort = scrapeRunPort;
    }

    public SiteRegistry siteRegistry() {
        return siteRegistry;
    }

    public RubroResolver rubroResolver() {
        return rubroResolver;
    }

    public FavoritosPort favoritos() {
        return favoritosPort;
    }

    public PresetPort presets() {
        return presetPort;
    }

    public HistorialPort historial() {
        return historialPort;
    }

    public ScrapeRunPort scrapeRun() {
        return scrapeRunPort;
    }

    public SitiosPort sitios() {
        return sitiosPort;
    }

    public CategoriaStatsPort categoriaStats() {
        return categoriaStatsPort;
    }

    public MlOutputPort mlOutput() {
        return mlOutputPort;
    }

    public CatalogQueryPort catalogQuery() {
        return catalogQueryPort;
    }

    public ProductPort productos() {
        return productPort;
    }

    public FeedbackPort feedback() {
        return feedbackPort;
    }

    public SavedOutfitsPort outfitsGuardados() {
        return savedOutfitsPort;
    }

    public SavedPcsPort pcsGuardadas() {
        return savedPcsPort;
    }

    public PreferenciaArmadorPort preferenciaArmador() {
        return preferenciaArmadorPort;
    }

    public PreciosExternosPort preciosExternos() {
        return preciosExternosPort;
    }

    @PostConstruct
    void init() {
        try {
            presetPort.seedPresetIlustrativoSiVacio();
            LOG.info("[DB] Conectado (pool HikariCP sobre {})", safeDescribeDataSource());
        } catch (Exception e) {
            LOG.error("[DB] Error en el seed inicial: {}", e.getMessage(), e);
        }
    }

    private String safeDescribeDataSource() {
        try {
            return dataSource.getClass().getSimpleName();
        } catch (Exception e) {
            return "DataSource";
        }
    }

    /**
     * Cuenta las filas cacheadas en {@code image_embeddings} — respalda {@code embeddingsCount} en
     * {@code GET /api/ml/estado} para reportar cobertura del índice visual frente al total de
     * productos activos.
     */
    /**
     * Cuenta las filas cacheadas en {@code image_embeddings} — respalda {@code embeddingsCount} en
     * {@code GET /api/ml/estado}.
     */
    public long contarEmbeddings() {
        return productPort.contarEmbeddings();
    }

    public List<Preset> listarPresets() {
        return presetPort.listarPresets();
    }

    public Optional<Preset> cargarPresetActivo() {
        return presetPort.cargarPresetActivo();
    }

    public int crearPreset(String label, double recargoPct, int cuotas) {
        return presetPort.crearPreset(label, recargoPct, cuotas);
    }

    public boolean editarPreset(int id, String label, double recargoPct, int cuotas) {
        return presetPort.editarPreset(id, label, recargoPct, cuotas);
    }

    /** Activa el preset {@code id} y desactiva todos los demás, de forma transaccional. */
    public boolean activarPreset(int id) {
        return presetPort.activarPreset(id);
    }

    public boolean eliminarPreset(int id) {
        return presetPort.eliminarPreset(id);
    }

    public UpsertStats upsertProductos(List<Product> productos) {
        return productPort.upsertProductos(productos);
    }

    /**
     * Igual, pero acotando el soft-delete a lo que tocó la corrida en lugar de a este batch. Un
     * resume trae sólo la mitad reanudada, así que el alcance derivado del batch deja de barrer los
     * sitios que cubrió la mitad interrumpida..
     */
    public UpsertStats upsertProductos(List<Product> productos,
                                       ar.scraper.scrape.CorridaEnCurso corrida) {
        return productPort.upsertProductos(productos, corrida);
    }

    /** NUNCA hace soft-delete — solo inserta/actualiza los productos dados. */
    public UpsertStats upsertParcial(List<Product> productos) {
        return productPort.upsertParcial(productos);
    }

    public List<Product> cargarProductos() {
        return productPort.cargarProductos();
    }

    /** Busca un producto por URL sin filtrar por `activo` (incluye descontinuados). */
    public CatalogPage buscarCatalogo(CatalogFilter filtro, String orden, int page, int size) {
        return catalogQueryPort.buscar(filtro, orden, page, size);
    }

    public CatalogPage buscarCatalogo(CatalogFilter filtro, String orden, int page, int size,
                                      java.util.Optional<java.time.Instant> cota) {
        return catalogQueryPort.buscar(filtro, orden, page, size, cota);
    }

    // Delegates rather than a getter: ScrapeRunRepository is package-private, like every other
    // repository here, so `ar.scraper.web` cannot name the type.

    /** Opens a run and enrolls its sites as PENDING, in one transaction. */
    public long crearScrapeRun(java.util.UUID scrapeUuid, java.time.Instant startedAt,
                               java.util.UUID triggeredBy, Long cronJobId,
                               java.util.Collection<String> sitios) {
        return scrapeRunPort.crear(scrapeUuid, startedAt, triggeredBy, cronJobId, sitios);
    }

    public void marcarSitioEnCurso(long runId, String sitio, java.time.Instant cuando) {
        scrapeRunPort.marcarSitioEnCurso(runId, sitio, cuando);
    }

    public void marcarSitioTerminado(long runId, String sitio, String status, int productosCount,
                                     String error, java.time.Instant cuando) {
        scrapeRunPort.marcarSitioTerminado(runId, sitio, status, productosCount, error, cuando);
    }

    public void finalizarScrapeRun(long runId, String status, int productosCount,
                                   java.time.Instant finishedAt) {
        scrapeRunPort.finalizar(runId, status, productosCount, finishedAt);
    }

    public java.util.List<Long> marcarRunsInterrumpidos(java.time.Instant cuando) {
        return scrapeRunPort.marcarInterrumpidosAlArrancar(cuando);
    }

    public java.util.Optional<CorridaInterrumpida> ultimaCorridaInterrumpida() {
        return scrapeRunPort.ultimaInterrumpida();
    }

    public void reabrirScrapeRun(long runId) {
        scrapeRunPort.reabrir(runId);
    }

    public java.util.List<String> marcarSitiosAusentesDelRegistro(
            long runId, java.util.Collection<String> nombresActuales) {
        return scrapeRunPort.marcarAusentesDelRegistro(runId, nombresActuales);
    }

    public java.util.Optional<java.time.Instant> startedAtDeRun(long runId) {
        return scrapeRunPort.startedAtDe(runId);
    }

    /** Whether the reader bound may apply at all — see the repository for why COMPLETED. */
    public boolean existeCorridaCompletada() {
        return scrapeRunPort.existeCorridaCompletada();
    }

    public Facets facetasCatalogo() {
        return catalogQueryPort.facetas();
    }

    public Facets facetasCatalogo(
            java.util.Optional<java.time.Instant> cota) {
        return catalogQueryPort.facetas(cota);
    }

    public CatalogResumen resumenCatalogo() {
        return catalogQueryPort.resumen();
    }

    public CatalogResumen resumenCatalogo(java.util.Optional<java.time.Instant> cota) {
        return catalogQueryPort.resumen(cota);
    }

    public java.util.Optional<Product> obtenerProducto(String url) {
        return productPort.obtenerProducto(url);
    }

    public java.util.Optional<Product> obtenerProductoPorKey(String key) {
        return productPort.obtenerProductoPorKey(key);
    }

    /**
     * Read-side of the manual classification lock. One entry per locked product, keyed by url —
     * {@code ResultAggregator.aplicarBloqueos} reads this ONCE per {@code agregar} call.
     */
    public Map<String, ClasificacionBloqueada> cargarClasificacionBloqueada() {
        return productPort.cargarClasificacionBloqueada();
    }

    public void guardarMlOutput(JsonNode mlOutput) {
        mlOutputPort.guardarMlOutput(mlOutput);
    }

    public JsonNode cargarMlOutput() {
        return mlOutputPort.cargarMlOutput();
    }

    public List<Map<String, Object>> cargarHistorial(String url) {
        return historialPort.cargarHistorial(url);
    }

    public void guardarSitio(String nombre, String url, String plataforma) {
        sitiosPort.guardarSitio(nombre, url, plataforma);
    }

    public void eliminarSitio(String nombre) {
        sitiosPort.eliminarSitio(nombre);
    }

    public List<Map<String, String>> cargarSitiosDinamicos() {
        return sitiosPort.cargarSitiosDinamicos();
    }

    public void guardarCategoriaStats(com.fasterxml.jackson.databind.JsonNode statsNode) {
        categoriaStatsPort.guardarCategoriaStats(statsNode);
    }

    public java.util.Map<String, CategoriaStats> cargarCategoriaStats() {
        return categoriaStatsPort.cargarCategoriaStats();
    }

    public void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        preciosExternosPort.guardarPreciosExternos(productoUrl, sitio, resultados);
    }

    public java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
        return preciosExternosPort.cargarPreciosExternos(productoUrl);
    }

    public void actualizarCategoria(String url, String nuevaCategoria) {
        productPort.actualizarCategoria(url, nuevaCategoria);
    }

    /**
     * Actualiza categoria/marca/genero/talles de un producto ya persistido sin re-scrapear (bulk
     * re-normalización). Camino de MÁQUINA: respeta el lock.
     */
    public int actualizarNormalizacion(String url, String categoria, String marca,
                                        String genero, List<String> talles, String subCategoria) {
        return productPort.actualizarNormalizacion(url, categoria, marca, genero, talles, subCategoria);
    }

    /**
     * Una sola transacción: el UPDATE de clasificación, el UPDATE de rubro/lock y el INSERT de
     * auditoría se confirman juntos o ninguno de los tres.
     */
    public boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
                                                   String genero, List<String> talles, String subCategoria,
                                                   Product previo, String actor) {
        return productPort.aplicarReclasificacionAuditada(
                url, categoria, marca, genero, talles, subCategoria, previo, actor);
    }

    // Cada método toma usuario_id PRIMERO y no existe ninguna variante sin él.

    public void guardarFavorito(UUID usuarioId, String url, String sitio, String nombre) {
        favoritosPort.guardarFavorito(usuarioId, url, sitio, nombre);
    }

    public void eliminarFavorito(UUID usuarioId, String url) {
        favoritosPort.eliminarFavorito(usuarioId, url);
    }

    public List<Map<String, String>> listarFavoritos(UUID usuarioId) {
        return favoritosPort.listarFavoritos(usuarioId);
    }

    public void tocarFavorito(UUID usuarioId, String url) {
        favoritosPort.tocarFavorito(usuarioId, url);
    }

    public void guardarOutfitFeedbackItem(UUID usuarioId, String genero, String slot, String url, boolean liked) {
        guardarOutfitFeedbackItem(usuarioId, genero, slot, url, liked, "gym");
    }

    public void guardarOutfitFeedbackItem(UUID usuarioId, String genero, String slot, String url,
                                          boolean liked, String estilo) {
        feedbackPort.guardarOutfitFeedbackItem(usuarioId, genero, slot, url, liked, estilo);
    }

    public List<OutfitItemRow> obtenerOutfitFeedback(UUID usuarioId) {
        return feedbackPort.obtenerOutfitFeedback(usuarioId);
    }

    /** Idempotente: si la categoria ya está dismissed, no inserta una fila duplicada. */
    public void guardarCategoriaDismiss(UUID usuarioId, String categoria) {
        feedbackPort.guardarCategoriaDismiss(usuarioId, categoria);
    }

    public void limpiarOutfitFeedback(UUID usuarioId) {
        feedbackPort.limpiarOutfitFeedback(usuarioId);
    }

    public void limpiarOutfitFeedback(UUID usuarioId, String estilo) {
        feedbackPort.limpiarOutfitFeedback(usuarioId, estilo);
    }

    /** Safe no-op si no existía. */
    public void borrarCategoriaDismiss(UUID usuarioId, String categoria) {
        feedbackPort.borrarCategoriaDismiss(usuarioId, categoria);
    }

    public Set<String> obtenerCategoriaDismiss(UUID usuarioId) {
        return feedbackPort.obtenerCategoriaDismiss(usuarioId);
    }

    public void marcarDescontinuado(String url) {
        productPort.marcarDescontinuado(url);
    }

    /**
     * {@code ResultAggregator.renormalizarCatalogo} snapshots
     * {@link #cargarClasificacionBloqueada()} once at method entry for the common case, but that
     * snapshot goes stale the moment a product is locked via {@code POST /api/agent/apply} mid-run
     * — the loop makes one sequential round-trip per changed product across the whole catalog, a
     * realistic window.
     */
    public boolean estaBloqueado(String url) {
        return productPort.estaBloqueado(url);
    }

    public boolean esProductoActivo(String url) {
        return productPort.esProductoActivo(url);
    }

    public List<HistorialEntry> getHistorialPrecios(String url) {
        return historialPort.getHistorialPrecios(url);
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: una sola consulta
     * {@code WHERE url IN (...)}, evitando el patrón N+1 (usado por {@code SenalEnricher} sobre
     * todo el catálogo)..
     */
    public Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls) {
        return historialPort.getHistorialPrecios(urls);
    }

    public void limpiarProductos() {
        productPort.limpiarProductos();
    }

    public void limpiarMlOutput() {
        mlOutputPort.limpiarMlOutput();
    }

    public int guardarOutfit(UUID usuarioId, String nombre, String slotsJson,
                             String suplementosJson, double total) {
        return savedOutfitsPort.guardarOutfit(usuarioId, nombre, slotsJson, suplementosJson, total);
    }

    public List<Map<String, Object>> obtenerOutfitsGuardados(UUID usuarioId) {
        return savedOutfitsPort.obtenerOutfitsGuardados(usuarioId);
    }

    /** Elimina un outfit guardado por id. */
    public boolean eliminarOutfitGuardado(UUID usuarioId, int id) {
        return savedOutfitsPort.eliminarOutfitGuardado(usuarioId, id);
    }

    /** Renombra un outfit guardado. */
    public boolean renombrarOutfit(UUID usuarioId, int id, String nombre) {
        return savedOutfitsPort.renombrarOutfit(usuarioId, id, nombre);
    }

    public int guardarPc(UUID usuarioId, String nombre, List<PcPick> picks, double presupuesto,
                         boolean conGpu, double totalEstimado, Gama gama) {
        return savedPcsPort.guardarPc(usuarioId, nombre, picks, presupuesto, conGpu, totalEstimado, gama);
    }

    public List<Map<String, Object>> obtenerPcsGuardadas(UUID usuarioId) {
        return savedPcsPort.obtenerPcsGuardadas(usuarioId);
    }

    /** Elimina un PC guardado por id. */
    public boolean eliminarPcGuardada(UUID usuarioId, int id) {
        return savedPcsPort.eliminarPcGuardada(usuarioId, id);
    }

    /** Renombra un PC guardado. */
    public boolean renombrarPc(UUID usuarioId, int id, String nombre) {
        return savedPcsPort.renombrarPc(usuarioId, id, nombre);
    }

    public long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        return cronPort.insertCronJob(name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled, nextRunAt);
    }

    public boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        return cronPort.updateCronJob(id, name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled, nextRunAt);
    }

    public boolean deleteCronJob(long id) {
        return cronPort.deleteCronJob(id);
    }

    public List<CronJob> listCronJobs() {
        return cronPort.listCronJobs();
    }

    public Optional<CronJob> getCronJob(long id) {
        return cronPort.getCronJob(id);
    }

    /**
     * Actualiza SOLO {@code last_run_at} — usado por {@code CronJobRunner} al disparar/skippear un
     * run.
     */
    public boolean touchLastRunAt(long jobId, String lastRunAt) {
        return cronPort.touchLastRunAt(jobId, lastRunAt);
    }

    /**
     * Actualiza SOLO {@code next_run_at} — usado por {@code CronSchedulerService} tras cada poll.
     */
    public boolean updateNextRunAt(long jobId, String nextRunAt) {
        return cronPort.updateNextRunAt(jobId, nextRunAt);
    }

    public long insertCronExecution(long jobId, String startedAt, String status, String skippedReason) {
        return cronPort.insertCronExecution(jobId, startedAt, status, skippedReason);
    }

    public boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs) {
        return cronPort.updateCronExecution(execId, finishedAt, status,
                skippedReason, logOutput, durationMs);
    }

    public List<CronExecution> listExecutions(long jobId, int limit) {
        return cronPort.listExecutions(jobId, limit);
    }

    public Optional<CronExecution> getExecution(long execId) {
        return cronPort.getExecution(execId);
    }

    /** Retiene solo las últimas {@code keep} ejecuciones por job (decision 7: 50). */
    public void pruneCronExecutions(long jobId, int keep) {
        cronPort.pruneCronExecutions(jobId, keep);
    }

}
