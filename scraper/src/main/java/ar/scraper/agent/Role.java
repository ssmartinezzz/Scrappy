package ar.scraper.agent;

/**
 * Provider-agnostic chat role (llm-catalog-nlp, design D1). Mirrors the
 * roles shared by both OpenAI-compatible ({@code system}/{@code user}/
 * {@code assistant}/{@code tool}) and Anthropic ({@code system}/{@code user}/
 * {@code assistant} with typed {@code tool_result} content blocks) wire
 * protocols — each {@link ChatProvider} adapter translates wire role
 * strings to/from this enum internally.
 */
public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
