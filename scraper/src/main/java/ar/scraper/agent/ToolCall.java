package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** A single tool invocation requested by the model, in domain terms. */
public record ToolCall(String id, String name, JsonNode arguments) {}
