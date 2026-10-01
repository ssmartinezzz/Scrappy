package ar.scraper.agent;

import java.util.List;

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
