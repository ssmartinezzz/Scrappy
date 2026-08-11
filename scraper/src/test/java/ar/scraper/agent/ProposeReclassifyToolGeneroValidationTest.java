package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * normalize-db-schema-fks-1nf, slice A.3 (V6), design D7.
 *
 * <p>{@code ProposeReclassifyTool.java:80-85} already validates
 * {@code categoria} against a canonical set; {@code :89} passed
 * {@code genero} through raw and unnormalised — the write path that
 * produced the live capital-M {@code 'Mujer'} row (obs #839). This mirrors
 * that exact idiom (CODE-6: one taxonomy, one owner) for {@code genero}
 * against the same five-value domain the V6 CHECK constraint enforces:
 * {@code hombre/mujer/unisex/infantil/''}. Decision (explicit, not silent):
 * the tool REJECTS an out-of-domain/miscased value via its existing
 * {@link ToolResult#error} path rather than silently normalising it —
 * same behaviour as the {@code categoria} check it mirrors, and it keeps
 * "propose" honestly reporting what the LLM actually asked for instead of
 * quietly rewriting it.</p>
 */
@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("propose_reclassify — genero validated against the CHECK domain (V6)")
@DisplayName("ProposeReclassifyTool — genero domain validation")
class ProposeReclassifyToolGeneroValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"hombre", "mujer", "unisex", "infantil"})
    @DisplayName("every legal non-blank genero value → accepted, proposal carries it through")
    void legalGeneroIsAccepted(String genero) throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        Product current = producto("https://a.com/1", "Zapatilla SAD Adidas", "Zapatilla Running", "hombre");
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://a.com/1")
                .put("categoria", "Buzo")
                .put("genero", genero);
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isFalse();
        ReclassifyProposal proposal = MAPPER.readValue(result.content(), ReclassifyProposal.class);
        assertThat(proposal.generoPropuesto()).isEqualTo(genero);
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("no genero param → falls back to the current value, no validation error")
    void missingGeneroFallsBackToCurrent() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        Product current = producto("https://a.com/1", "Zapatilla SAD Adidas", "Zapatilla Running", "hombre");
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://a.com/1")
                .put("categoria", "Buzo");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isFalse();
        ReclassifyProposal proposal = MAPPER.readValue(result.content(), ReclassifyProposal.class);
        assertThat(proposal.generoPropuesto()).isEqualTo("hombre");
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("blank current genero (GenderResolver's abstention sentinel), no override → accepted, stays blank")
    void blankCurrentGeneroWithNoOverrideIsAccepted() throws Exception {
        // Blank is indistinguishable from "not provided" by this tool's existing
        // optionalText() idiom (same convention AgentEndpoints.agentApply already
        // uses for marca/subCategoria/genero: "(x != null && !x.isBlank()) ? x :
        // previo.x()") — an explicit genero="" argument can never override a
        // non-blank current value. That is a pre-existing convention, unchanged
        // by this slice; this test instead proves the domain-blank case itself
        // (current genero == "") is still accepted, not rejected as "out of domain".
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        Product current = producto("https://a.com/1", "Producto Sin Genero", "Zapatilla Running", "");
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://a.com/1")
                .put("categoria", "Buzo");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isFalse();
        ReclassifyProposal proposal = MAPPER.readValue(result.content(), ReclassifyProposal.class);
        assertThat(proposal.generoPropuesto()).isEqualTo("");
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mujer", "Femenino", "MUJER", "hombres", "nene"})
    @DisplayName("out-of-domain / unnormalised genero → is_error, no write")
    void outOfDomainGeneroIsError(String badGenero) {
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        Product current = producto("https://a.com/1", "Zapatilla SAD Adidas", "Zapatilla Running", "hombre");
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://a.com/1")
                .put("categoria", "Buzo")
                .put("genero", badGenero);
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("mujer"); // a real domain value should be listed
        verifyNoInteractions(db);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String url, String nombre, String categoria, String genero) {
        return new Product("Sitio", nombre, 1000, null, url, "img",
                categoria, genero, List.of(), Product.MlScore.EMPTY, "Adidas",
                "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }
}
