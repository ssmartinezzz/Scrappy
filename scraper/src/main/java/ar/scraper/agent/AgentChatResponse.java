package ar.scraper.agent;

import java.util.List;

/**
 * Response shape for {@code POST /api/agent/chat}: the assistant's final
 * reply for this turn plus any pending reclassification proposals collected
 * during the (read-only) tool-use loop — zero writes have occurred by the
 * time this is returned (llm-catalog-nlp, design D4).
 */
public record AgentChatResponse(String assistantText, List<ReclassifyProposal> proposals) {}
