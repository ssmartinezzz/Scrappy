package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;

/**
 * A turn's trace is an ordered {@code List<ToolStep>}, and the step grouping is load-bearing —
 * flattening {@code search → view → propose} into one batch would tell the model those three calls
 * can be issued in parallel, which is exactly the mistake the system prompt asks it not to make
 * (proposing a reclassification for a url it has not looked up yet).
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
