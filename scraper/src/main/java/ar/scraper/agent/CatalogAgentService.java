package ar.scraper.agent;

import ar.scraper.aggregator.normalize.CategoryGroups;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, non-streaming, READ-ONLY tool-use loop (llm-catalog-nlp, design
 * D3/D4/D8) — the ONLY place the {@link ChatProvider} + {@link
 * ToolRegistry} are wired together. Every tool inside this loop is
 * read-only (search/view/propose); {@code propose_reclassify} never writes,
 * it only produces a {@link ReclassifyProposal} that this service collects
 * and surfaces in the returned {@link AgentChatResponse} — the ONLY write
 * path is the separate, out-of-loop {@code POST /api/agent/apply} endpoint.
 *
 * <p>A malformed/unknown tool call never crashes the loop: {@link
 * ToolRegistry#execute} always returns a structured {@code is_error} result
 * that gets fed back to the model as a normal tool message, so the model
 * can self-correct within the iteration budget. If the budget is exhausted
 * without a final answer, a graceful partial reply is returned instead of
 * looping forever or throwing.</p>
 */
@Component
public class CatalogAgentService {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bounded iteration limit (design D3) — never an infinite loop. */
    public static final int MAX_ITERATIONS = 6;

    private final ChatProvider provider;
    private final ToolRegistry registry;

    public CatalogAgentService(ChatProvider provider, ToolRegistry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    /**
     * Runs the loop for one user turn.
     *
     * @param model model id override for this call, or {@code null} to use
     *              the provider's env-configured default (D8) — forwarded
     *              verbatim to every {@link ChatProvider#next} call in this
     *              run.
     */
    public AgentChatResponse run(List<ChatMessage> userHistory, String model) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(systemPrompt()));
        history.addAll(userHistory);

        List<ToolSpec> tools = registry.specs();
        List<ReclassifyProposal> proposals = new ArrayList<>();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ChatResponse response = provider.next(history, tools, model);

            if (response.done()) {
                return new AgentChatResponse(response.assistantText(), proposals);
            }

            history.add(ChatMessage.assistant(response.assistantText(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                ToolResult result = registry.execute(call);
                history.add(ChatMessage.toolResult(result.toolCallId(), result.content()));
                if (!result.isError() && ProposeReclassifyTool.NAME.equals(call.name())) {
                    collectProposal(result, proposals);
                }
            }
        }

        LOG.warn("[Agent] MAX_ITERATIONS ({}) reached without a final answer", MAX_ITERATIONS);
        return new AgentChatResponse(
                "No pude completar la solicitud en el límite de pasos permitidos. "
                        + "Probá reformular el pedido o acotar el alcance.",
                proposals);
    }

    /** Discovers models available from the active provider (D8). */
    public List<String> listModels() {
        return provider.listModels();
    }

    private void collectProposal(ToolResult result, List<ReclassifyProposal> proposals) {
        try {
            proposals.add(MAPPER.readValue(result.content(), ReclassifyProposal.class));
        } catch (Exception e) {
            LOG.warn("[Agent] No se pudo parsear la propuesta de reclasificación: {}", e.getMessage());
        }
    }

    private String systemPrompt() {
        String categorias = String.join(", ",
                CategoryGroups.canonicalCategories().stream().sorted().toList());
        return """
                Sos un asistente que ayuda a revisar y corregir la clasificación (categoría, subcategoría, \
                marca, género) de productos reales de un catálogo. SOLO podés usar las herramientas provistas \
                — nunca inventes datos ni categorías. Las categorías válidas son EXACTAMENTE estas (no uses \
                ninguna otra): %s.

                Flujo esperado para corregir una clasificación: primero buscá el producto (search_products), \
                después mirá su clasificación actual (view_product), y recién ahí proponé el cambio \
                (propose_reclassify). Esa última herramienta NUNCA escribe en la base de datos — solo genera \
                una propuesta (valor actual → valor propuesto) que el usuario debe confirmar explícitamente en \
                la interfaz antes de que se aplique ningún cambio real.
                """.formatted(categorias);
    }
}
