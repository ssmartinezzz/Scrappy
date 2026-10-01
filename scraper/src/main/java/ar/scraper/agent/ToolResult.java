package ar.scraper.agent;

/**
 * {@code isError} true means the tool boundary rejected the call; the loop feeds this back to the
 * model as a normal tool message so it can self-correct, never throwing/500-ing.
 */
public record ToolResult(String toolCallId, String content, boolean isError) {

    public static ToolResult ok(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false);
    }

    public static ToolResult error(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, true);
    }
}
