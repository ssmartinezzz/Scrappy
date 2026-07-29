package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RED→GREEN coverage for {@link CatalogAgentService} (llm-catalog-nlp, task
 * 4.3-4.7 — design D3/D4/D8): the bounded, READ-ONLY tool-use loop. A fake
 * {@link ChatProvider} scripts each turn's {@link ChatResponse} so the whole
 * loop runs deterministically without any real LLM/network dependency.
 */
@Epic("LLM Catalog Agent")
@Feature("CatalogAgentService")
@Story("Bounded read-only tool-use loop")
@DisplayName("CatalogAgentService")
class CatalogAgentServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScraperService scraperService;
    private ToolRegistry registry;
    private Product product;

    @BeforeEach
    void setUp() {
        scraperService = mock(ScraperService.class);
        product = new Product("Sitio", "Zapatilla SAD Adidas", 1000, null, "https://a.com/1", "img",
                "Zapatilla Running", "hombre", List.of(), Product.MlScore.EMPTY, "Adidas",
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        AggregatedResult result = new AggregatedResult(List.of(product), Map.of(), Map.of(), facets, 0, 0);
        when(scraperService.getLastResult()).thenReturn(result);
        registry = new ToolRegistry(new SearchProductsTool(scraperService),
                new ViewProductTool(scraperService), new ProposeReclassifyTool(scraperService));
    }

    @Test
    @DisplayName("canonical search→view→propose_reclassify flow: proposals collected, no write, in dependency order")
    void canonicalFlowCollectsProposalNoWrite() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        provider.enqueueToolCall(ViewProductTool.NAME, Map.of("url", "https://a.com/1"));
        provider.enqueueToolCall(ProposeReclassifyTool.NAME,
                Map.of("url", "https://a.com/1", "categoria", "Buzo"));
        provider.enqueueFinalAnswer("Te propongo cambiar la categoría a Buzo, ¿confirmás?");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(List.of(ChatMessage.user("corregí la zapatilla SAD Adidas")), null);

        assertThat(resp.assistantText()).contains("Buzo");
        assertThat(resp.proposals()).hasSize(1);
        assertThat(resp.proposals().get(0).categoriaPropuesta()).isEqualTo("Buzo");
        assertThat(provider.calledToolNamesInOrder())
                .containsExactly(SearchProductsTool.NAME, ViewProductTool.NAME, ProposeReclassifyTool.NAME);
        assertThat(resp.outcome()).isEqualTo(TurnOutcome.COMPLETE);
    }

    @Test
    @DisplayName("malformed/unknown tool call → is_error fed back, loop continues (no crash/500); "
            + "an only-error tool result does not count as grounding (outcome=UNGROUNDED, prose discarded)")
    void malformedToolCallIsErrorFedBackLoopContinues() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall("delete_everything", Map.of());
        provider.enqueueFinalAnswer("No pude ejecutar esa acción, pero seguí funcionando.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(List.of(ChatMessage.user("hacé algo raro")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(resp.assistantText()).isNotEqualTo("No pude ejecutar esa acción, pero seguí funcionando.");
        assertThat(resp.proposals()).isEmpty();
    }

    @Test
    @DisplayName("model answers without calling any tool → outcome=UNGROUNDED, model's prose discarded, proposals empty")
    void answerWithoutAnyToolCallIsUngrounded() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("Puedo ayudarte con lo que quieras.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(List.of(ChatMessage.user("dame consejos de moda")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(resp.assistantText()).isNotEqualTo("Puedo ayudarte con lo que quieras.");
        assertThat(resp.proposals()).isEmpty();
    }

    @Test
    @DisplayName("search_products succeeding with ZERO matches does not count as real grounding by itself "
            + "(model's prose is discarded, matching ViewProductTool's not-found semantics), but the turn is "
            + "still answered honestly as COMPLETE with a system-authored no-matches message, not rejected")
    void emptySearchResultDoesNotGroundButTurnIsStillAnsweredHonestly() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "marca-inexistente-xyz"));
        provider.enqueueFinalAnswer("Encontré una remera azul con 50% de descuento."); // untrusted, must be discarded

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(
                List.of(ChatMessage.user("¿tenés algo de la marca inexistente xyz?")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.COMPLETE);
        assertThat(resp.assistantText()).isNotEqualTo("Encontré una remera azul con 50% de descuento.");
        assertThat(resp.assistantText()).containsIgnoringCase("no encontr");
        assertThat(resp.proposals()).isEmpty();
    }

    @Test
    @DisplayName("recognized meta-intent short-circuits before the loop: outcome=CAPABILITY, zero provider calls")
    void metaIntentShortCircuitsWithZeroProviderCalls() {
        FakeChatProvider provider = new FakeChatProvider();

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(List.of(ChatMessage.user("hola")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.CAPABILITY);
        assertThat(resp.proposals()).isEmpty();
        assertThat(provider.callCount()).isZero();
    }

    @Test
    @DisplayName("loop exhaustion keeps proposals collected before exhausting (outcome=EXHAUSTED)")
    void exhaustionKeepsCollectedProposals() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        provider.enqueueToolCall(ViewProductTool.NAME, Map.of("url", "https://a.com/1"));
        provider.enqueueToolCall(ProposeReclassifyTool.NAME,
                Map.of("url", "https://a.com/1", "categoria", "Buzo"));
        // Never terminates on its own after that — forces MAX_ITERATIONS exhaustion.
        for (int i = 0; i < 10; i++) {
            provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        }

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(List.of(ChatMessage.user("corregí la zapatilla SAD Adidas")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.EXHAUSTED);
        assertThat(resp.proposals()).hasSize(1);
        assertThat(provider.callCount()).isEqualTo(CatalogAgentService.MAX_ITERATIONS);
    }

    @Test
    @DisplayName("provider throwing ProviderUnavailableException propagates out of run() uncaught")
    void providerFailurePropagatesUncaught() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueThrow(new ProviderUnavailableException(
                ProviderUnavailableException.Reason.UNREACHABLE, "boom"));

        CatalogAgentService service = new CatalogAgentService(provider, registry);

        assertThatThrownBy(() -> service.run(List.of(ChatMessage.user("buscame una remera")), null))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("MAX_ITERATIONS bound → graceful reply, never infinite loop")
    void maxIterationsBoundGracefulReply() {
        FakeChatProvider provider = new FakeChatProvider();
        // Always request another tool call — never terminates on its own.
        for (int i = 0; i < 20; i++) {
            provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        }

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(List.of(ChatMessage.user("segui buscando para siempre")), null);

        assertThat(resp.assistantText()).isNotBlank();
        assertThat(provider.callCount()).isEqualTo(CatalogAgentService.MAX_ITERATIONS);
    }

    @Test
    @DisplayName("run(history, model) forwards the model through to ChatProvider.next()")
    void runForwardsModelToProvider() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("ok");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(List.of(ChatMessage.user("buscame una remera")), "llama3.1:8b");

        assertThat(provider.lastModelUsed()).isEqualTo("llama3.1:8b");
    }

    // ── Fake ChatProvider test double ──────────────────────────────────

    private static class FakeChatProvider implements ChatProvider {
        /** Each element is either a {@link ChatResponse} or a {@link RuntimeException} to throw. */
        private final Deque<Object> script = new ArrayDeque<>();
        private final List<String> calledToolNames = new ArrayList<>();
        private String lastModelUsed;
        private int callCount = 0;

        void enqueueToolCall(String name, Map<String, Object> args) {
            var node = MAPPER.valueToTree(args);
            script.add(new ChatResponse("", List.of(new ToolCall("call_" + script.size(), name, node))));
        }

        void enqueueFinalAnswer(String text) {
            script.add(new ChatResponse(text, List.of()));
        }

        void enqueueThrow(RuntimeException ex) {
            script.add(ex);
        }

        List<String> calledToolNamesInOrder() { return calledToolNames; }
        int callCount() { return callCount; }
        String lastModelUsed() { return lastModelUsed; }

        @Override
        public ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model) {
            callCount++;
            lastModelUsed = model;
            Object next = script.isEmpty()
                    ? new ChatResponse("sin más pasos programados", List.of())
                    : script.poll();
            if (next instanceof RuntimeException re) {
                throw re;
            }
            ChatResponse resp = (ChatResponse) next;
            resp.toolCalls().forEach(tc -> calledToolNames.add(tc.name()));
            return resp;
        }

        @Override
        public List<String> listModels() { return List.of(); }
    }
}
