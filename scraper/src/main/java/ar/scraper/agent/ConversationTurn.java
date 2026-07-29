package ar.scraper.agent;

import java.util.List;

/**
 * One turn of the conversation as the CLIENT reports it (agent-chat-continuity)
 * — the transport-level input of {@link CatalogAgentService#run}, deliberately
 * distinct from {@link ChatMessage}, which is the provider-facing wire domain.
 *
 * <p>The distinction matters: a client may only ever author a {@code USER} or
 * an {@code ASSISTANT} turn. The system prompt, every tool call's transport
 * id, and every tool <em>result</em> are server-authored — none of them has a
 * representation here, so none of them can be injected from the browser.</p>
 *
 * <p>{@code trace} is the tool activity of a past assistant turn (empty for
 * user turns and for assistant turns that used no tools). It is what lets
 * {@link CatalogAgentService} rebuild a coherent transcript on every request
 * without holding server-side session state — the calls come back from the
 * client, the results are always re-executed against the live catalog.</p>
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
