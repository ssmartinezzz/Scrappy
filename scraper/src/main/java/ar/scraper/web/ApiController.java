package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.identity.ActorResolver;
import ar.scraper.agent.AgentConfig;
import ar.scraper.agent.CatalogAgentService;
import ar.scraper.agent.ReclassifyProposal;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {


    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(ApiController.class);

    private final ScraperService   service;
    private final InflacionService inflacionService;
    private final ScraperConfig    config;

    private final ar.scraper.aggregator.ResultAggregator aggregator;
    private final ar.scraper.db.DatabaseService db;
    private final ar.scraper.aggregator.grouping.GroupingService grouping;
    private final ar.scraper.ml.PythonRunner pythonRunner;
    private final OutfitService outfitService;
    private final RecommendationService recommendationService;
    private final CatalogAgentService catalogAgentService;
    private final AgentConfig agentConfig;
    private final ActorResolver actorResolver;

    /**
     * LLM Catalog Agent endpoints, extracted to their own class (backlog A3).
     * The request mappings stay on this controller and delegate here, so routes
     * and callers are unchanged.
     */
    private final AgentEndpoints agentEndpoints;

    /**
     * Financing presets, buy recommendation and inflation endpoints, extracted
     * to their own class (backlog A3) — same delegation shape as
     * {@link #agentEndpoints}.
     */
    private final FinanciacionEndpoints financiacionEndpoints;

    /**
     * Outfit / supplement builder and saved-outfit endpoints, extracted to their
     * own class (backlog A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final OutfitsEndpoints outfitsEndpoints;

    /**
     * "Para ti" feed endpoints, extracted to their own class (backlog A3) —
     * same delegation shape as {@link #agentEndpoints}.
     */
    private final RecomendadosEndpoints recomendadosEndpoints;

    /**
     * Favoritos endpoints, extracted to their own class (backlog A3) — same
     * delegation shape as {@link #agentEndpoints}.
     */
    private final FavoritosEndpoints favoritosEndpoints;

    /**
     * ML pipeline / training endpoints, extracted to their own class (backlog
     * A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final MlEndpoints mlEndpoints;

    /**
     * Brand browser and mejores-picks endpoints, extracted to their own class
     * (backlog A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final MarcasPicksEndpoints marcasPicksEndpoints;

    /**
     * Multi-site comparison endpoints, extracted to their own class (backlog
     * A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final ComparadorEndpoints comparadorEndpoints;

    /**
     * Destructive DB maintenance and the retired export/import, extracted to
     * their own class (backlog A3).
     */
    private final DbAdminEndpoints dbAdminEndpoints;

    /**
     * Catalog listing / facets / CSV / soft-delete, extracted to their own
     * class (backlog A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final CatalogoEndpoints catalogoEndpoints;

    /**
     * Run control (status / scrape) and site+config management, extracted to
     * their own class (backlog A3) — same delegation shape as
     * {@link #agentEndpoints}.
     */
    private final ScrapeControlEndpoints scrapeControlEndpoints;

    /**
     * Primary constructor (manual-classification-lock Phase 7) — adds the
     * {@link ActorResolver} seam (architecture/session-readiness, obs #773):
     * {@code agentApply} resolves the acting identity through this ONE seam,
     * never inline. Spring wires this one (see {@code @Autowired} below); two
     * legacy overloads are kept right below purely so the existing unit tests
     * that construct {@code ApiController} directly keep compiling unchanged.
     */
    @Autowired
    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig,
                         ActorResolver actorResolver) {
        this.service           = service;
        this.inflacionService  = inflacionService;
        this.config            = config;
        this.aggregator        = aggregator;
        this.db                = db;
        this.grouping          = grouping;
        this.pythonRunner      = pythonRunner;
        this.outfitService     = outfitService;
        this.recommendationService = recommendationService;
        this.catalogAgentService = catalogAgentService;
        this.agentConfig        = agentConfig;
        this.actorResolver      = actorResolver;
        this.agentEndpoints     = new AgentEndpoints(service, db, catalogAgentService,
                                                     agentConfig, actorResolver);
        this.financiacionEndpoints = new FinanciacionEndpoints(service, inflacionService,
                                                               db, aggregator);
        this.outfitsEndpoints   = new OutfitsEndpoints(service, db, outfitService, actorResolver);
        this.recomendadosEndpoints = new RecomendadosEndpoints(service, db, recommendationService, actorResolver);
        this.favoritosEndpoints = new FavoritosEndpoints(db, actorResolver);
        this.mlEndpoints        = new MlEndpoints(service, db, aggregator, pythonRunner);
        this.marcasPicksEndpoints = new MarcasPicksEndpoints(service);
        this.comparadorEndpoints = new ComparadorEndpoints(service, db, grouping);
        this.dbAdminEndpoints   = new DbAdminEndpoints(service, db, aggregator);
        this.catalogoEndpoints  = new CatalogoEndpoints(service, db, config, inflacionService);
        this.scrapeControlEndpoints = new ScrapeControlEndpoints(service, config);
    }

    /**
     * Legacy 11-arg constructor (pre manual-classification-lock, llm-catalog-nlp
     * shape) — see the note on the primary constructor above. Defaults to a real
     * (not fake) {@link ActorResolver} — it has no dependencies of its own, so
     * this is behaviorally identical to Spring injecting it.
     */
    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig) {
        this(service, inflacionService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, catalogAgentService, agentConfig, new ActorResolver());
    }

    /** Legacy 9-arg constructor (pre-agent) — see the note on the primary constructor above. */
    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService) {
        this(service, inflacionService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, null, null);
    }

    // ─── Run control: status/progreso y lanzar scraping. Bodies in
    // ScrapeControlEndpoints (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/scrape/interrupted")
    public ResponseEntity<ObjectNode> scrapeInterrumpido() {
        return scrapeControlEndpoints.interrumpida();
    }

    @PostMapping("/scrape/resume")
    public ResponseEntity<ObjectNode> retomarScrape() {
        return scrapeControlEndpoints.retomar();
    }

    @PostMapping("/scrape/cancel")
    public ResponseEntity<ObjectNode> cancelarScrape() {
        return scrapeControlEndpoints.cancelar();
    }

    @GetMapping("/status")
    public ResponseEntity<ObjectNode> status() {
        return scrapeControlEndpoints.status();
    }

    @PostMapping("/scrape")
    public ResponseEntity<ObjectNode> scrape(
            @RequestParam(required=false) Double precioMin,
            @RequestParam(required=false) Double precioMax,
            @RequestParam(required=false) Double precio,          // legado
            @RequestParam(required=false) List<String> sitios,   // seleccion opcional
            @RequestParam(defaultValue="false") boolean forceRetrain) {
        return scrapeControlEndpoints.scrape(precioMin, precioMax, precio, sitios, forceRetrain);
    }

    @DeleteMapping("/db/productos")
    public ResponseEntity<String> limpiarProductos() {
        return dbAdminEndpoints.limpiarProductos();
    }

    @DeleteMapping("/db/ml")
    public ResponseEntity<String> limpiarMl() {
        return dbAdminEndpoints.limpiarMl();
    }

    // ─── Catálogo: listado paginado con filtros, facets sueltos, CSV y
    // soft-delete. Bodies in CatalogoEndpoints (backlog A3); the mappings
    // and BOTH data(...) overloads stay here -- tests call them directly.
    //
    // Query params de /data:
    //   page        int (default 1)
    //   size        int (default 24)
    //   talle       string[] (multi, OR dentro del grupo)
    //   genero      string   (single)
    //   categoria   string[] (multi, OR)
    //   q           string   (búsqueda full-text en nombre)
    //   orden       precio_asc | precio_desc | nombre (default precio_asc)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Legacy 17-arg overload (pre-PR6) — retained for backward source
     * compatibility with existing test call sites built before the 4 additive
     * visual-attribute filters (T6.7/T6.8) were added. Defaults
     * fit/estampado/escote/colorDominante to {@code null} (no filter).
     */
    public ResponseEntity<ObjectNode> data(
            int page, int size, List<String> talle, String genero, List<String> categoria,
            String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack,
            Double precioMin, Double precioMax, List<String> subCategoria
    ) {
        return data(page, size, talle, genero, categoria, q, sitio, marca, badge, segment,
                rubro, gymrat, orden, pack, precioMin, precioMax, subCategoria,
                null, null, null, null);
    }

    @GetMapping("/data")
    public ResponseEntity<ObjectNode> data(
            @RequestParam(defaultValue = "1")   int page,
            @RequestParam(defaultValue = "24")  int size,
            @RequestParam(required = false)     List<String> talle,
            @RequestParam(required = false)     String genero,
            @RequestParam(required = false)     List<String> categoria,
            @RequestParam(required = false)     String q,
            @RequestParam(required = false)     String sitio,
            @RequestParam(required = false)     List<String> marca,
            @RequestParam(required = false)     String badge,
            @RequestParam(required = false)     String segment,
            @RequestParam(required = false)     String rubro,
            @RequestParam(required = false)     Boolean gymrat,
            @RequestParam(defaultValue = "precio_asc") String orden,
            @RequestParam(required = false)     Boolean pack,
            @RequestParam(required = false)     Double precioMin,
            @RequestParam(required = false)     Double precioMax,
            @RequestParam(required = false)     List<String> subCategoria,
            @RequestParam(required = false)     String fit,
            @RequestParam(required = false)     String estampado,
            @RequestParam(required = false)     String escote,
            @RequestParam(required = false)     String colorDominante
    ) {
        return catalogoEndpoints.data(page, size, talle, genero, categoria, q, sitio, marca,
                badge, segment, rubro, gymrat, orden, pack, precioMin, precioMax,
                subCategoria, fit, estampado, escote, colorDominante);
    }

    @GetMapping("/facets")
    public ResponseEntity<ObjectNode> facets() {
        return catalogoEndpoints.facets();
    }

    @GetMapping("/csv")
    public ResponseEntity<String> csv() throws Exception {
        return catalogoEndpoints.csv();
    }

    /**
     * Detalle de un producto + su historial, para la vista dedicada. 404 si no
     * existe. Entra por el handle corto (`producto_key`), no por la URL entera.
     */
    @GetMapping("/producto/{key}")
    public ResponseEntity<Object> productoDetalle(@PathVariable String key) {
        return catalogoEndpoints.productoDetalle(key);
    }

    // ─── Gestión de sitios y config. Bodies in ScrapeControlEndpoints
    // (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/sitios")
    public ResponseEntity<ObjectNode> getSitios() {
        return scrapeControlEndpoints.getSitios();
    }

    @PostMapping("/sitios")
    public ResponseEntity<ObjectNode> agregarSitio(@RequestBody Map<String, String> body) {
        return scrapeControlEndpoints.agregarSitio(body);
    }

    @DeleteMapping("/sitios/{nombre}")
    public ResponseEntity<ObjectNode> eliminarSitio(@PathVariable String nombre) {
        return scrapeControlEndpoints.eliminarSitio(nombre);
    }

    @PutMapping("/config")
    public ResponseEntity<ObjectNode> updateConfig(@RequestBody Map<String, Object> body) {
        return scrapeControlEndpoints.updateConfig(body);
    }

    // ─── ML: tendencias, historial de precios, aplicar/renormalizar y
    // entrenamiento. Bodies in MlEndpoints (backlog A3); the mappings stay
    // here. They were spread across three regions of this file.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/tendencias")
    public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> tendencias() {
        return mlEndpoints.tendencias();
    }

    @GetMapping("/historial")
    public ResponseEntity<Object> historial(@RequestParam String url) {
        return mlEndpoints.historial(url);
    }

    // ─── Comparador: grupos multi-sitio + búsqueda externa. Bodies in
    // ComparadorEndpoints (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/grupos")
    public ResponseEntity<Object> grupos(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sitio,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String rubro,
            @RequestParam(defaultValue = "2") int minSitios,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return comparadorEndpoints.grupos(q, sitio, categoria, rubro, minSitios, page, size);
    }

    @PostMapping("/ml/aplicar")
    public ResponseEntity<Object> mlAplicar() {
        return mlEndpoints.mlAplicar();
    }

    @PostMapping("/ml/renormalizar")
    public ResponseEntity<Object> mlRenormalizar() {
        return mlEndpoints.mlRenormalizar();
    }

    // ─── Presets de financiación ("¿conviene en cuotas?") + recomendación
    // de compra + inflación INDEC. Bodies in FinanciacionEndpoints (backlog
    // A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/financiacion/presets")
    public ResponseEntity<ObjectNode> listarPresets() {
        return financiacionEndpoints.listarPresets();
    }

    @PostMapping("/financiacion/presets")
    public ResponseEntity<ObjectNode> crearPreset(@RequestBody Map<String, Object> body) {
        return financiacionEndpoints.crearPreset(body);
    }

    @PutMapping("/financiacion/presets/{id}/activar")
    public ResponseEntity<ObjectNode> activarPreset(@PathVariable int id) {
        return financiacionEndpoints.activarPreset(id);
    }

    @PutMapping("/financiacion/presets/{id}")
    public ResponseEntity<ObjectNode> editarPreset(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return financiacionEndpoints.editarPreset(id, body);
    }

    @DeleteMapping("/financiacion/presets/{id}")
    public ResponseEntity<ObjectNode> eliminarPreset(@PathVariable int id) {
        return financiacionEndpoints.eliminarPreset(id);
    }

    @GetMapping("/recomendacion")
    public ResponseEntity<Object> recomendacion(@RequestParam String url) {
        return financiacionEndpoints.recomendacion(url);
    }

    @GetMapping("/inflacion")
    public ResponseEntity<Object> inflacion() {
        return financiacionEndpoints.inflacion();
    }

    // ─── Outfits + supplement builder + saved outfits. Bodies in
    // OutfitsEndpoints (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/outfits")
    public ResponseEntity<ObjectNode> outfits(
            @RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(defaultValue = "0") double presupuestoSuplementos) {
        return outfitsEndpoints.outfits(genero, presupuesto, excluir, presupuestoSuplementos);
    }

    @GetMapping("/outfits/builder")
    public ResponseEntity<ObjectNode> outfitsBuilder(
            @RequestParam(required = false) String categorias,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(required = false, defaultValue = "") String pin,
            @RequestParam(defaultValue = "false") boolean greedy,
            @RequestParam(required = false, defaultValue = "gym") String estilo) {
        return outfitsEndpoints.outfitsBuilder(categorias, presupuesto, genero, excluir, pin, greedy, estilo);
    }

    @GetMapping("/suplementos/tipos")
    public ResponseEntity<ObjectNode> suplementosTipos() {
        return outfitsEndpoints.suplementosTipos();
    }

    @GetMapping("/suplementos/builder")
    public ResponseEntity<Object> suplementosBuilder(
            @RequestParam(required = false) String tipos,
            @RequestParam(defaultValue = "0") double presupuesto,
            @RequestParam(defaultValue = "") String excluir) {
        return outfitsEndpoints.suplementosBuilder(tipos, presupuesto, excluir);
    }

    /**
     * Backward-compatible 2-arg overload, sin mapping propio — la ruta la sigue
     * sirviendo el método de arriba. Existe para que los call sites previos a
     * {@code excluir} sigan compilando sin editarlos.
     */
    public ResponseEntity<Object> suplementosBuilder(String tipos, double presupuesto) {
        return outfitsEndpoints.suplementosBuilder(tipos, presupuesto, "");
    }

    @PostMapping("/outfits/feedback")
    public ResponseEntity<ObjectNode> outfitFeedback(@RequestBody Map<String, Object> body) {
        return outfitsEndpoints.outfitFeedback(body);
    }

    @PostMapping("/outfits/save")
    public ResponseEntity<ObjectNode> saveOutfit(@RequestBody Map<String, Object> body) {
        return outfitsEndpoints.saveOutfit(body);
    }

    @GetMapping("/outfits/saved")
    public ResponseEntity<Object> getSavedOutfits() {
        return outfitsEndpoints.getSavedOutfits();
    }

    @DeleteMapping("/outfits/saved/{id}")
    public ResponseEntity<ObjectNode> deleteSavedOutfit(@PathVariable int id) {
        return outfitsEndpoints.deleteSavedOutfit(id);
    }

    @PatchMapping("/outfits/saved/{id}/nombre")
    public ResponseEntity<ObjectNode> renameSavedOutfit(@PathVariable int id,
                                                         @RequestBody Map<String, Object> body) {
        return outfitsEndpoints.renameSavedOutfit(id, body);
    }

    @DeleteMapping("/outfits/feedback")
    public ResponseEntity<ObjectNode> resetOutfitFeedback(
            @RequestParam(required = false, defaultValue = "gym") String estilo) {
        return outfitsEndpoints.resetOutfitFeedback(estilo);
    }

    // ─── Recomendados ("Para ti" feed). Bodies in RecomendadosEndpoints
    // (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/recomendados")
    public ResponseEntity<ObjectNode> recomendados(
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false)    String genero,
            @RequestParam(required = false)    String categoria) {
        return recomendadosEndpoints.recomendados(page, size, genero, categoria);
    }

    @PostMapping("/recomendados/feedback")
    public ResponseEntity<ObjectNode> recomendadosFeedback(@RequestBody Map<String, Object> body) {
        return recomendadosEndpoints.recomendadosFeedback(body);
    }

    @PostMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ObjectNode> dismissCategoria(@RequestBody Map<String, String> body) {
        return recomendadosEndpoints.dismissCategoria(body);
    }

    @DeleteMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ObjectNode> undismissCategoria(@RequestParam String categoria) {
        return recomendadosEndpoints.undismissCategoria(categoria);
    }

    // ─── Favoritos. Bodies in FavoritosEndpoints (backlog A3); the mappings
    // stay here. DELETE /api/data is NOT part of that group -- it is written
    // in this region but soft-deletes a catalog product.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/favoritos")
    public ResponseEntity<ArrayNode> getFavoritos() {
        return favoritosEndpoints.getFavoritos();
    }

    @PostMapping("/favoritos")
    public ResponseEntity<ObjectNode> addFavorito(@RequestBody Map<String, String> body) {
        return favoritosEndpoints.addFavorito(body);
    }

    @DeleteMapping("/favoritos")
    public ResponseEntity<ObjectNode> deleteFavorito(@RequestParam String url) {
        return favoritosEndpoints.deleteFavorito(url);
    }

    @DeleteMapping("/data")
    public ResponseEntity<ObjectNode> eliminarProducto(@RequestParam String url) {
        return catalogoEndpoints.eliminarProducto(url);
    }

    @GetMapping("/ml/estado")
    public ResponseEntity<Object> mlEstado() {
        return mlEndpoints.mlEstado();
    }

    @PostMapping("/ml/entrenar")
    public ResponseEntity<Object> mlEntrenar(
            @RequestParam(defaultValue = "false") boolean images,
            @RequestParam(defaultValue = "8") int epochs) {
        return mlEndpoints.mlEntrenar(images, epochs);
    }

    @GetMapping("/ml/resultado")
    public ResponseEntity<Object> mlResultado() {
        return mlEndpoints.mlResultado();
    }

    // ─── Marcas browser + mejores picks. Bodies in MarcasPicksEndpoints
    // (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/marcas-browser")
    public ResponseEntity<Object> marcasBrowser(
            @RequestParam(required = false) String rubro,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "count") String sort) {
        return marcasPicksEndpoints.marcasBrowser(rubro, q, sort);
    }

    @GetMapping("/mejores")
    public ResponseEntity<Object> mejoresPorCategoria(
            @RequestParam(required = false) String rubro) {
        return marcasPicksEndpoints.mejoresPorCategoria(rubro);
    }

    /**
     * Precio por unidad de un producto (precio de estantería dividido por
     * {@code cantidadUnidades} cuando es un pack). Espeja la fórmula usada en
     * {@code /api/data} (fila del catálogo) para que catálogo, ML y mejores
     * picks compartan una única fuente de verdad. Guard contra división por
     * cero: {@code cantidadUnidades <= 0} cae al precio de estantería.
     *
     * <p>Kept on this class as a delegate because a test calls it directly.</p>
     */
    static double precioUnitario(Product p) {
        return ProductJson.precioUnitario(p);
    }

    // ─── DB Export / Import ──────────────────────────────────────────────────────

    @GetMapping("/db/export")
    public ResponseEntity<Object> exportDb() {
        return dbAdminEndpoints.exportDb();
    }

    @PostMapping(value = "/db/import",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> importDb(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile upload) {
        return dbAdminEndpoints.importDb(upload);
    }

    @GetMapping("/buscar-externo")
    public ResponseEntity<Object> buscarExterno(
            @RequestParam String q,
            @RequestParam(required = false) String url,
            @RequestParam(defaultValue = "mercadolibre") String sitio) {
        return comparadorEndpoints.buscarExterno(q, url, sitio);
    }

    private String safe(String s) { return ProductJson.safe(s); }

    // ---------------------------------------------------------------
    // LLM Catalog Agent (llm-catalog-nlp) — chat / apply / models, grouped
    // together behind the same future admin-only gate. NOTE (task 5.7, scope
    // id 734): this whole group is the intended insertion point for an
    // admin-only auth guard once user accounts/roles exist — no no-op guard
    // is added now, this comment only marks WHERE it goes.
    //
    // The bodies live in AgentEndpoints (backlog A3); the mappings stay here.
    // ---------------------------------------------------------------

    @PostMapping("/agent/chat")
    public ResponseEntity<Object> agentChat(@RequestBody Map<String, Object> body) {
        return agentEndpoints.agentChat(body);
    }

    @GetMapping("/agent/models")
    public ResponseEntity<Object> agentModels() {
        return agentEndpoints.agentModels();
    }

    @PostMapping("/agent/apply")
    public ResponseEntity<Object> agentApply(@RequestBody ReclassifyProposal body) {
        return agentEndpoints.agentApply(body);
    }


    /**
     * An owner-scoped surface reached with no authenticated subject.
     *
     * <p>Answered as 401 rather than as an empty list: showing a user nothing
     * when their data is fine is a bug that looks like data loss, and answering
     * with everybody's rows would be the leak. Refusing is the only honest
     * option. In practice the filter chain already guarantees a subject on every
     * one of these routes — this is what catches a future route added to the
     * wrong band before it serves somebody else's data.</p>
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(Sujeto.SinSujeto.class)
    public ResponseEntity<ObjectNode> sinSujeto(Sujeto.SinSujeto e) {
        ObjectNode resp = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        resp.put("error", "no_autenticado");
        resp.put("mensaje", "Esta operación es personal y necesita una sesión.");
        return ResponseEntity.status(401).body(resp);
    }
}
