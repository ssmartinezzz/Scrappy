package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
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
 *
 * <p>The {@code agent-chat-continuity} block at the bottom covers multi-turn
 * transcript reconstruction: a past assistant turn's tool calls are replayed
 * against the LIVE catalog so the model sees a coherent, currently-true
 * transcript instead of bare prose.</p>
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
        product = producto("Zapatilla Running");
        when(scraperService.getLastResult()).thenReturn(snapshotWith(product));
        registry = new ToolRegistry(new SearchProductsTool(scraperService),
                new ViewProductTool(scraperService), new ProposeReclassifyTool(scraperService));
    }

    private static Product producto(String categoria) {
        return new Product("Sitio", "Zapatilla SAD Adidas", 1000, null, "https://a.com/1", "img",
                categoria, "hombre", List.of(), Product.MlScore.EMPTY, "Adidas",
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }

    private static AggregatedResult snapshotWith(Product p) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(List.of(p), Map.of(), Map.of(), facets, 0, 0);
    }

    private static ToolStep step(String toolName, Map<String, Object> args) {
        return new ToolStep(List.of(new ToolStep.Call(toolName, MAPPER.valueToTree(args))));
    }

    private static List<ConversationTurn> conversation(ConversationTurn... turns) {
        return List.of(turns);
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
        AgentChatResponse resp = service.run(
                conversation(ConversationTurn.user("corregí la zapatilla SAD Adidas")), null);

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
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("hacé algo raro")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(resp.assistantText()).isNotEqualTo("No pude ejecutar esa acción, pero seguí funcionando.");
        assertThat(resp.proposals()).isEmpty();
    }

    @Test
    @DisplayName("model answers without calling any tool → outcome=UNGROUNDED, model's prose discarded, proposals empty")
    void answerWithoutAnyToolCallIsUngrounded() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("Puedo ayudarte con lo que quieras.");
        provider.enqueueFinalAnswer("Igual te insisto: puedo ayudarte con lo que quieras.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("dame consejos de moda")), null);

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
                conversation(ConversationTurn.user("¿tenés algo de la marca inexistente xyz?")), null);

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
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("hola")), null);

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
        AgentChatResponse resp = service.run(
                conversation(ConversationTurn.user("corregí la zapatilla SAD Adidas")), null);

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

        assertThatThrownBy(() -> service.run(conversation(ConversationTurn.user("buscame una remera")), null))
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
        AgentChatResponse resp = service.run(
                conversation(ConversationTurn.user("segui buscando para siempre")), null);

        assertThat(resp.assistantText()).isNotBlank();
        assertThat(provider.callCount()).isEqualTo(CatalogAgentService.MAX_ITERATIONS);
    }

    @Test
    @DisplayName("run(conversation, model) forwards the model through to ChatProvider.next()")
    void runForwardsModelToProvider() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("ok");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(conversation(ConversationTurn.user("buscame una remera")), "llama3.1:8b");

        assertThat(provider.lastModelUsed()).isEqualTo("llama3.1:8b");
    }

    // ── agent-chat-continuity: grounding nudge ──────────────────────────

    @Test
    @DisplayName("a first ungrounded answer gets ONE corrective nudge; if the model then calls a tool "
            + "successfully the turn completes normally (the nudge fixes the false negative, it does not "
            + "launder grounding)")
    void ungroundedAnswerGetsOneCorrectiveNudgeAndCanRecover() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("La zapatilla está en categoría Zapatilla Running.");
        provider.enqueueToolCall(ViewProductTool.NAME, Map.of("url", "https://a.com/1"));
        provider.enqueueFinalAnswer("Confirmado: está en Zapatilla Running.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("¿en qué categoría está?")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.COMPLETE);
        assertThat(resp.assistantText()).isEqualTo("Confirmado: está en Zapatilla Running.");
    }

    @Test
    @DisplayName("the corrective nudge fires at most ONCE per turn: a model that keeps answering without "
            + "tools is still rejected as UNGROUNDED")
    void nudgeFiresAtMostOncePerTurn() {
        FakeChatProvider provider = new FakeChatProvider();
        for (int i = 0; i < 5; i++) provider.enqueueFinalAnswer("Te respondo igual sin usar herramientas.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("inventame algo")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(provider.callCount()).isEqualTo(2);
    }

    // ── agent-chat-continuity: multi-turn transcript replay ─────────────

    @Test
    @DisplayName("a past assistant turn's trace is replayed into the transcript as assistant(tool_calls) + "
            + "tool result pairs, so the model sees that its previous answer came from tools")
    void pastTurnTraceIsReplayedAsToolCallAndResultPairs() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        provider.enqueueFinalAnswer("Ahí va.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(conversation(
                ConversationTurn.user("mostrame la zapatilla"),
                ConversationTurn.assistant("Es una Zapatilla Running de Adidas.",
                        List.of(step(ViewProductTool.NAME, Map.of("url", "https://a.com/1")))),
                ConversationTurn.user("¿y de qué marca es?")), null);

        List<ChatMessage> sent = provider.firstHistory();
        assertThat(sent.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(sent.get(1).role()).isEqualTo(Role.USER);

        ChatMessage replayedCall = sent.get(2);
        assertThat(replayedCall.role()).isEqualTo(Role.ASSISTANT);
        assertThat(replayedCall.toolCalls()).singleElement()
                .extracting(ToolCall::name).isEqualTo(ViewProductTool.NAME);

        ChatMessage replayedResult = sent.get(3);
        assertThat(replayedResult.role()).isEqualTo(Role.TOOL);
        assertThat(replayedResult.toolCallId()).isEqualTo(replayedCall.toolCalls().get(0).id());
        assertThat(replayedResult.text()).contains("Adidas");

        assertThat(sent.get(4).role()).isEqualTo(Role.ASSISTANT);
        assertThat(sent.get(4).text()).isEqualTo("Es una Zapatilla Running de Adidas.");
        assertThat(sent.get(5).role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("replayed tool results are re-read from the LIVE catalog, never carried by the client — "
            + "a product reclassified between turns comes back with its NEW category")
    void replayedResultsAreReReadFromTheLiveCatalog() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("ok");
        provider.enqueueFinalAnswer("ok");

        // The confirmed reclassification landed between the two turns.
        when(scraperService.getLastResult()).thenReturn(snapshotWith(producto("Buzo")));

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(conversation(
                ConversationTurn.user("mostrame la zapatilla"),
                ConversationTurn.assistant("Está en Zapatilla Running.",
                        List.of(step(ViewProductTool.NAME, Map.of("url", "https://a.com/1")))),
                ConversationTurn.user("¿en qué categoría quedó?")), null);

        ChatMessage replayedResult = provider.firstHistory().get(3);
        assertThat(replayedResult.role()).isEqualTo(Role.TOOL);
        assertThat(replayedResult.text()).contains("Buzo");
        assertThat(replayedResult.text()).doesNotContain("Zapatilla Running");
    }

    @Test
    @DisplayName("replaying a past propose_reclassify never re-emits its proposal — only calls the model "
            + "issues in THIS turn produce proposal cards")
    void replayNeverReEmitsPastProposals() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        provider.enqueueFinalAnswer("Listo.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(
                ConversationTurn.user("corregí la zapatilla"),
                ConversationTurn.assistant("Te propuse pasarla a Buzo.",
                        List.of(step(ProposeReclassifyTool.NAME,
                                Map.of("url", "https://a.com/1", "categoria", "Buzo")))),
                ConversationTurn.user("gracias, ahora buscame otra")), null);

        assertThat(resp.proposals()).isEmpty();
    }

    @Test
    @DisplayName("a tool name the registry does not know is dropped from a client-supplied trace: never "
            + "executed, and no error text is injected into the replayed transcript")
    void unknownToolNameInClientTraceIsDropped() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        provider.enqueueFinalAnswer("Listo.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(conversation(
                ConversationTurn.user("hola"),
                ConversationTurn.assistant("Respuesta previa.",
                        List.of(new ToolStep(List.of(
                                new ToolStep.Call("delete_everything", MAPPER.createObjectNode()))))),
                ConversationTurn.user("seguimos")), null);

        assertThat(provider.firstHistory())
                .noneMatch(m -> m.role() == Role.TOOL)
                .noneMatch(m -> m.text() != null && m.text().contains("delete_everything"));
    }

    @Test
    @DisplayName("replay never launders grounding: a turn whose only tool activity is replayed history is "
            + "still rejected as UNGROUNDED when the model answers without calling anything itself")
    void replayDoesNotLaunderGrounding() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("Vale 1500 pesos el dólar.");
        provider.enqueueFinalAnswer("Te insisto: 1500 pesos.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(
                ConversationTurn.user("mostrame la zapatilla"),
                ConversationTurn.assistant("Es una Zapatilla Running.",
                        List.of(step(ViewProductTool.NAME, Map.of("url", "https://a.com/1")))),
                ConversationTurn.user("¿cuánto vale el dólar?")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(resp.assistantText()).doesNotContain("1500");
    }

    @Test
    @DisplayName("replay is bounded by MAX_REPLAY_CALLS, keeping the most RECENT turns and dropping the "
            + "oldest — a long conversation can't grow the transcript without limit")
    void replayIsBoundedKeepingTheMostRecentTurns() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("ok");
        provider.enqueueFinalAnswer("ok");

        List<ConversationTurn> longChat = new ArrayList<>();
        int pastTurns = CatalogAgentService.MAX_REPLAY_CALLS + 4;
        for (int i = 0; i < pastTurns; i++) {
            longChat.add(ConversationTurn.user("pregunta " + i));
            longChat.add(ConversationTurn.assistant("respuesta " + i,
                    List.of(step(ViewProductTool.NAME, Map.of("url", "https://a.com/1")))));
        }
        longChat.add(ConversationTurn.user("última"));

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(longChat, null);

        List<ChatMessage> sent = provider.firstHistory();
        long replayedResults = sent.stream().filter(m -> m.role() == Role.TOOL).count();
        assertThat(replayedResults).isEqualTo(CatalogAgentService.MAX_REPLAY_CALLS);

        // The window is the tail of the conversation: the LAST assistant turn kept its trace…
        int lastAssistant = sent.size() - 2; // the trailing user turn is last
        assertThat(sent.get(lastAssistant).text()).isEqualTo("respuesta " + (pastTurns - 1));
        assertThat(sent.get(lastAssistant - 1).role()).isEqualTo(Role.TOOL);
        // …while the very first one was dropped (its text survives, its trace does not).
        assertThat(sent.get(2).text()).isEqualTo("respuesta 0");
        assertThat(sent.get(2).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    @DisplayName("replay never contributes confirmedNoMatches either: a past turn whose search found "
            + "nothing cannot make a later toolless turn answer 'no encontré productos' — that message "
            + "is a claim about THIS turn's search")
    void replayDoesNotContributeConfirmedNoMatches() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("No hay nada de eso en el catálogo.");
        provider.enqueueFinalAnswer("Te insisto: no hay nada.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(
                ConversationTurn.user("¿tenés algo de la marca inexistente xyz?"),
                ConversationTurn.assistant("No encontré nada de esa marca.",
                        List.of(step(SearchProductsTool.NAME, Map.of("query", "marca-inexistente-xyz")))),
                ConversationTurn.user("¿y de Adidas?")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(resp.assistantText()).doesNotContain("No encontré productos que coincidan");
    }

    @Test
    @DisplayName("a turn whose OWN trace exceeds the whole budget is truncated to its most recent steps, "
            + "never dropped — dropping it collapsed replay to nothing exactly after the tool-heaviest turn")
    void overBudgetTurnIsTruncatedToItsMostRecentStepsNotDropped() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("ok");
        provider.enqueueFinalAnswer("ok");

        List<ToolStep> fatTrace = new ArrayList<>();
        int steps = CatalogAgentService.MAX_REPLAY_CALLS + 8;
        for (int i = 0; i < steps; i++) {
            fatTrace.add(step(ViewProductTool.NAME, Map.of("url", "https://a.com/1")));
        }

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(conversation(
                ConversationTurn.user("corregí todo el catálogo"),
                ConversationTurn.assistant("Revisé varios productos.", fatTrace),
                ConversationTurn.user("¿y ahora?")), null);

        List<ChatMessage> sent = provider.firstHistory();
        assertThat(sent.stream().filter(m -> m.role() == Role.TOOL).count())
                .isEqualTo(CatalogAgentService.MAX_REPLAY_CALLS);
        assertThat(sent).anyMatch(m -> m.role() == Role.TOOL && m.text().contains("Adidas"));
    }

    @Test
    @DisplayName("a single step bigger than the whole budget is truncated to its most recent calls, and "
            + "the replayed assistant message announces exactly the calls that get executed (well-formed wire)")
    void overBudgetSingleStepIsTruncatedAndStaysWellFormed() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("ok");
        provider.enqueueFinalAnswer("ok");

        List<ToolStep.Call> manyCalls = new ArrayList<>();
        for (int i = 0; i < CatalogAgentService.MAX_REPLAY_CALLS + 8; i++) {
            manyCalls.add(new ToolStep.Call(ViewProductTool.NAME,
                    MAPPER.valueToTree(Map.of("url", "https://a.com/1"))));
        }

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        service.run(conversation(
                ConversationTurn.user("mirá todo"),
                ConversationTurn.assistant("Miré varios.", List.of(new ToolStep(manyCalls))),
                ConversationTurn.user("¿y ahora?")), null);

        List<ChatMessage> sent = provider.firstHistory();
        ChatMessage announced = sent.stream()
                .filter(m -> m.role() == Role.ASSISTANT && !m.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        long results = sent.stream().filter(m -> m.role() == Role.TOOL).count();

        assertThat(announced.toolCalls()).hasSize(CatalogAgentService.MAX_REPLAY_CALLS);
        assertThat(results).isEqualTo(CatalogAgentService.MAX_REPLAY_CALLS);
    }

    @Test
    @DisplayName("a COMPLETE turn exports the tool calls it issued as a step-ordered trace, so the next "
            + "turn can replay them — errored calls are excluded")
    void completeTurnExportsOnlySuccessfulCallsAsTrace() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueToolCall(SearchProductsTool.NAME, Map.of("query", "zapatilla"));
        provider.enqueueToolCall(ViewProductTool.NAME, Map.of("url", "https://inexistente.com/9"));
        provider.enqueueToolCall(ViewProductTool.NAME, Map.of("url", "https://a.com/1"));
        provider.enqueueFinalAnswer("Es una Zapatilla Running.");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("mostrame la zapatilla")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.COMPLETE);
        assertThat(resp.trace()).hasSize(2);
        assertThat(resp.trace().get(0).calls()).singleElement()
                .extracting(ToolStep.Call::name).isEqualTo(SearchProductsTool.NAME);
        assertThat(resp.trace().get(1).calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.name()).isEqualTo(ViewProductTool.NAME);
                    assertThat(call.arguments().path("url").asText()).isEqualTo("https://a.com/1");
                });
    }

    @Test
    @DisplayName("a turn that produces no durable assistant message (UNGROUNDED) exports no trace")
    void ungroundedTurnExportsNoTrace() {
        FakeChatProvider provider = new FakeChatProvider();
        provider.enqueueFinalAnswer("sin herramientas");
        provider.enqueueFinalAnswer("sigo sin herramientas");

        CatalogAgentService service = new CatalogAgentService(provider, registry);
        AgentChatResponse resp = service.run(conversation(ConversationTurn.user("inventá algo")), null);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.UNGROUNDED);
        assertThat(resp.trace()).isEmpty();
    }

    // ── Fake ChatProvider test double ──────────────────────────────────

    private static class FakeChatProvider implements ChatProvider {
        /** Each element is either a {@link ChatResponse} or a {@link RuntimeException} to throw. */
        private final Deque<Object> script = new ArrayDeque<>();
        private final List<String> calledToolNames = new ArrayList<>();
        /** Every history this provider was handed, in order — lets a test assert on the reconstructed transcript. */
        private final List<List<ChatMessage>> histories = new ArrayList<>();
        private String lastModelUsed;
        private int callCount = 0;

        void enqueueToolCall(String name, Map<String, Object> args) {
            JsonNode node = MAPPER.valueToTree(args);
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
        List<ChatMessage> firstHistory() { return histories.get(0); }

        @Override
        public ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model) {
            callCount++;
            lastModelUsed = model;
            histories.add(List.copyOf(history));
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
