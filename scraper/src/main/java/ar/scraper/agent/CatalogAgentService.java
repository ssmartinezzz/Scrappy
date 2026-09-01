package ar.scraper.agent;

import ar.scraper.aggregator.normalize.CategoryGroups;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <h2>Multi-turn continuity (agent-chat-continuity)</h2>
 *
 * <p>This service holds NO session state — every request rebuilds the whole
 * transcript. Previously that rebuild was lossy: the client could only send
 * back plain {@code user}/{@code assistant} text, so every past turn arrived
 * as bare prose with no sign that a tool had ever run. A model reading that
 * transcript imitates it — it stops calling tools and answers from context,
 * which the grounding guard then (correctly, given what it can see) rejects.
 * That is why a conversation used to work once and refuse everything after.</p>
 *
 * <p>The fix is {@link #replayInto}: each past assistant turn carries a
 * {@link ToolStep} trace of the calls it issued, and those calls are
 * <em>re-executed here</em> against the live catalog before the provider is
 * contacted. The client therefore round-trips only what the model ASKED
 * ({@code name} + {@code arguments}), never what the catalog ANSWERED. Two
 * properties fall out of that, and both matter:</p>
 * <ul>
 *   <li>A browser can never feed the model a catalog fact the server did not
 *       just produce — replayed evidence is as trustworthy as live evidence.</li>
 *   <li>Replayed evidence is <em>current</em>: after a confirmed
 *       reclassification, a replayed {@code view_product} returns the NEW
 *       category, so the conversation cannot keep reasoning over the value
 *       it saw three turns ago.</li>
 * </ul>
 *
 * <p>Replay deliberately does NOT set {@code grounded}. Grounding stays
 * strictly per-turn, exactly as {@code agent-chat-response-quality} defined
 * it: the model must touch the catalog to earn delivery of its own prose.
 * Replay removes the <em>cause</em> of the false rejections (an incoherent
 * transcript); it is not a licence to skip the guard. The one remaining
 * false negative — a model that could have answered from replayed context —
 * is handled by a single corrective nudge before rejection, so a legitimate
 * follow-up costs one extra round-trip instead of failing.</p>
 */
@Component
public class CatalogAgentService {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bounded iteration limit (design D3) — never an infinite loop. */
    public static final int MAX_ITERATIONS = 6;

    /**
     * Upper bound on tool calls re-executed from the conversation's history in
     * one request. Bounds both the work done before the first provider call
     * and — the binding constraint — how much replayed tool output can crowd
     * a local model's context window. The window is the TAIL of the
     * conversation: recent turns are what a follow-up question refers to.
     */
    public static final int MAX_REPLAY_CALLS = 12;

    /**
     * Sent once per turn when the model answers without having touched the
     * catalog. Phrased as an instruction rather than an error so the model
     * corrects course instead of apologising — the rejection below is what
     * happens if it ignores this.
     */
    private static final String GROUNDING_NUDGE = """
            No ejecutaste ninguna herramienta en este turno, así que tu respuesta no se le va a entregar \
            al usuario. Volvé a responder la última pregunta usando primero al menos una herramienta \
            (search_products, view_product o propose_reclassify), incluso si creés que ya tenés el dato \
            más arriba en la conversación.""";

    private final ChatProvider provider;
    private final ToolRegistry registry;

    public CatalogAgentService(ChatProvider provider, ToolRegistry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    /**
     * Runs the loop for one user turn.
     *
     * @param conversation the full conversation as the client reports it, the
     *                     current user message last. Past assistant turns may
     *                     carry a {@link ToolStep} trace, which is replayed
     *                     against the live catalog (see class javadoc).
     * @param model model id override for this call, or {@code null} to use
     *              the provider's env-configured default (D8) — forwarded
     *              verbatim to every {@link ChatProvider#next} call in this
     *              run.
     */
    public AgentChatResponse run(List<ConversationTurn> conversation, String model) {
        String lastUserText = lastUserText(conversation);
        if (MetaIntents.matches(lastUserText)) {
            return AgentChatResponse.withoutTrace(cannedHelpText(), TurnOutcome.CAPABILITY);
        }

        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(systemPrompt()));
        replayInto(history, conversation);

        List<ToolSpec> tools = registry.specs();
        List<ReclassifyProposal> proposals = new ArrayList<>();
        // This turn's own tool activity, step by step — exported to the client
        // so the NEXT turn can replay it. Only successful calls are recorded:
        // replaying a call that already failed would re-inject a dead end into
        // the transcript and spend budget re-learning it.
        List<ToolStep> trace = new ArrayList<>();
        // grounded == true means at least one tool call THIS TURN actually
        // returned real catalog data (a matched product, a found view, a
        // proposal diff) — this is what licenses delivering the model's own
        // prose as COMPLETE. A search_products call that succeeds but matches
        // ZERO products does NOT set this: it carries no product data, so on
        // its own it must not unlock arbitrary model-authored prose (see
        // confirmedNoMatches below for how that legitimate case is still
        // answered honestly, without trusting the model's free text).
        boolean grounded = false;
        // True when at least one search_products call executed successfully
        // and truthfully found zero matches — a real, useful answer ("no
        // tengo eso en el catálogo") that must remain deliverable even though
        // it does not set `grounded` above (Safeguard consistency with
        // ViewProductTool's not-found handling). Unlike `grounded`, this can
        // never come from replay: "no encontré nada" is a claim about the
        // search THIS turn ran, not about one three turns ago.
        boolean confirmedNoMatches = false;
        // The corrective nudge fires at most once per turn — see GROUNDING_NUDGE.
        boolean nudged = false;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ChatResponse response = provider.next(history, tools, model);

            if (response.done()) {
                if (grounded) {
                    return new AgentChatResponse(response.assistantText(), proposals,
                            TurnOutcome.COMPLETE, trace);
                }
                if (confirmedNoMatches) {
                    // The model's own prose is still discarded here (it is
                    // just as untrusted as in the ungrounded case), but the
                    // turn itself is a legitimate, answerable "no results"
                    // outcome, not a rejection.
                    return new AgentChatResponse(noMatchesMessage(), proposals,
                            TurnOutcome.COMPLETE, trace);
                }
                if (!nudged && i < MAX_ITERATIONS - 1) {
                    nudged = true;
                    history.add(ChatMessage.assistant(response.assistantText(), List.of()));
                    history.add(ChatMessage.system(GROUNDING_NUDGE));
                    continue;
                }
                return rejectUngrounded(response.assistantText());
            }

            history.add(ChatMessage.assistant(response.assistantText(), response.toolCalls()));
            List<ToolStep.Call> succeeded = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                ToolResult result = registry.execute(call);
                history.add(ChatMessage.toolResult(result.toolCallId(), result.content()));
                if (!result.isError()) {
                    succeeded.add(new ToolStep.Call(call.name(), call.arguments()));
                    if (isEmptySearchResult(call, result)) {
                        confirmedNoMatches = true;
                    } else {
                        grounded = true;
                    }
                    if (ProposeReclassifyTool.NAME.equals(call.name())) {
                        collectProposal(result, proposals);
                    }
                }
            }
            if (!succeeded.isEmpty()) trace.add(new ToolStep(succeeded));
        }

        LOG.warn("[Agent] MAX_ITERATIONS ({}) reached without a final answer", MAX_ITERATIONS);
        return new AgentChatResponse(
                "No pude completar la solicitud en el límite de pasos permitidos. "
                        + "Probá reformular el pedido o acotar el alcance.",
                proposals, TurnOutcome.EXHAUSTED, List.of());
    }

    // ── Transcript reconstruction ───────────────────────────────────────

    /**
     * Rebuilds the conversation into provider-facing {@link ChatMessage}s,
     * re-executing each replayable past turn's tool calls so their results are
     * read from the live catalog rather than carried by the client.
     *
     * <p>Deliberately side-effect free with respect to this turn's grounding
     * and proposals: replaying a past {@code propose_reclassify} regenerates
     * its diff for the model to read, but never re-emits a proposal card the
     * user already answered.</p>
     */
    private void replayInto(List<ChatMessage> history, List<ConversationTurn> conversation) {
        Map<Integer, List<ToolStep>> window = selectReplayWindow(conversation);
        int callSeq = 0;

        for (int t = 0; t < conversation.size(); t++) {
            ConversationTurn turn = conversation.get(t);
            if (turn.role() != Role.ASSISTANT) {
                history.add(ChatMessage.user(turn.text()));
                continue;
            }
            for (ToolStep step : window.getOrDefault(t, List.of())) {
                List<ToolCall> calls = new ArrayList<>();
                for (ToolStep.Call call : step.calls()) {
                    // Unknown names are dropped rather than executed: the
                    // registry's "unknown tool" error is a self-correction
                    // signal for the live loop, not transcript material.
                    // Logged because it is never expected from our own
                    // client — it means a stale tab or a tampered payload.
                    if (!registry.knows(call.name())) {
                        LOG.warn("[Agent] Replay: descarto '{}' — no es una herramienta registrada", call.name());
                        continue;
                    }
                    calls.add(new ToolCall("replay_" + (callSeq++), call.name(), call.arguments()));
                }
                // An assistant message announcing tool_calls MUST be followed
                // by one tool message per call — emitting an empty batch would
                // put a malformed pair on the wire.
                if (calls.isEmpty()) continue;
                history.add(ChatMessage.assistant("", calls));
                for (ToolCall call : calls) {
                    ToolResult result = registry.execute(call);
                    history.add(ChatMessage.toolResult(result.toolCallId(), result.content()));
                }
            }
            history.add(ChatMessage.assistant(turn.text(), List.of()));
        }
    }

    /**
     * Picks, per past assistant turn, which of its steps get replayed — walking
     * backwards from the most recent within {@link #MAX_REPLAY_CALLS}.
     *
     * <p>The window is a contiguous TAIL of the conversation. A hole in the
     * middle would show the model a transcript where it sometimes used tools
     * and sometimes conjured the same kind of answer out of nothing — the exact
     * pattern this change exists to stop teaching it.</p>
     *
     * <p>A turn too big to fit whole is TRUNCATED to its most recent steps
     * rather than dropped. Dropping it would fail worst exactly where it costs
     * most: a turn whose own tool use exceeds the budget is the tool-heaviest
     * turn in the conversation, and the one a follow-up question is most likely
     * to be about — so the naive "skip what doesn't fit" rule collapsed replay
     * to nothing precisely when it mattered.</p>
     */
    private static Map<Integer, List<ToolStep>> selectReplayWindow(List<ConversationTurn> conversation) {
        Map<Integer, List<ToolStep>> window = new HashMap<>();
        int budget = MAX_REPLAY_CALLS;
        for (int t = conversation.size() - 1; t >= 0 && budget > 0; t--) {
            ConversationTurn turn = conversation.get(t);
            if (turn.role() != Role.ASSISTANT || turn.trace().isEmpty()) continue;

            List<ToolStep> fitted = fitWithin(turn.trace(), budget);
            if (fitted.isEmpty()) break;
            window.put(t, fitted);
            budget -= callCount(fitted);

            if (callCount(fitted) != callCount(turn.trace())) {
                // Expected on any long conversation, but worth a line: from
                // here backwards every turn degrades to bare prose, which is
                // precisely the shape that made this bug invisible before.
                LOG.debug("[Agent] Replay: el turno {} entra recortado ({} de {} llamadas); el presupuesto "
                                + "de {} se agota ahí y los turnos previos van sin traza",
                        t, callCount(fitted), callCount(turn.trace()), MAX_REPLAY_CALLS);
                break;
            }
        }
        return window;
    }

    /**
     * The longest TAIL of {@code steps} whose calls fit {@code budget}. If not
     * even the last step fits whole, its most recent calls are kept: the
     * replayed assistant message announces exactly the calls {@link #replayInto}
     * goes on to execute, so a partially-kept step is still well-formed on the
     * wire (one tool message per announced call).
     */
    private static List<ToolStep> fitWithin(List<ToolStep> steps, int budget) {
        Deque<ToolStep> kept = new ArrayDeque<>();
        int spent = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            List<ToolStep.Call> calls = steps.get(i).calls();
            if (spent + calls.size() <= budget) {
                kept.addFirst(steps.get(i));
                spent += calls.size();
                continue;
            }
            int room = budget - spent;
            if (kept.isEmpty() && room > 0) {
                kept.addFirst(new ToolStep(calls.subList(calls.size() - room, calls.size())));
            }
            break;
        }
        return List.copyOf(kept);
    }

    private static int callCount(List<ToolStep> steps) {
        return steps.stream().mapToInt(step -> step.calls().size()).sum();
    }

    // ── Turn outcomes ───────────────────────────────────────────────────

    private AgentChatResponse rejectUngrounded(String discardedProse) {
        LOG.warn("[Agent] Turno rechazado por falta de grounding — respuesta descartada: {}",
                discardedProse == null ? "" : discardedProse.substring(0, Math.min(200, discardedProse.length())));
        return AgentChatResponse.withoutTrace(
                "No pude responder eso con datos reales del catálogo. Probá pedirme que busque, "
                        + "mire o corrija un producto puntual.",
                TurnOutcome.UNGROUNDED);
    }

    private String lastUserText(List<ConversationTurn> conversation) {
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ConversationTurn turn = conversation.get(i);
            if (turn.role() == Role.USER) return turn.text();
        }
        return "";
    }

    /**
     * True iff {@code call} was {@link SearchProductsTool#NAME} and its
     * (non-error) result is a well-formed, genuinely empty match array —
     * {@code search_products} always returns {@code ok} for a syntactically
     * valid query regardless of match count, unlike {@link ViewProductTool}
     * which only returns {@code ok} when it actually found the requested
     * product. Distinguishing "found nothing" from "found real data" here is
     * what keeps the two read tools consistent about what counts as grounding.
     */
    private boolean isEmptySearchResult(ToolCall call, ToolResult result) {
        if (!SearchProductsTool.NAME.equals(call.name())) return false;
        try {
            return MAPPER.readTree(result.content()).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String noMatchesMessage() {
        return "No encontré productos que coincidan con esa búsqueda en el catálogo actual. "
                + "Probá con otro término, otra marca o una categoría distinta.";
    }

    private String cannedHelpText() {
        return "¡Hola! Puedo ayudarte a revisar y corregir la clasificación (categoría, subcategoría, "
                + "marca, género) de productos reales del catálogo. Pedime, por ejemplo, que busque un "
                + "producto o que revise su categoría actual.";
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

                Hacés dos cosas: BUSCAR productos en el catálogo y CORREGIR su clasificación.

                Para buscar, usá search_products y pasale TODOS los criterios que pida el usuario, cada uno \
                en su parámetro. Nunca traigas una lista amplia para después descartar a mano en tu respuesta: \
                lo que no filtró el catálogo no está filtrado. Para "musculosas que no sean de fútbol y por \
                menos de $50.000" la llamada correcta es una sola: \
                categoria="Musculosa", excluir=["futbol"], precioMax=50000.

                Ojo con una cosa: 'categoria' es la categoría CLASIFICADA del producto, y el nombre puede no \
                contener esa palabra — una "Remera sin mangas Dry Fit" puede estar clasificada como Musculosa. \
                Si el usuario nombra un tipo de prenda, va en 'categoria', no en 'query'. Usá 'query' para \
                texto libre: un modelo, una marca, una palabra suelta.

                Flujo esperado para corregir una clasificación: primero buscá el producto (search_products), \
                después mirá su clasificación actual (view_product), y recién ahí proponé el cambio \
                (propose_reclassify). Esa última herramienta NUNCA escribe en la base de datos — solo genera \
                una propuesta (valor actual → valor propuesto) que el usuario debe confirmar explícitamente en \
                la interfaz antes de que se aplique ningún cambio real.

                Nunca respondas una pregunta sobre el catálogo sin haber usado antes al menos una herramienta \
                con resultado válido — una respuesta sin ninguna herramienta ejecutada exitosamente va a ser \
                descartada y no le va a llegar al usuario. Esto vale en CADA turno por separado: aunque más \
                arriba en la conversación ya haya resultados de herramientas, para responder de nuevo tenés \
                que volver a consultarlos. Si la pregunta no requiere catálogo, usá las herramientas igual \
                para fundamentar tu respuesta.
                """.formatted(categorias);
    }
}
