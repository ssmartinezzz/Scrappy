package ar.scraper.agent;

import java.util.List;

/**
 * One turn of the conversation as the CLIENT reports it — the transport-level input of
 * {@link CatalogAgentService#run}, deliberately distinct from {@link ChatMessage}, which is the
 * provider-facing wire domain.
 */
public record ConversationTurn(Role role, String text, List<ToolStep> trace) {

    public ConversationTurn {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public static ConversationTurn user(String text) {
        return new ConversationTurn(Role.USER, text, List.of());
    }

    public static ConversationTurn assistant(String text, List<ToolStep> trace) {
        return new ConversationTurn(Role.ASSISTANT, text, trace);
    }
}
