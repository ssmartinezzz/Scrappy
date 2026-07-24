package ar.scraper.web;

import ar.scraper.agent.AgentChatResponse;
import ar.scraper.agent.AgentConfig;
import ar.scraper.agent.CatalogAgentService;
import ar.scraper.agent.ChatMessage;
import ar.scraper.agent.ReclassifyProposal;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RED→GREEN coverage for the 3 LLM catalog agent endpoints (llm-catalog-nlp,
 * task 5.1-5.14 — design D3/D4/D5/D8), following this project's existing
 * {@code ApiController} test convention: direct instantiation with mocked
 * collaborators and direct method calls (no full Spring MVC dispatch — see
 * {@code ApiControllerStatusScrapeTest}).
 */
@Epic("REST API")
@Feature("LLM Catalog Agent")
@Story("chat / apply / models")
@DisplayName("ApiController — Agent endpoints")
class ApiControllerAgentTest {

    private ScraperService service;
    private InflacionService inflacionService;
    private ScraperConfig config;
    private ResultAggregator aggregator;
    private DatabaseService db;
    private GroupingService grouping;
    private PythonRunner pythonRunner;
    private OutfitService outfitService;
    private RecommendationService recommendationService;
    private CatalogAgentService catalogAgentService;
    private AgentConfig agentConfig;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        service               = mock(ScraperService.class);
        inflacionService      = mock(InflacionService.class);
        config                = mock(ScraperConfig.class);
        aggregator            = mock(ResultAggregator.class);
        db                    = mock(DatabaseService.class);
        grouping              = mock(GroupingService.class);
        pythonRunner          = mock(PythonRunner.class);
        outfitService         = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);
        catalogAgentService   = mock(CatalogAgentService.class);
        agentConfig           = mock(AgentConfig.class);
        controller = new ApiController(service, inflacionService, config, aggregator, db, grouping,
                pythonRunner, outfitService, recommendationService, catalogAgentService, agentConfig);
    }

    // ── POST /api/agent/chat ────────────────────────────────────────────

    @Test
    @DisplayName("5.1 happy path → AgentChatResponse")
    void chatHappyPath() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        var expected = new AgentChatResponse("Listo, encontré 2 productos.", List.of());
        when(catalogAgentService.run(anyList(), isNull())).thenReturn(expected);

        var body = Map.<String, Object>of("messages", List.of(Map.of("role", "user", "text", "hola")));
        var resp = controller.agentChat(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(expected);
    }

    @Test
    @DisplayName("5.2 empty/missing message → 4xx, provider never invoked")
    void chatEmptyMessageIsBadRequestProviderNeverInvoked() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);

        var bodyMissing = Map.<String, Object>of();
        var respMissing = controller.agentChat(bodyMissing);
        assertThat(respMissing.getStatusCode().is4xxClientError()).isTrue();

        var bodyEmpty = Map.<String, Object>of("messages", List.of(Map.of("role", "user", "text", "")));
        var respEmpty = controller.agentChat(bodyEmpty);
        assertThat(respEmpty.getStatusCode().is4xxClientError()).isTrue();

        verifyNoInteractions(catalogAgentService);
    }

    @Test
    @DisplayName("5.3 chat → 409 when scraping RUNNING, provider never invoked")
    void chatReturns409WhenRunning() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        var body = Map.<String, Object>of("messages", List.of(Map.of("role", "user", "text", "hola")));
        var resp = controller.agentChat(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(catalogAgentService);
    }

    // ── 5.11-5.13: model override ───────────────────────────────────────

    @Test
    @DisplayName("5.11 chat with a valid model in the available set → overrides the default for that request")
    void chatWithValidModelOverridesDefault() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(catalogAgentService.listModels()).thenReturn(List.of("qwen3:14b", "llama3.1:8b"));
        when(catalogAgentService.run(anyList(), eq("llama3.1:8b")))
                .thenReturn(new AgentChatResponse("ok", List.of()));

        var body = Map.<String, Object>of(
                "messages", List.of(Map.of("role", "user", "text", "hola")),
                "model", "llama3.1:8b");
        var resp = controller.agentChat(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(catalogAgentService).run(anyList(), eq("llama3.1:8b"));
    }

    @Test
    @DisplayName("5.12 chat without a model field → uses the env default (null forwarded)")
    void chatWithoutModelUsesEnvDefault() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(catalogAgentService.run(anyList(), isNull()))
                .thenReturn(new AgentChatResponse("ok", List.of()));

        var body = Map.<String, Object>of("messages", List.of(Map.of("role", "user", "text", "hola")));
        controller.agentChat(body);

        verify(catalogAgentService).run(anyList(), isNull());
    }

    @Test
    @DisplayName("5.13 chat with an unknown model → 400 naming the invalid model, no silent fallback")
    void chatWithUnknownModelIsBadRequestNoFallback() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(catalogAgentService.listModels()).thenReturn(List.of("qwen3:14b"));

        var body = Map.<String, Object>of(
                "messages", List.of(Map.of("role", "user", "text", "hola")),
                "model", "does-not-exist:1b");
        var resp = controller.agentChat(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().toString()).contains("does-not-exist:1b");
        verify(catalogAgentService, never()).run(anyList(), any());
    }

    // ── GET /api/agent/models ────────────────────────────────────────────

    @Test
    @DisplayName("5.8 GET /agent/models → {available, default}")
    void agentModelsReturnsAvailableAndDefault() {
        when(catalogAgentService.listModels()).thenReturn(List.of("qwen3:14b", "llama3.1:8b"));
        when(agentConfig.model()).thenReturn("qwen3:14b");

        var resp = controller.agentModels();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(List.of("qwen3:14b", "llama3.1:8b"));
        assertThat(body.get("default")).isEqualTo("qwen3:14b");
    }

    @Test
    @DisplayName("5.9 GET /agent/models is NOT gated — responds normally while scraping RUNNING")
    void agentModelsNotGatedDuringRunning() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);
        when(catalogAgentService.listModels()).thenReturn(List.of("qwen3:14b"));
        when(agentConfig.model()).thenReturn("qwen3:14b");

        var resp = controller.agentModels();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    // ── POST /api/agent/apply ────────────────────────────────────────────

    @Test
    @DisplayName("5.4 apply commits via actualizarNormalizacion, preserving untouched fields (e.g. talles)")
    void applyCommitsViaActualizarNormalizacion() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        var body = Map.<String, Object>of("url", "https://a.com/1", "categoria", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(db).actualizarNormalizacion("https://a.com/1", "Buzo", "Adidas", "hombre", List.of("42", "43"), "");
    }

    @Test
    @DisplayName("5.5 apply re-validates server-side: bad url → 400, no write")
    void applyRejectsBadUrlNoWrite() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(service.getLastResult()).thenReturn(mockResult(List.of()));

        var body = Map.<String, Object>of("url", "https://nope.com/x", "categoria", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("5.5 apply re-validates server-side: bad category → 400, no write")
    void applyRejectsBadCategoryNoWrite() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of());
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        var body = Map.<String, Object>of("url", "https://a.com/1", "categoria", "Frisa");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("5.3 apply → 409 when scraping RUNNING, no write")
    void applyReturns409WhenRunning() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        var body = Map.<String, Object>of("url", "https://a.com/1", "categoria", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(db);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String url, String categoria, String marca, String genero, List<String> talles) {
        return new Product("Sitio", "Producto", 1000, null, url, "img",
                categoria, genero, talles, Product.MlScore.EMPTY, marca,
                "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }
}
