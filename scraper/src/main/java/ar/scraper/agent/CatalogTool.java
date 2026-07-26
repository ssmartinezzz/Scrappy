package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single read-only catalog tool the agent's tool-use loop can invoke
 * (llm-catalog-nlp, design D3/D4). Every implementation validates its
 * arguments at the boundary (Safeguard A) and returns a structured {@code
 * is_error} {@link ToolResult} instead of throwing — {@link ToolRegistry}
 * additionally wraps execution in a try/catch as a second line of defense.
 * {@code execute}'s returned {@link ToolResult#toolCallId()} is ignored;
 * the caller (loop/registry) fills in the real call id.
 */
public interface CatalogTool {
    ToolSpec spec();
    ToolResult execute(JsonNode args);
}
