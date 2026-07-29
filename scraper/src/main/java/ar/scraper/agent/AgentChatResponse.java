package ar.scraper.agent;

import java.util.List;

/**
 * Response shape for {@code POST /api/agent/chat}: the assistant's final
 * reply for this turn, any pending reclassification proposals collected
 * during the (read-only) tool-use loop — zero writes have occurred by the
 * time this is returned (llm-catalog-nlp, design D4) — this turn's
 * {@link TurnOutcome} (agent-chat-response-quality, design Part 1), which
 * distinguishes a grounded/complete answer from a meta-intent short-circuit,
 * a rejected ungrounded turn, or loop exhaustion, and this turn's
 * {@code trace} (agent-chat-continuity).
 *
 * <p>{@code trace} lists the tool calls this turn issued <em>successfully</em>,
 * grouped by loop step. The client stores it alongside the assistant message
 * and sends it back on the next turn, which is what lets the server rebuild a
 * transcript where the model can see that its previous answers came from
 * tools. Only a turn that actually reached the tools exports one — in practice
 * {@code COMPLETE}. The other three outcomes export nothing, for two different
 * reasons: {@code UNGROUNDED} and {@code EXHAUSTED} leave no durable message
 * in the conversation at all (the client renders them as ephemeral notices,
 * never appending them to the transcript), while {@code CAPABILITY} DOES leave
 * a message — the canned help text — but short-circuits before any tool runs,
 * so it has nothing to trace.</p>
 */
public record AgentChatResponse(String assistantText, List<ReclassifyProposal> proposals,
                                TurnOutcome outcome, List<ToolStep> trace) {

    public AgentChatResponse {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    /** A turn with no tool activity to export: no proposals, no trace. */
    public static AgentChatResponse withoutTrace(String assistantText, TurnOutcome outcome) {
        return new AgentChatResponse(assistantText, List.of(), outcome, List.of());
    }
}
