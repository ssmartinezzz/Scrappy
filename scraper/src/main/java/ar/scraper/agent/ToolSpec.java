package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolSpec(String name, String description, JsonNode paramsSchema) {}
