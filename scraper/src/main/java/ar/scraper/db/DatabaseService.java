package ar.scraper.db;

import ar.scraper.aggregator.normalize.SiteRegistry;
import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

/**
 * PostgreSQL persistence layer (decouple-services-postgres, Batch 1).
 *
 * Conexión: pool HikariCP administrado por Spring Boot ({@code DataSource}
 * inyectado), apuntando a {@code DATABASE_URL} (env-only, design D6). Cada
 * método de este servicio toma prestada una conexión del pool por llamada
 * (try-with-resources) — ya NO existe una conexión única compartida de
 * escritura/lectura ni el {@code writeLock}/{@code readLock} que la
 * serializaban: Postgres MVCC permite lectores y escritor conviviendo sin
 * el lock-dance que SQLite necesitaba (design D1). El schema (15 tablas +
 * funciones {@code sp_upsert_run}/{@code sp_soft_delete_ausentes}) lo
 * administra Flyway ({@code db/migration/V1__baseline.sql}) — reemplaza el
 * bootstrap runtime que existía en {@code initEn()}/{@code crearTablas()}/
 * {@code migrarColumna()} (removidos, design D3).
 *
 * Upsert logic (preservada exactamente, ahora ejecutada server-side vía
 * {@code sp_upsert_run}, design D2):
 *   - Producto nuevo  → INSERT + historial
 *   - Precio igual    → UPDATE touched_at solamente
 *   - Precio cambió   → UPDATE precio + INSERT en precio_historico
 *   - No apareció     → soft-delete (activo=false) vía {@code sp_soft_delete_ausentes}
 */
