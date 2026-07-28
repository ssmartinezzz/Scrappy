package ar.scraper.agent;

import java.util.List;

/**
 * Response shape for {@code POST /api/agent/chat}: the assistant's final
 * reply for this turn, any pending reclassification proposals collected
 * during the (read-only) tool-use loop — zero writes have occurred by the
 * time this is returned (llm-catalog-nlp, design D4) — and this turn's
 * {@link TurnOutcome} (agent-chat-response-quality, design Part 1), which
 * distinguishes a grounded/complete answer from a meta-intent short-circuit,
 * a rejected ungrounded turn, or loop exhaustion.
 */
public record AgentChatResponse(String assistantText, List<ReclassifyProposal> proposals, TurnOutcome outcome) {}
