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










    static double precioUnitario(Product p) {
        return ProductJson.precioUnitario(p);
    }




}
