package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;

/**
 * One step of a turn's tool activity: the batch of calls the model issued in
 * a single {@link ChatProvider#next} round-trip (agent-chat-continuity).
 *
 * <p>A turn's trace is an ordered {@code List<ToolStep>}, and the step
 * grouping is load-bearing — flattening {@code search → view → propose} into
 * one batch would tell the model those three calls can be issued in
 * parallel, which is exactly the mistake the system prompt asks it not to
 * make (proposing a reclassification for a url it has not looked up yet).</p>
 *
 * <p>A step carries only what the model <em>asked for</em> — tool name and
 * arguments — never what the catalog <em>answered</em>. Results are always
 * re-read server-side from the live snapshot when a trace is replayed
 * ({@link CatalogAgentService}), so a client can never feed the model a
 * catalog fact the server did not produce, and a stale result can never
 * survive a reclassification.</p>
 */
public record ToolStep(List<Call> calls) {

    public ToolStep {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /** A single requested tool invocation, without the transport-scoped id. */
    public record Call(String name, JsonNode arguments) {
        public Call {
            arguments = arguments == null ? NullNode.instance : arguments;
        }
    }
}
