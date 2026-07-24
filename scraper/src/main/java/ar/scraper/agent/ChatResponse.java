package ar.scraper.agent;

import java.util.List;

/**
 * A single {@link ChatProvider#next} turn's result: either a final
 * natural-language answer ({@code toolCalls} empty, {@link #done()} true)
 * or a request to execute one or more tool calls before continuing the
 * loop (llm-catalog-nlp, design D3).
 */
public record ChatResponse(String assistantText, List<ToolCall> toolCalls) {
    public boolean done() { return toolCalls == null || toolCalls.isEmpty(); }
}
