package ar.scraper.agent;

/**
 * Result of executing a {@link ToolCall}, fed back into the conversation.
 * {@code isError} true means the tool boundary rejected the call (Safeguard
 * A — malformed args, unknown URL, non-taxonomy value); the loop feeds this
 * back to the model as a normal tool message so it can self-correct, never
 * throwing/500-ing (llm-catalog-nlp, design D3).
 */
public record ToolResult(String toolCallId, String content, boolean isError) {

    public static ToolResult ok(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false);
    }

    public static ToolResult error(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, true);
    }
}