@Service
public class DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseService.class);

    private final DataSource dataSource;

    /**
     * Per-aggregate repository holding the {@code cron_jobs}/{@code cron_executions}
     * bodies (backlog A3). Built here rather than injected so this constructor's
     * shape (DataSource only) stays unchanged for the existing test call sites.
     */
    private final CronRepository cronRepository;
    private final PresetRepository presetRepository;
    private final FavoritosRepository favoritosRepository;
    private final FeedbackRepository feedbackRepository;
    private final SavedOutfitsRepository savedOutfitsRepository;
    private final MlOutputRepository mlOutputRepository;
    private final HistorialRepository historialRepository;
    private final SitiosRepository sitiosRepository;
    private final CategoriaStatsRepository categoriaStatsRepository;
    private final PreciosExternosRepository preciosExternosRepository;
    private final ProductRepository productRepository;
    private final CatalogQueryRepository catalogQueryRepository;
    private final ScrapeRunRepository scrapeRunRepository;
    private final SiteRegistry siteRegistry;

    /**
     * Backward-compatible overload for the ~46 existing test call sites that
     * construct {@code DatabaseService} without a {@link SiteRegistry} — each
     * gets its own private instance, backed by the same {@code DataSource},
     * rather than the single Spring-managed singleton production wiring
     * shares (close-1nf-and-3nf-foundation extension, design E1). None of
     * those tests exercise cross-refresh behavior (a POST/DELETE
     * {@code /api/sitios} elsewhere becoming visible here), so a private
     * instance is behaviorally identical to them.
     */
    public DatabaseService(DataSource dataSource) {
        this(dataSource, new SiteRegistry(dataSource));
    }

    @Autowired
    public DatabaseService(DataSource dataSource, SiteRegistry siteRegistry) {
        this.dataSource = dataSource;
        this.siteRegistry = siteRegistry;
        this.catalogQueryRepository = new CatalogQueryRepository(dataSource, siteRegistry);
        this.cronRepository = new CronRepository(dataSource);
        this.presetRepository = new PresetRepository(dataSource);
        this.favoritosRepository = new FavoritosRepository(dataSource);
        this.feedbackRepository = new FeedbackRepository(dataSource);
        this.savedOutfitsRepository = new SavedOutfitsRepository(dataSource);
        this.mlOutputRepository = new MlOutputRepository(dataSource);
        this.historialRepository = new HistorialRepository(dataSource);
        this.sitiosRepository = new SitiosRepository(dataSource, siteRegistry);
        this.categoriaStatsRepository = new CategoriaStatsRepository(dataSource);
        this.preciosExternosRepository = new PreciosExternosRepository(dataSource);
        this.productRepository = new ProductRepository(dataSource, siteRegistry);
        this.scrapeRunRepository = new ScrapeRunRepository(dataSource);
    }

    public SiteRegistry siteRegistry() {
        return siteRegistry;
    }


    @PostConstruct
    void init() {
        try {
            presetRepository.seedPresetIlustrativoSiVacio();
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
     * Cuenta las filas cacheadas en {@code image_embeddings} (fashion-image-
     * classification PR6, T6.3/T6.4) — respalda {@code embeddingsCount} en
     * {@code GET /api/ml/estado} para reportar cobertura del índice visual
     * frente al total de productos activos.
     */
    /**
     * Cuenta las filas cacheadas en {@code image_embeddings} — respalda
     * {@code embeddingsCount} en {@code GET /api/ml/estado}.
     */
    public long contarEmbeddings() {
        return productRepository.contarEmbeddings();
    }

    // ─── Presets de financiación. Bodies in PresetRepository (backlog A3);
    // this class keeps the public surface and delegates. The Preset record
    // stays HERE: callers and tests name it DatabaseService.Preset.
    // ─────────────────────────────────────────────────────────────────────

    public record Preset(int id, String label, double recargoPct, int cuotas, boolean activo) {}

    public List<Preset> listarPresets() {
        return presetRepository.listarPresets();
    }

    public Optional<Preset> cargarPresetActivo() {
        return presetRepository.cargarPresetActivo();
    }

    /**
     * Crea un preset nuevo, inactivo por defecto. Retorna el id generado, o -1 en
     * error o si {@code cuotas}/{@code recargoPct} son inválidos (mismo criterio
     * que {@code FinanciacionCalculator.compute}: cuotas&gt;0 y recargoPct&gt;-100).
     */
    public int crearPreset(String label, double recargoPct, int cuotas) {
        return presetRepository.crearPreset(label, recargoPct, cuotas);
    }

    /**
     * Edita label/recargoPct/cuotas de un preset existente. No altera su estado activo.
     * Retorna {@code false} sin persistir si {@code cuotas}/{@code recargoPct} son
     * inválidos, o si ocurre un error.
     */
    public boolean editarPreset(int id, String label, double recargoPct, int cuotas) {
        return presetRepository.editarPreset(id, label, recargoPct, cuotas);
    }

    /**
     * Activa el preset {@code id} y desactiva todos los demás, de forma transaccional.
     * Retorna {@code false} (y revierte la desactivación) si {@code id} no existe.
     */
    public boolean activarPreset(int id) {
        return presetRepository.activarPreset(id);
    }

    /**
     * Elimina un preset. Si era el ÚNICO restante, recrea el preset ilustrativo
     * activo; si quedan otros, ninguno se auto-activa.
     *
     * @return {@code true} si el {@code id} existía y fue borrado.
     */
    public boolean eliminarPreset(int id) {
        return presetRepository.eliminarPreset(id);
    }

    // ─── Productos: write-path del scrape, lecturas del catálogo y caminos
    // de clasificación. Bodies in ProductRepository (backlog A3); this class
    // keeps the public surface and delegates.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Aplica la lógica de merge al dataset completo de un scraping, delegando
     * la decisión de "cambió el precio" y el insert/upsert/historial a
     * {@code sp_upsert_run} (server-side, design D2). Retorna estadísticas:
     * {nuevos, actualizados, sinCambios, desactivados}.
     */
    public UpsertStats upsertProductos(List<Product> productos) {
        return productRepository.upsertProductos(productos);
    }

    /**
     * Igual, pero acotando el soft-delete a lo que tocó la corrida en lugar de
     * a este batch (design D4). Un resume trae sólo la mitad reanudada, así que
     * el alcance derivado del batch deja de barrer los sitios que cubrió la
     * mitad interrumpida.
     *
     * @param runStartedAt el {@code started_at} de la corrida; {@code null}
     *                     vuelve al alcance derivado del batch.
     */
    public UpsertStats upsertProductos(List<Product> productos, java.time.Instant runStartedAt) {
        return productRepository.upsertProductos(productos, runStartedAt);
    }

    /**
     * Upsert parcial durante scraping progresivo. NUNCA hace soft-delete — solo
     * inserta/actualiza los productos dados. Columnas visuales excluidas a
     * propósito (VisualAttrs todavía no está poblado en esta etapa).
     */
    public void upsertParcial(List<Product> productos) {
        productRepository.upsertParcial(productos);
    }

    public List<Product> cargarProductos() {
        return productRepository.cargarProductos();
    }

    /** Busca un producto por URL sin filtrar por `activo` (incluye descontinuados). */
    /**
     * `/api/data` en SQL (`sql-catalog-filtering`): filtra, ordena y pagina en
     * la base en vez de barrer el catálogo en memoria en cada request. Los
     * talles y los badges se filtran contra sus tablas hijas — el motivo por el
     * que V7 las creó.
     */
    public CatalogPage buscarCatalogo(CatalogFilter filtro, String orden, int page, int size) {
        return catalogQueryRepository.buscar(filtro, orden, page, size);
    }

    // ── scrape_run / scrape_run_site (V29) ───────────────────────────────────
    //
    // Delegates rather than a getter: ScrapeRunRepository is package-private,
    // like every other repository here, so `ar.scraper.web` cannot name the
    // type. The facade is the seam.

    /** Opens a run and enrolls its sites as PENDING, in one transaction. */
    public long crearScrapeRun(java.util.UUID scrapeUuid, java.time.Instant startedAt,
                               java.util.UUID triggeredBy, Long cronJobId,
                               java.util.Collection<String> sitios) throws SQLException {
        return scrapeRunRepository.crear(scrapeUuid, startedAt, triggeredBy, cronJobId, sitios);
    }

    public void marcarSitioEnCurso(long runId, String sitio, java.time.Instant cuando)
            throws SQLException {
        scrapeRunRepository.marcarSitioEnCurso(runId, sitio, cuando);
    }

    public void marcarSitioTerminado(long runId, String sitio, String status, int productosCount,
                                     String error, java.time.Instant cuando) throws SQLException {
        scrapeRunRepository.marcarSitioTerminado(runId, sitio, status, productosCount, error, cuando);
    }

    public void finalizarScrapeRun(long runId, String status, int productosCount,
                                   java.time.Instant finishedAt) throws SQLException {
        scrapeRunRepository.finalizar(runId, status, productosCount, finishedAt);
    }

    /** Marks whatever the previous process left open. Only marks — never starts a scrape. */
    public java.util.List<Long> marcarRunsInterrumpidos(java.time.Instant cuando) throws SQLException {
        return scrapeRunRepository.marcarInterrumpidosAlArrancar(cuando);
    }

    /** La corrida que dejó abierta un proceso muerto, con sus sitios ya separados. */
    public java.util.Optional<CorridaInterrumpida> ultimaCorridaInterrumpida()
            throws SQLException {
        return scrapeRunRepository.ultimaInterrumpida();
    }

    /** Reabre una corrida interrumpida EN SU LUGAR, conservando su started_at. */
    public void reabrirScrapeRun(long runId) throws SQLException {
        scrapeRunRepository.reabrir(runId);
    }

    /** Marca SKIPPED los sitios pendientes que ya no están en el registro y los devuelve. */
    public java.util.List<String> marcarSitiosAusentesDelRegistro(
            long runId, java.util.Collection<String> nombresActuales) throws SQLException {
        return scrapeRunRepository.marcarAusentesDelRegistro(runId, nombresActuales);
    }

    /** The reader-isolation bound for a run. Truncated to the second — see the repository. */
    public java.util.Optional<java.time.Instant> startedAtDeRun(long runId) throws SQLException {
        return scrapeRunRepository.startedAtDe(runId);
    }

    /** Las facetas del catálogo persistido, un GROUP BY por faceta. */
    public ar.scraper.aggregator.ResultAggregator.Facets facetasCatalogo() {
        return catalogQueryRepository.facetas();
    }

    /** Rango de precios, conteo por sitio/rubro, gymrat y packs del catálogo persistido. */
    public CatalogResumen resumenCatalogo() {
        return catalogQueryRepository.resumen();
    }

    public java.util.Optional<Product> obtenerProducto(String url) {
        return productRepository.obtenerProducto(url);
    }

    /** Producto por su handle corto (`producto_key`, V25). Ver ProductRepository. */
    public java.util.Optional<Product> obtenerProductoPorKey(String key) {
        return productRepository.obtenerProductoPorKey(key);
    }

    /**
     * Read-side of the manual classification lock (design D3/D4). One entry
     * per locked product, keyed by url — {@code ResultAggregator.aplicarBloqueos}
     * reads this ONCE per {@code agregar} call.
     */
    public Map<String, ClasificacionBloqueada> cargarClasificacionBloqueada() {
        return productRepository.cargarClasificacionBloqueada();
    }

    // ─── ML Output. Bodies in MlOutputRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarMlOutput(JsonNode mlOutput) {
        mlOutputRepository.guardarMlOutput(mlOutput);
    }

    public JsonNode cargarMlOutput() {
        return mlOutputRepository.cargarMlOutput();
    }

    // ─── Historial de precios (lecturas). Bodies in HistorialRepository
    // (backlog A3). Las ESCRITURAS viven en el upsert de productos.
    // ─────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> cargarHistorial(String url) {
        return historialRepository.cargarHistorial(url);
    }

    // ─── Sitios dinámicos. Bodies in SitiosRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarSitio(String nombre, String url, String plataforma) {
        sitiosRepository.guardarSitio(nombre, url, plataforma);
    }

    public void eliminarSitio(String nombre) {
        sitiosRepository.eliminarSitio(nombre);
    }

    public List<Map<String, String>> cargarSitiosDinamicos() {
        return sitiosRepository.cargarSitiosDinamicos();
    }

    // ─── Categoria Stats. Bodies in CategoriaStatsRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarCategoriaStats(com.fasterxml.jackson.databind.JsonNode statsNode) {
        categoriaStatsRepository.guardarCategoriaStats(statsNode);
    }

    public java.util.Map<String, CategoriaStats> cargarCategoriaStats() {
        return categoriaStatsRepository.cargarCategoriaStats();
    }

    // ─── Precios externos. Bodies in PreciosExternosRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        preciosExternosRepository.guardarPreciosExternos(productoUrl, sitio, resultados);
    }

    public java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
        return preciosExternosRepository.cargarPreciosExternos(productoUrl);
    }

    /**
     * Actualiza la categoría de un producto (corrección por modelo ML).
     * Camino de MÁQUINA (design D5): lleva {@code AND bloqueado_por IS NULL},
     * así un producto bloqueado no pierde su categoría humana-confirmada.
     */
    public void actualizarCategoria(String url, String nuevaCategoria) {
        productRepository.actualizarCategoria(url, nuevaCategoria);
    }

    /**
     * Actualiza categoria/marca/genero/talles de un producto ya persistido sin
     * re-scrapear (bulk re-normalización). Devuelve el row count real del UPDATE
     * de clasificación — el llamador lo usa para distinguir "escritura intentada"
     * de "escritura aplicada". Camino de MÁQUINA: respeta el lock.
     */
    public int actualizarNormalizacion(String url, String categoria, String marca,
                                        String genero, List<String> talles, String subCategoria) {
        return productRepository.actualizarNormalizacion(url, categoria, marca, genero, talles, subCategoria);
    }

    /**
     * Camino auditado de reclasificación humana ({@code POST /api/agent/apply}).
     * Una sola transacción: el UPDATE de clasificación, el UPDATE de rubro/lock
     * y el INSERT de auditoría se confirman juntos o ninguno de los tres.
     * Camino HUMANO: NO lleva el guard del lock — una segunda confirmación debe
     * poder re-lockear un producto ya bloqueado.
     */
    public boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
                                                   String genero, List<String> talles, String subCategoria,
                                                   Product previo, String actor) {
        return productRepository.aplicarReclasificacionAuditada(
                url, categoria, marca, genero, talles, subCategoria, previo, actor);
    }

    // ─── Favoritos. Bodies in FavoritosRepository (backlog A3).
    //
    // Cada método toma usuario_id PRIMERO y no existe ninguna variante sin él.
    // Esa ausencia es el diseño: una lectura que ramifica por rol es donde una
    // fuga aparece tarde o temprano, y un método que no existe no se puede
    // llamar por error. ADMIN y VIEWER corren el mismo SQL con otro parámetro.
    // ─────────────────────────────────────────────────────────────────────

    public void guardarFavorito(UUID usuarioId, String url, String sitio, String nombre) {
        favoritosRepository.guardarFavorito(usuarioId, url, sitio, nombre);
    }

    public void eliminarFavorito(UUID usuarioId, String url) {
        favoritosRepository.eliminarFavorito(usuarioId, url);
    }

    public List<Map<String, String>> listarFavoritos(UUID usuarioId) {
        return favoritosRepository.listarFavoritos(usuarioId);
    }

    public void tocarFavorito(UUID usuarioId, String url) {
        favoritosRepository.tocarFavorito(usuarioId, url);
    }

    // ─── Outfit feedback + categoria dismiss. Bodies in FeedbackRepository
    // (backlog A3). The OutfitItemRow record stays HERE: callers and tests
    // name it DatabaseService.OutfitItemRow.
    // ─────────────────────────────────────────────────────────────────────

    /** Fila cruda de feedback per-item — el join con el catálogo vivo lo hace el caller. */
    public record OutfitItemRow(String slot, String url, boolean liked, String estilo) {}

    /**
     * Backward-compat overload: persiste con estilo="gym" (comportamiento previo a
     * la separación de señal por estilo). Se mantiene para callers/tests que no
     * distinguen estilo.
     */
    public void guardarOutfitFeedbackItem(UUID usuarioId, String genero, String slot, String url, boolean liked) {
        guardarOutfitFeedbackItem(usuarioId, genero, slot, url, liked, "gym");
    }

    public void guardarOutfitFeedbackItem(UUID usuarioId, String genero, String slot, String url,
                                          boolean liked, String estilo) {
        feedbackRepository.guardarOutfitFeedbackItem(usuarioId, genero, slot, url, liked, estilo);
    }

    public List<OutfitItemRow> obtenerOutfitFeedback(UUID usuarioId) {
        return feedbackRepository.obtenerOutfitFeedback(usuarioId);
    }

    /**
     * Marca una categoria como "no me interesa" feed-wide. Idempotente: si la
     * categoria ya está dismissed, no inserta una fila duplicada.
     */
    public void guardarCategoriaDismiss(UUID usuarioId, String categoria) {
        feedbackRepository.guardarCategoriaDismiss(usuarioId, categoria);
    }

    /**
     * Borra TODO el historial de feedback (todos los estilos + tabla legacy).
     * Backward-compat: el reset scoped por estilo usa {@link #limpiarOutfitFeedback(String)}.
     */
    public void limpiarOutfitFeedback(UUID usuarioId) {
        feedbackRepository.limpiarOutfitFeedback(usuarioId);
    }

    /**
     * Borra el historial de feedback de UN estilo ("gym" | "casual"). estilo
     * null/blank → no-op.
     */
    public void limpiarOutfitFeedback(UUID usuarioId, String estilo) {
        feedbackRepository.limpiarOutfitFeedback(usuarioId, estilo);
    }

    /** Revierte el dismiss de una categoria (undo). Safe no-op si no existía. */
    public void borrarCategoriaDismiss(UUID usuarioId, String categoria) {
        feedbackRepository.borrarCategoriaDismiss(usuarioId, categoria);
    }

    /** Lee todas las categorias dismissed feed-wide. */
    public Set<String> obtenerCategoriaDismiss(UUID usuarioId) {
        return feedbackRepository.obtenerCategoriaDismiss(usuarioId);
    }

    public void marcarDescontinuado(String url) {
        productRepository.marcarDescontinuado(url);
    }

    /**
     * Live single-URL read of the classification lock (review fix F3,
     * manual-classification-lock). {@code ResultAggregator.renormalizarCatalogo}
     * snapshots {@link #cargarClasificacionBloqueada()} once at method entry
     * for the common case, but that snapshot goes stale the moment a product
     * is locked via {@code POST /api/agent/apply} mid-run — the loop makes one
     * sequential round-trip per changed product across the whole catalog, a
     * realistic window. This lets the caller attribute a 0-row guarded write
     * to "correctly skipped, now locked" from a FRESH read taken right after
     * the write attempt, instead of trusting the stale snapshot.
     */
    /**
     * Live single-URL read of the classification lock (review fix F3,
     * manual-classification-lock) — a FRESH read, not the possibly-stale
     * {@link #cargarClasificacionBloqueada()} snapshot.
     */
    public boolean estaBloqueado(String url) {
        return productRepository.estaBloqueado(url);
    }

    public boolean esProductoActivo(String url) {
        return productRepository.esProductoActivo(url);
    }

    public record HistorialEntry(String fecha, double precio) {}

    public List<HistorialEntry> getHistorialPrecios(String url) {
        return historialRepository.getHistorialPrecios(url);
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: una sola consulta
     * {@code WHERE url IN (...)}, evitando el patrón N+1 (usado por
     * {@code SenalEnricher} sobre todo el catálogo).
     *
     * @param urls URLs de productos a consultar; URLs vacías/blank son ignoradas
     * @return mapa url -&gt; historial (orden ascendente por fecha); URLs sin
     *         historial no aparecen como key
     */
    public Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls) {
        return historialRepository.getHistorialPrecios(urls);
    }

    // ─── Clear methods ───────────────────────────────────────────────────────

    public void limpiarProductos() throws SQLException {
        productRepository.limpiarProductos();
    }

    public void limpiarMlOutput() throws SQLException {
        mlOutputRepository.limpiarMlOutput();
    }

    // ─── Saved Outfits. Bodies in SavedOutfitsRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Persiste un outfit generado con su nombre, slots y suplementos en JSON, y el
     * total estimado. Retorna el id generado, o -1 en error.
     */
    public int guardarOutfit(UUID usuarioId, String nombre, String slotsJson,
                             String suplementosJson, double total) {
        return savedOutfitsRepository.guardarOutfit(usuarioId, nombre, slotsJson, suplementosJson, total);
    }

    /** Retorna todos los outfits guardados, ordenados por created_at DESC. */
    public List<Map<String, Object>> obtenerOutfitsGuardados(UUID usuarioId) {
        return savedOutfitsRepository.obtenerOutfitsGuardados(usuarioId);
    }

    /** Elimina un outfit guardado por id. Retorna true si existía. */
    public boolean eliminarOutfitGuardado(UUID usuarioId, int id) {
        return savedOutfitsRepository.eliminarOutfitGuardado(usuarioId, id);
    }

    /** Renombra un outfit guardado. Retorna true si existía. */
    public boolean renombrarOutfit(UUID usuarioId, int id, String nombre) {
        return savedOutfitsRepository.renombrarOutfit(usuarioId, id, nombre);
    }

    // ─── Cron Jobs + Executions. Bodies in CronRepository (backlog A3);
    // this class keeps the public surface and delegates.
    // ─────────────────────────────────────────────────────────────────────

    public long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        return cronRepository.insertCronJob(name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled, nextRunAt);
    }

    /** Retorna {@code false} sin persistir si {@code id} no existe. */
    public boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        return cronRepository.updateCronJob(id, name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled, nextRunAt);
    }

    /** Elimina el job y (cascada manual) sus ejecuciones. Retorna {@code false} si {@code id} no existía. */
    public boolean deleteCronJob(long id) {
        return cronRepository.deleteCronJob(id);
    }

    public List<CronJob> listCronJobs() {
        return cronRepository.listCronJobs();
    }

    public Optional<CronJob> getCronJob(long id) {
        return cronRepository.getCronJob(id);
    }

    /** Actualiza SOLO {@code last_run_at} — usado por {@code CronJobRunner} al disparar/skippear un run. */
    public boolean touchLastRunAt(long jobId, String lastRunAt) {
        return cronRepository.touchLastRunAt(jobId, lastRunAt);
    }

    /** Actualiza SOLO {@code next_run_at} — usado por {@code CronSchedulerService} tras cada poll. */
    public boolean updateNextRunAt(long jobId, String nextRunAt) {
        return cronRepository.updateNextRunAt(jobId, nextRunAt);
    }

    public long insertCronExecution(long jobId, String startedAt, String status, String skippedReason) {
        return cronRepository.insertCronExecution(jobId, startedAt, status, skippedReason);
    }

    public boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs) {
        return cronRepository.updateCronExecution(execId, finishedAt, status,
                skippedReason, logOutput, durationMs);
    }

    public List<CronExecution> listExecutions(long jobId, int limit) {
        return cronRepository.listExecutions(jobId, limit);
    }

    public Optional<CronExecution> getExecution(long execId) {
        return cronRepository.getExecution(execId);
    }

    /** Retiene solo las últimas {@code keep} ejecuciones por job (decision 7: 50). */
    public void pruneCronExecutions(long jobId, int keep) {
        cronRepository.pruneCronExecutions(jobId, keep);
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {}
}
