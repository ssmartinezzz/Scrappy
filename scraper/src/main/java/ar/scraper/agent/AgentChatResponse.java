package ar.scraper.agent;

import java.util.List;

/**
 * Response shape for {@code POST /api/agent/chat}: the assistant's final reply for this turn, any
 * pending reclassification proposals collected during the (read-only) tool-use loop — zero writes
 * have occurred by the time this is returned — this turn's {@link TurnOutcome}, which distinguishes
 * a grounded/complete answer from a meta-intent short-circuit, a rejected ungrounded turn, or loop
 * exhaustion, and this turn's {@code trace}.
 */
public record AgentChatResponse(String assistantText, List<ReclassifyProposal> proposals,
                                TurnOutcome outcome, List<ToolStep> trace) {

    public AgentChatResponse {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public static AgentChatResponse withoutTrace(String assistantText, TurnOutcome outcome) {
        return new AgentChatResponse(assistantText, List.of(), outcome, List.of());
    }
}
