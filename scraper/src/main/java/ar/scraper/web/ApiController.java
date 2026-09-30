package ar.scraper.web;

import ar.scraper.indices.IndiceService;
import ar.scraper.json.ProductJson;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.security.ActorResolver;
import ar.scraper.agent.AgentChatResponse;
import ar.scraper.agent.AgentConfig;
import ar.scraper.agent.CatalogAgentService;
import ar.scraper.agent.ReclassifyProposal;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ScraperService   service;
    private final IndiceService indiceService;
    private final ScraperConfig    config;

    private final ar.scraper.aggregator.ResultAggregator aggregator;
    private final ar.scraper.db.DatabaseService db;
    private final ar.scraper.ml.PythonRunner pythonRunner;
    private final OutfitService outfitService;
    private final RecommendationService recommendationService;
    private final CatalogAgentService catalogAgentService;
    private final AgentConfig agentConfig;
    private final ActorResolver actorResolver;

    private final AgentEndpoints agentEndpoints;

    private final FinanciacionEndpoints financiacionEndpoints;

    private final OutfitsEndpoints outfitsEndpoints;

    private final PcsEndpoints pcsEndpoints;

    private final RecomendadosEndpoints recomendadosEndpoints;

    private final FavoritosEndpoints favoritosEndpoints;

    private final MlEndpoints mlEndpoints;

    private final MarcasPicksEndpoints marcasPicksEndpoints;

    private final ComparadorEndpoints comparadorEndpoints;

    private final DbAdminEndpoints dbAdminEndpoints;

    private final CatalogoEndpoints catalogoEndpoints;

    private final ScrapeControlEndpoints scrapeControlEndpoints;

    // Mappings live here; bodies live in the *Endpoints delegates. The shorter constructors and the
    // mapping-less overloads below exist only so tests that build the controller directly keep compiling.
    @Autowired
    public ApiController(ScraperService service,
                         IndiceService indiceService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig,
                         ActorResolver actorResolver,
                         ar.scraper.web.cache.CatalogoDerivadoCache derivados) {
        this.service           = service;
        this.indiceService  = indiceService;
        this.config            = config;
        this.aggregator        = aggregator;
        this.db                = db;
        this.pythonRunner      = pythonRunner;
        this.outfitService     = outfitService;
        this.recommendationService = recommendationService;
        this.catalogAgentService = catalogAgentService;
        this.agentConfig        = agentConfig;
        this.actorResolver      = actorResolver;
        this.agentEndpoints     = new AgentEndpoints(service, db.rubroResolver(), db.productos(), catalogAgentService,
                                                     agentConfig, actorResolver);
        this.financiacionEndpoints = new FinanciacionEndpoints(service, indiceService,
                                                               db.presets(), db.historial(), db.productos(), aggregator);
        this.outfitsEndpoints   = new OutfitsEndpoints(service, db.feedback(), db.outfitsGuardados(), outfitService, actorResolver);
        this.pcsEndpoints       = new PcsEndpoints(service, new ar.scraper.pcs.PcBuilder(),
                                                     db.pcsGuardadas(), db.preferenciaArmador(), actorResolver);
        this.recomendadosEndpoints = new RecomendadosEndpoints(service, db.feedback(), recommendationService, actorResolver);
        this.favoritosEndpoints = new FavoritosEndpoints(db.favoritos(), db.productos(), actorResolver);
        this.mlEndpoints        = new MlEndpoints(service, db.categoriaStats(), db.mlOutput(), db.historial(), db.productos(), aggregator, pythonRunner);
        this.marcasPicksEndpoints = new MarcasPicksEndpoints(service);
        this.comparadorEndpoints = new ComparadorEndpoints(service, db.preciosExternos(), derivados);
        this.dbAdminEndpoints   = new DbAdminEndpoints(service, db.mlOutput(), db.productos(), aggregator);
        this.catalogoEndpoints  = new CatalogoEndpoints(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(), config, indiceService);
        this.scrapeControlEndpoints = new ScrapeControlEndpoints(service, config);
    }

    public ApiController(ScraperService service,
                         IndiceService indiceService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig,
                         ActorResolver actorResolver) {
        this(service, indiceService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, catalogAgentService, agentConfig, actorResolver,
             new ar.scraper.web.cache.CatalogoDerivadoCache(service, grouping));
    }

    public ApiController(ScraperService service,
                         IndiceService indiceService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig) {
        this(service, indiceService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, catalogAgentService, agentConfig, new ActorResolver());
    }

    public ApiController(ScraperService service,
                         IndiceService indiceService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService) {
        this(service, indiceService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, null, null);
    }

    @GetMapping("/scrape/interrupted")
    public ResponseEntity<ApiResponse<ScrapeDtos.Interrumpida>> scrapeInterrumpido() {
        return scrapeControlEndpoints.interrumpida();
    }

    @PostMapping("/scrape/resume")
    public ResponseEntity<ApiResponse<ScrapeDtos.Retomar>> retomarScrape() {
        return scrapeControlEndpoints.retomar();
    }

    @PostMapping("/scrape/discard")
    public ResponseEntity<ApiResponse<ScrapeDtos.Descartar>> descartarScrapeInterrumpido() {
        return scrapeControlEndpoints.descartar();
    }

    @PostMapping("/scrape/cancel")
    public ResponseEntity<ApiResponse<ScrapeDtos.Cancelar>> cancelarScrape() {
        return scrapeControlEndpoints.cancelar();
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ScrapeDtos.Status>> status() {
        return scrapeControlEndpoints.status();
    }

    @PostMapping("/scrape")
    public ResponseEntity<ApiResponse<ScrapeDtos.Iniciar>> scrape(
            @RequestParam(required=false) Double precioMin,
            @RequestParam(required=false) Double precioMax,
            @RequestParam(required=false) Double precio,          // legacy alias of precioMax
            @RequestParam(required=false) List<String> sitios,
            @RequestParam(defaultValue="false") boolean forceRetrain) {
        return scrapeControlEndpoints.scrape(precioMin, precioMax, precio, sitios, forceRetrain);
    }

    @DeleteMapping("/db/productos")
    public ResponseEntity<ApiResponse<MensajeDto>> limpiarProductos() {
        return dbAdminEndpoints.limpiarProductos();
    }

    @DeleteMapping("/db/ml")
    public ResponseEntity<ApiResponse<MensajeDto>> limpiarMl() {
        return dbAdminEndpoints.limpiarMl();
    }


    public ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> data(
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
    public ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> data(
            @RequestParam(defaultValue = "0")   int page,
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
    public ResponseEntity<ApiResponse<CatalogoDtos.FacetsDto>> facets() {
        return catalogoEndpoints.facets();
    }

    @GetMapping("/csv")
    public ResponseEntity<String> csv() throws Exception {
        return catalogoEndpoints.csv();
    }

    @GetMapping("/producto/{key}")
    public ResponseEntity<ApiResponse<CatalogoDtos.ProductoDetalle>> productoDetalle(@PathVariable String key) {
        return catalogoEndpoints.productoDetalle(key);
    }


    @GetMapping("/sitios")
    public ResponseEntity<ApiResponse<ScrapeDtos.Sitios>> getSitios() {
        return scrapeControlEndpoints.getSitios();
    }

    @PostMapping("/sitios")
    public ResponseEntity<ApiResponse<OpResult>> agregarSitio(@RequestBody Map<String, String> body) {
        return scrapeControlEndpoints.agregarSitio(body);
    }

    @DeleteMapping("/sitios/{nombre}")
    public ResponseEntity<ApiResponse<OpResult>> eliminarSitio(@PathVariable String nombre) {
        return scrapeControlEndpoints.eliminarSitio(nombre);
    }

    @PutMapping("/config")
    public ResponseEntity<ApiResponse<ScrapeDtos.ConfigResult>> updateConfig(@RequestBody Map<String, Object> body) {
        return scrapeControlEndpoints.updateConfig(body);
    }


    @GetMapping("/tendencias")
    public ResponseEntity<ApiResponse<JsonNode>> tendencias() {
        return mlEndpoints.tendencias();
    }

    @GetMapping("/historial")
    public ResponseEntity<ApiResponse<JsonNode>> historial(@RequestParam String url) {
        return mlEndpoints.historial(url);
    }


    @GetMapping("/grupos")
    public ResponseEntity<ApiResponse<List<ComparadorDtos.Grupo>>> grupos(
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
    public ResponseEntity<ApiResponse<MlDtos.Started>> mlAplicar() {
        return mlEndpoints.mlAplicar();
    }

    @PostMapping("/ml/renormalizar")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> mlRenormalizar() {
        return mlEndpoints.mlRenormalizar();
    }


    @GetMapping("/financiacion/presets")
    public ResponseEntity<ApiResponse<FinanciacionDtos.Presets>> listarPresets() {
        return financiacionEndpoints.listarPresets();
    }

    @PostMapping("/financiacion/presets")
    public ResponseEntity<ApiResponse<OpResult>> crearPreset(@RequestBody Map<String, Object> body) {
        return financiacionEndpoints.crearPreset(body);
    }

    @PutMapping("/financiacion/presets/{id}/activar")
    public ResponseEntity<ApiResponse<OpResult>> activarPreset(@PathVariable int id) {
        return financiacionEndpoints.activarPreset(id);
    }

    @PutMapping("/financiacion/presets/{id}")
    public ResponseEntity<ApiResponse<OpResult>> editarPreset(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return financiacionEndpoints.editarPreset(id, body);
    }

    @DeleteMapping("/financiacion/presets/{id}")
    public ResponseEntity<ApiResponse<OpResult>> eliminarPreset(@PathVariable int id) {
        return financiacionEndpoints.eliminarPreset(id);
    }

    @GetMapping("/recomendacion")
    public ResponseEntity<ApiResponse<FinanciacionDtos.Recomendacion>> recomendacion(@RequestParam String url) {
        return financiacionEndpoints.recomendacion(url);
    }

    @GetMapping("/indices")
    public ResponseEntity<ApiResponse<FinanciacionDtos.Indices>> indices() {
        return financiacionEndpoints.indices();
    }


    @GetMapping("/outfits")
    public ResponseEntity<ApiResponse<OutfitsDtos.Outfit>> outfits(
            @RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(defaultValue = "0") double presupuestoSuplementos) {
        return outfitsEndpoints.outfits(genero, presupuesto, excluir, presupuestoSuplementos);
    }

    @GetMapping("/outfits/builder")
    public ResponseEntity<ApiResponse<OutfitsDtos.Builder>> outfitsBuilder(
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
    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementoTipos>> suplementosTipos() {
        return outfitsEndpoints.suplementosTipos();
    }

    @GetMapping("/suplementos/builder")
    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementosBuilder>> suplementosBuilder(
            @RequestParam(required = false) String tipos,
            @RequestParam(defaultValue = "0") double presupuesto,
            @RequestParam(defaultValue = "") String excluir) {
        return outfitsEndpoints.suplementosBuilder(tipos, presupuesto, excluir);
    }

    @GetMapping("/pcs/builder")
    public ResponseEntity<ApiResponse<ObjectNode>> pcsBuilder(
            @RequestParam(defaultValue = "0") double presupuesto,
            @RequestParam(defaultValue = "false") boolean conGpu,
            @RequestParam(defaultValue = "") String excluir,
            @RequestParam(defaultValue = "") String gama,
            @RequestParam(defaultValue = "") String ddr,
            @RequestParam(defaultValue = "") String marcaCpu,
            @RequestParam(defaultValue = "") String marcaGpu,
            @RequestParam(defaultValue = "") String tipoAlmacenamiento,
            @RequestParam(required = false) Boolean ramDual,
            @RequestParam(required = false) Boolean wifi,
            @RequestParam(required = false) Integer capacidadMinimaGb,
            @RequestParam(defaultValue = "") String tamanioGabinete,
            @RequestParam(defaultValue = "") String tipoCooler,
            @RequestParam(required = false) Integer wattsMinimos,
            @RequestParam(defaultValue = "") String uso) {
        return pcsEndpoints.builder(presupuesto, conGpu, excluir, gama,
                ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi,
                capacidadMinimaGb, tamanioGabinete, tipoCooler, wattsMinimos, uso);
    }

    public ResponseEntity<ApiResponse<ObjectNode>> pcsBuilder(double presupuesto, boolean conGpu, String excluir, String gama,
            String ddr, String marcaCpu, String marcaGpu, String tipoAlmacenamiento,
            Boolean ramDual, Boolean wifi,
            Integer capacidadMinimaGb, String tamanioGabinete, String tipoCooler, Integer wattsMinimos) {
        return pcsEndpoints.builder(presupuesto, conGpu, excluir, gama,
                ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi,
                capacidadMinimaGb, tamanioGabinete, tipoCooler, wattsMinimos);
    }

    public ResponseEntity<ApiResponse<ObjectNode>> pcsBuilder(double presupuesto, boolean conGpu, String excluir, String gama,
            String ddr, String marcaCpu, String marcaGpu, String tipoAlmacenamiento,
            Boolean ramDual, Boolean wifi) {
        return pcsEndpoints.builder(presupuesto, conGpu, excluir, gama,
                ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi);
    }

    public ResponseEntity<ApiResponse<ObjectNode>> pcsBuilder(double presupuesto, boolean conGpu, String excluir, String gama) {
        return pcsEndpoints.builder(presupuesto, conGpu, excluir, gama, "", "", "", "", null, null);
    }

    public ResponseEntity<ApiResponse<ObjectNode>> pcsBuilder(double presupuesto, boolean conGpu, String excluir) {
        return pcsEndpoints.builder(presupuesto, conGpu, excluir, "", "", "", "", "", null, null);
    }

    @GetMapping("/pcs/preferencia")
    public ResponseEntity<ApiResponse<PcsDtos.Preferencia>> getPcsPreferencia() {
        return pcsEndpoints.getPreferencia();
    }

    @PutMapping("/pcs/preferencia")
    public ResponseEntity<ApiResponse<PcsDtos.Preferencia>> putPcsPreferencia(@RequestBody Map<String, Object> body) {
        return pcsEndpoints.putPreferencia(body);
    }

    @PostMapping("/pcs/save")
    public ResponseEntity<ApiResponse<PcsDtos.Guardada>> savePc(@RequestBody Map<String, Object> body) {
        return pcsEndpoints.savePc(body);
    }

    @GetMapping("/pcs/saved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSavedPcs() {
        return pcsEndpoints.getSavedPcs();
    }

    @DeleteMapping("/pcs/saved/{id}")
    public ResponseEntity<ApiResponse<OpResult>> deleteSavedPc(@PathVariable int id) {
        return pcsEndpoints.deleteSavedPc(id);
    }

    @PatchMapping("/pcs/saved/{id}/nombre")
    public ResponseEntity<ApiResponse<OpResult>> renameSavedPc(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return pcsEndpoints.renameSavedPc(id, body);
    }

    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementosBuilder>> suplementosBuilder(String tipos, double presupuesto) {
        return outfitsEndpoints.suplementosBuilder(tipos, presupuesto, "");
    }

    @PostMapping("/outfits/feedback")
    public ResponseEntity<ApiResponse<OpResult>> outfitFeedback(@RequestBody Map<String, Object> body) {
        return outfitsEndpoints.outfitFeedback(body);
    }

    @PostMapping("/outfits/save")
    public ResponseEntity<ApiResponse<OutfitsDtos.Guardado>> saveOutfit(@RequestBody Map<String, Object> body) {
        return outfitsEndpoints.saveOutfit(body);
    }

    @GetMapping("/outfits/saved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSavedOutfits() {
        return outfitsEndpoints.getSavedOutfits();
    }

    @DeleteMapping("/outfits/saved/{id}")
    public ResponseEntity<ApiResponse<OpResult>> deleteSavedOutfit(@PathVariable int id) {
        return outfitsEndpoints.deleteSavedOutfit(id);
    }

    @PatchMapping("/outfits/saved/{id}/nombre")
    public ResponseEntity<ApiResponse<OpResult>> renameSavedOutfit(@PathVariable int id,
                                                         @RequestBody Map<String, Object> body) {
        return outfitsEndpoints.renameSavedOutfit(id, body);
    }

    @DeleteMapping("/outfits/feedback")
    public ResponseEntity<ApiResponse<OpResult>> resetOutfitFeedback(
            @RequestParam(required = false, defaultValue = "gym") String estilo) {
        return outfitsEndpoints.resetOutfitFeedback(estilo);
    }


    @GetMapping("/recomendados")
    public ResponseEntity<ApiResponse<List<ObjectNode>>> recomendados(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false)    String genero,
            @RequestParam(required = false)    String categoria) {
        return recomendadosEndpoints.recomendados(page, size, genero, categoria);
    }

    @PostMapping("/recomendados/feedback")
    public ResponseEntity<ApiResponse<OpResult>> recomendadosFeedback(@RequestBody Map<String, Object> body) {
        return recomendadosEndpoints.recomendadosFeedback(body);
    }

    @PostMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ApiResponse<OpResult>> dismissCategoria(@RequestBody Map<String, String> body) {
        return recomendadosEndpoints.dismissCategoria(body);
    }

    @DeleteMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ApiResponse<OpResult>> undismissCategoria(@RequestParam String categoria) {
        return recomendadosEndpoints.undismissCategoria(categoria);
    }


    @GetMapping("/favoritos")
    public ResponseEntity<ApiResponse<List<ObjectNode>>> getFavoritos() {
        return favoritosEndpoints.getFavoritos();
    }

    @PostMapping("/favoritos")
    public ResponseEntity<ApiResponse<OpResult>> addFavorito(@RequestBody Map<String, String> body) {
        return favoritosEndpoints.addFavorito(body);
    }

    @DeleteMapping("/favoritos")
    public ResponseEntity<ApiResponse<OpResult>> deleteFavorito(@RequestParam String url) {
        return favoritosEndpoints.deleteFavorito(url);
    }

    @DeleteMapping("/data")
    public ResponseEntity<ApiResponse<OpResult>> eliminarProducto(@RequestParam String url) {
        return catalogoEndpoints.eliminarProducto(url);
    }

    @GetMapping("/ml/estado")
    public ResponseEntity<ApiResponse<MlDtos.Estado>> mlEstado() {
        return mlEndpoints.mlEstado();
    }

    @PostMapping("/ml/entrenar")
    public ResponseEntity<ApiResponse<MlDtos.Started>> mlEntrenar(
            @RequestParam(defaultValue = "false") boolean images,
            @RequestParam(defaultValue = "8") int epochs) {
        return mlEndpoints.mlEntrenar(images, epochs);
    }

    @GetMapping("/ml/resultado")
    public ResponseEntity<ApiResponse<MlDtos.Resultado>> mlResultado() {
        return mlEndpoints.mlResultado();
    }


    @GetMapping("/marcas-browser")
    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.Marca>>> marcasBrowser(
            @RequestParam(required = false) String rubro,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "count") String sort) {
        return marcasPicksEndpoints.marcasBrowser(rubro, q, sort);
    }

    @GetMapping("/mejores")
    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.MejoresCategoria>>> mejoresPorCategoria(
            @RequestParam(required = false) String rubro) {
        return marcasPicksEndpoints.mejoresPorCategoria(rubro);
    }

    static double precioUnitario(Product p) {
        return ProductJson.precioUnitario(p);
    }


    @GetMapping("/db/export")
    public ResponseEntity<ApiResponse<Void>> exportDb() {
        return dbAdminEndpoints.exportDb();
    }

    @PostMapping(value = "/db/import",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Void>> importDb(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile upload) {
        return dbAdminEndpoints.importDb(upload);
    }

    @GetMapping("/buscar-externo")
    public ResponseEntity<ApiResponse<ComparadorDtos.BusquedaExterna>> buscarExterno(
            @RequestParam String q,
            @RequestParam(required = false) String url,
            @RequestParam(defaultValue = "mercadolibre") String sitio) {
        return comparadorEndpoints.buscarExterno(q, url, sitio);
    }



    @PostMapping("/agent/chat")
    public ResponseEntity<ApiResponse<AgentChatResponse>> agentChat(@RequestBody Map<String, Object> body) {
        return agentEndpoints.agentChat(body);
    }

    @GetMapping("/agent/models")
    public ResponseEntity<ApiResponse<AgentDtos.Models>> agentModels() {
        return agentEndpoints.agentModels();
    }

    @PostMapping("/agent/apply")
    public ResponseEntity<ApiResponse<AgentDtos.Applied>> agentApply(@RequestBody ReclassifyProposal body) {
        return agentEndpoints.agentApply(body);
    }
}
