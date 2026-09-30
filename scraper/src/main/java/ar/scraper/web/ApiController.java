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




    static double precioUnitario(Product p) {
        return ProductJson.precioUnitario(p);
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
