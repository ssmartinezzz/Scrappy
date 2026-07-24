package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Domain-level tool definition handed to {@link ChatProvider#next} — each
 * adapter translates this into its own wire shape (OpenAI {@code function}
 * object, Anthropic {@code tool} block, etc). {@code paramsSchema} is a
 * JSON Schema object (as used by both wire protocols for parameter
 * validation hints to the model).
 */
public record ToolSpec(String name, String description, JsonNode paramsSchema) {}
