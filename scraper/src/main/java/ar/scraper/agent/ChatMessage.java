package ar.scraper.agent;

import java.util.List;

/**
 * A single turn in the conversation, expressed in domain terms
 * (llm-catalog-nlp, design D1). {@code toolCalls} is populated on an
 * {@code ASSISTANT} message that requested tool execution; {@code toolCallId}
 * is populated on a {@code TOOL} message that carries a result back for a
 * specific prior call. Both are {@code null}/empty on plain text turns.
 */
public record ChatMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId) {

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text, List.of(), null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), null);
    }

    public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, text, toolCalls, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId);
    }
}
