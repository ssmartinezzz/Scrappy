package ar.scraper.web;

import ar.scraper.agent.AgentChatResponse;
import ar.scraper.agent.AgentConfig;
import ar.scraper.agent.CatalogAgentService;
import ar.scraper.agent.ChatMessage;
import ar.scraper.agent.ProviderUnavailableException;
import ar.scraper.agent.ReclassifyProposal;
import ar.scraper.agent.TurnOutcome;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    // T4.1-T4.3: real MockMvc dispatch (standalone, NOT @WebMvcTest — ApiController
    // has 11 constructor deps) is the only way to prove the @RequestBody JSON
    // binding contract itself, as opposed to a direct Java method call which
    // never exercises Jackson deserialization at all. The plain ObjectMapper here
    // (no Boot auto-config) keeps FAIL_ON_UNKNOWN_PROPERTIES at its Jackson
    // default (true) — Boot disables that feature — so T4.2 genuinely proves
    // ReclassifyProposal's own @JsonIgnoreProperties(ignoreUnknown = true), not
    // an ambient Boot setting.
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

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

        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ── POST /api/agent/chat ────────────────────────────────────────────

    @Test
    @DisplayName("5.1 happy path → AgentChatResponse")
    void chatHappyPath() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        var expected = new AgentChatResponse("Listo, encontré 2 productos.", List.of(), TurnOutcome.COMPLETE);
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

    // ── agent-chat-response-quality: provider failure → 502 transport ────

    @Test
    @DisplayName("chat → service throws ProviderUnavailableException → 502 with codigo:proveedor_no_disponible, never a 200 chat bubble")
    void chatMapsProviderUnavailableExceptionTo502() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(catalogAgentService.run(anyList(), isNull()))
                .thenThrow(new ProviderUnavailableException(
                        ProviderUnavailableException.Reason.UNREACHABLE, "No se pudo contactar al proveedor LLM."));

        var body = Map.<String, Object>of("messages", List.of(Map.of("role", "user", "text", "hola")));
        var resp = controller.agentChat(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("codigo")).isEqualTo("proveedor_no_disponible");
    }

    // ── 5.11-5.13: model override ───────────────────────────────────────

    @Test
    @DisplayName("5.11 chat with a valid model in the available set → overrides the default for that request")
    void chatWithValidModelOverridesDefault() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(catalogAgentService.listModels()).thenReturn(List.of("qwen3:14b", "llama3.1:8b"));
        when(catalogAgentService.run(anyList(), eq("llama3.1:8b")))
                .thenReturn(new AgentChatResponse("ok", List.of(), TurnOutcome.COMPLETE));

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
                .thenReturn(new AgentChatResponse("ok", List.of(), TurnOutcome.COMPLETE));

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
    // agent-chat-finetune WU4: every direct-call test below now builds a real
    // ReclassifyProposal instead of a Map.of(...) — the same typed shape
    // POST /agent/chat actually returns and frontend/src/api.js's
    // applyProposal() posts verbatim. categoriaActual is deliberately set to
    // the non-canonical "Zapatilla Running" the producto() helper's `categoria`
    // param carries (prep for WU5's staleness guard, which compares
    // categoriaActual against the DB, never against canonicalCategories()).

    @Test
    @DisplayName("5.4 apply commits via aplicarReclasificacionAuditada, preserving untouched fields (e.g. talles)")
    void applyCommitsViaActualizarNormalizacion() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(current));
        when(db.aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(db).aplicarReclasificacionAuditada(
                "https://a.com/1", "Buzo", "Adidas", "hombre", List.of("42", "43"), "", current);
    }

    @Test
    @DisplayName("5.5 apply re-validates server-side: bad url → 400, no write")
    void applyRejectsBadUrlNoWrite() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(service.getLastResult()).thenReturn(mockResult(List.of()));

        ReclassifyProposal body = proposal("https://nope.com/x", "Zapatilla Running", "Buzo");
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

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Frisa");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("T3.1 apply → the write failing (aplicarReclasificacionAuditada=false) is NEVER reported as applied")
    void applyReturns500WhenWriteFails() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(current));
        when(db.aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isNotEqualTo(200);
        assertThat(resp.getBody().toString()).doesNotContain("Reclasificación aplicada");
    }

    @Test
    @DisplayName("5.2b apply → patches the in-memory catalog so the UI reflects the change")
    void applyPatchesTheInMemoryCatalogOnSuccess() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(current));
        when(db.aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // /api/data and /api/mejores serve from lastResult, not from the DB, so
        // without this the persisted change stays invisible until scrape/restart.
        verify(service).actualizarProductoEnMemoria(
                "https://a.com/1", "Buzo", "Adidas", "hombre", current.subCategoria());
    }

    @Test
    @DisplayName("5.2c apply → never patches memory when the write did not happen")
    void applyDoesNotPatchMemoryWhenWriteFails() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(current));
        when(db.aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Buzo");
        controller.agentApply(body);

        verify(service, never()).actualizarProductoEnMemoria(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("5.3 apply → 409 when scraping RUNNING, no write")
    void applyReturns409WhenRunning() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Buzo");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(db);
    }

    // ── T5.1-T5.3: staleness guard (reads the DATABASE, never getLastResult) ──

    @Test
    @DisplayName("T5.1 apply → stale categoriaActual vs DB → 422 conflicto_stale, actual populated, no write")
    void applyDetectsStaleCategoriaActualAgainstDb() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product enMemoria = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(enMemoria)));
        // Alguien más ya reclasificó este producto en la DB desde que se generó
        // la propuesta — el snapshot en memoria (getLastResult) sigue viejo.
        Product enDb = producto("https://a.com/1", "Buzo", "Adidas", "hombre", List.of("42", "43"));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(enDb));

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Musculosa");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("codigo")).isEqualTo("conflicto_stale");
        assertThat(respBody.get("actual")).isNotNull();
        verify(db, never()).aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T5.2 apply → obtenerProducto empty (not found or read error) → 422, fails closed, no write")
    void applyFailsClosedWhenDbReadIsEmpty() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product enMemoria = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(enMemoria)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.empty());

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Musculosa");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("codigo")).isEqualTo("conflicto_stale");
        verify(db, never()).aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T5.3 apply → non-canonical categoriaPropuesta rejected independently of staleness (regression guard)")
    void applyRejectsNonCanonicalCategoryRegardlessOfStaleness() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product enMemoria = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(enMemoria)));

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Frisa");
        var resp = controller.agentApply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(db);
    }

    // ── T4.1-T4.3: real JSON binding via MockMvc (the actual contract fix) ──

    @Test
    @DisplayName("T4.1 apply accepts the real ReclassifyProposal JSON shape end-to-end (typed @RequestBody, not a Map)")
    void applyAcceptsRealReclassifyProposalJsonBody() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(current));
        when(db.aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        ReclassifyProposal body = proposal("https://a.com/1", "Zapatilla Running", "Buzo");

        mockMvc.perform(post("/api/agent/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("T4.2 apply tolerates UI-only keys (_applied/_mensaje) on a retried proposal — ignoreUnknown")
    void applyToleratesUiOnlyKeysOnRetriedProposal() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        Product current = producto("https://a.com/1", "Zapatilla Running", "Adidas", "hombre", List.of("42", "43"));
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));
        when(db.obtenerProducto("https://a.com/1")).thenReturn(Optional.of(current));
        when(db.aplicarReclasificacionAuditada(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        ObjectNode json = objectMapper.valueToTree(proposal("https://a.com/1", "Zapatilla Running", "Buzo"));
        json.put("_applied", false);
        json.put("_mensaje", "retry");

        mockMvc.perform(post("/api/agent/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(json)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("T4.3 apply with a blank url → 400 naming ONLY 'url' (not categoriaPropuesta)")
    void applyWithBlankUrlNamesOnlyUrl() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);

        ReclassifyProposal body = proposal("", "Zapatilla Running", "Buzo");

        mockMvc.perform(post("/api/agent/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("url")))
                .andExpect(content().string(not(containsString("categoriaPropuesta"))));
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

    private ReclassifyProposal proposal(String url, String categoriaActual, String categoriaPropuesta) {
        return new ReclassifyProposal(url, "Producto", categoriaActual, categoriaPropuesta, "", "", "");
    }
}
