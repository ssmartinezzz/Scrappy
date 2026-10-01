package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** A single read-only catalog tool the agent's tool-use loop can invoke. */
public interface CatalogTool {
    ToolSpec spec();
    ToolResult execute(JsonNode args);
}
