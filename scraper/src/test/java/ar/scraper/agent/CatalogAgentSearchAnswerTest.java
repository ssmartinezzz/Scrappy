package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Search-only turns deliver a server-rendered listing; the model's prose is not trusted for it. */
@DisplayName("CatalogAgentService — server-rendered search answers")
class CatalogAgentSearchAnswerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROSE = "PROSA DEL MODELO que ignora las filas";

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        ScraperService scraper = mock(ScraperService.class);
        Product p = new Product("Compragamer", "Zapatilla SAD Adidas", 264400, 300000.0, "https://a.com/1",
                "img", "Zapatilla Running", "hombre", List.of(), Product.MlScore.EMPTY, "Adidas",
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        when(scraper.getLastResult()).thenReturn(new AggregatedResult(List.of(p), Map.of(), Map.of(), facets, 0, 0));
        registry = new ToolRegistry(new SearchProductsTool(scraper), new ViewProductTool(scraper),
                new ProposeReclassifyTool(scraper), new ProposePcTool(scraper, new RecommendationService()));
    }

    private AgentChatResponse run(Script script) {
        return new CatalogAgentService(script, registry)
                .run(List.of(ConversationTurn.user("zapatillas adidas")), null);
    }

    @Test
    @DisplayName("search-only turn: the rendered listing replaces the model's prose; outcome and trace unchanged")
    void searchOnlyDeliversRenderedText() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla")).say(PROSE);

        AgentChatResponse resp = run(s);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.COMPLETE);
        assertThat(resp.assistantText()).doesNotContain("PROSA").startsWith("Encontré 1 producto:")
                .contains("- [Zapatilla SAD Adidas](https://a.com/1) — Compragamer — $264.400 — **−12%** (antes $300.000)");
        assertThat(resp.trace()).hasSize(1);
    }

    @Test
    @DisplayName("the LAST non-empty search of the turn is the one rendered")
    void lastNonEmptySearchWins() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla"))
                .call(SearchProductsTool.NAME, Map.of("query", "marca-inexistente-xyz"))
                .say(PROSE);

        AgentChatResponse resp = run(s);

        assertThat(resp.assistantText()).startsWith("Encontré 1 producto:");
    }

    @Test
    @DisplayName("a turn with propose_reclassify keeps the model's prose")
    void proposalTurnKeepsProse() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla"))
                .call(ProposeReclassifyTool.NAME, Map.of("url", "https://a.com/1", "categoria", "Buzo"))
                .say(PROSE);

        AgentChatResponse resp = run(s);

        assertThat(resp.assistantText()).isEqualTo(PROSE);
        assertThat(resp.proposals()).hasSize(1);
    }

    @Test
    @DisplayName("a turn with only view_product keeps the model's prose")
    void viewOnlyTurnKeepsProse() {
        Script s = new Script().call(ViewProductTool.NAME, Map.of("url", "https://a.com/1")).say(PROSE);

        assertThat(run(s).assistantText()).isEqualTo(PROSE);
    }

    @Test
    @DisplayName("a turn with propose_pc keeps the model's prose even after a search")
    void proposePcTurnKeepsProse() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla"))
                .call(ProposePcTool.NAME, Map.of("presupuesto", 0)).say(PROSE);

        assertThat(run(s).assistantText()).isEqualTo(PROSE);
    }

    @Test
    @DisplayName("an errored search (unknown key) followed by a corrected one renders the corrected rows")
    void selfCorrectedSearchIsRendered() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla", "marca", "Adidas"))
                .call(SearchProductsTool.NAME, Map.of("query", "zapatilla adidas")).say(PROSE);

        assertThat(run(s).assistantText()).startsWith("Encontré 1 producto:");
    }

    @Test
    @DisplayName("replayed searches from earlier turns are not rendered: a turn with no search of its own keeps its prose")
    void replayIsNotRendered() {
        Script s = new Script().call(ViewProductTool.NAME, Map.of("url", "https://a.com/1")).say(PROSE);
        ToolStep prior = new ToolStep(List.of(new ToolStep.Call(SearchProductsTool.NAME,
                MAPPER.valueToTree(Map.of("query", "zapatilla")))));

        AgentChatResponse resp = new CatalogAgentService(s, registry).run(List.of(
                ConversationTurn.user("zapatillas"),
                ConversationTurn.assistant("listado previo", List.of(prior)),
                ConversationTurn.user("mostrame la primera")), null);

        assertThat(resp.assistantText()).isEqualTo(PROSE);
    }

    @Test
    @DisplayName("the filters line comes from the arguments of the search whose rows are rendered, not from the prose")
    void filtersComeFromTheRenderedSearchArguments() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "marca-inexistente-xyz", "precioMax", 1))
                .call(SearchProductsTool.NAME, Map.of("query", "zapatilla", "precioMax", 400000, "enOferta", true))
                .say("filtré por precio menos de 5");

        String text = run(s).assistantText();

        assertThat(text.lines().toList().get(1)).isEqualTo("Filtré por: “zapatilla” · hasta $400.000 · en oferta");
    }

    @Test
    @DisplayName("a search plus only no-op proposals: the search grounds the turn and the rendered listing is delivered")
    void noOpProposalsLeaveTheSearchAnswer() {
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla"))
                .call(ProposeReclassifyTool.NAME, Map.of("url", "https://a.com/1", "categoria", "Zapatilla Running"))
                .call(ProposeReclassifyTool.NAME, Map.of("url", "https://a.com/1", "categoria", "Zapatilla Running"))
                .say(PROSE);

        AgentChatResponse resp = run(s);

        assertThat(resp.outcome()).isEqualTo(TurnOutcome.COMPLETE);
        assertThat(resp.assistantText()).startsWith("Encontré 1 producto:").doesNotContain("PROSA");
        assertThat(resp.proposals()).isEmpty();
        assertThat(resp.trace()).hasSize(1); // only the search; errored calls are not traced
    }

    @Test
    @DisplayName("identical proposals in one turn are kept once; different ones for the same url are both kept")
    void identicalProposalsAreDeduplicated() {
        Map<String, Object> buzo = Map.of("url", "https://a.com/1", "categoria", "Buzo");
        Map<String, Object> buzoNike = Map.of("url", "https://a.com/1", "categoria", "Buzo", "marca", "Nike");
        Script s = new Script().call(SearchProductsTool.NAME, Map.of("query", "zapatilla"))
                .call(ProposeReclassifyTool.NAME, buzo)
                .call(ProposeReclassifyTool.NAME, buzo)
                .call(ProposeReclassifyTool.NAME, buzoNike)
                .call(ProposeReclassifyTool.NAME, buzoNike)
                .say(PROSE);

        AgentChatResponse resp = run(s);

        assertThat(resp.proposals()).extracting(ReclassifyProposal::marcaPropuesta)
                .containsExactly("Adidas", "Nike");
    }

    private static final class Script implements ChatProvider {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        Script call(String name, Map<String, Object> args) {
            JsonNode node = MAPPER.valueToTree(args);
            script.add(new ChatResponse("", List.of(new ToolCall("c" + script.size(), name, node))));
            return this;
        }

        Script say(String text) {
            script.add(new ChatResponse(text, List.of()));
            return this;
        }

        @Override
        public ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model) {
            return script.isEmpty() ? new ChatResponse("sin más pasos", List.of()) : script.poll();
        }

        @Override
        public List<String> listModels() { return List.of(); }
    }
}
