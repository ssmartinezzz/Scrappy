package ar.scraper.agent;

import com.fasterxml.jackson.databind.node.NullNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registers the exactly-4 read-only catalog tools: */
@Component
public class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, CatalogTool> tools = new LinkedHashMap<>();

    public ToolRegistry(SearchProductsTool search, ViewProductTool view, ProposeReclassifyTool propose,
                         ProposePcTool proposePc) {
        tools.put(search.spec().name(), search);
        tools.put(view.spec().name(), view);
        tools.put(propose.spec().name(), propose);
        tools.put(proposePc.spec().name(), proposePc);
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(CatalogTool::spec).toList();
    }

    /**
     * Used when replaying a client-supplied trace: an unknown name there is dropped BEFORE
     * execution rather than run through {@link #execute}, whose "Herramienta desconocida" error is
     * meant for a model self-correcting inside the loop — injecting it into a replayed transcript
     * would teach the model a failure it never actually made.
     */
    public boolean knows(String name) {
        return tools.containsKey(name);
    }

    public ToolResult execute(ToolCall call) {
        CatalogTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(),
                    "Herramienta desconocida: '" + call.name() + "'. Herramientas disponibles: "
                            + String.join(", ", tools.keySet()));
        }
        LOG.debug("[Agent] tool={} args={}", call.name(), call.arguments());
        try {
            var args = call.arguments() != null ? call.arguments() : NullNode.instance;
            ToolResult result = tool.execute(args);
            return new ToolResult(call.id(), result.content(), result.isError());
        } catch (Exception e) {
            return ToolResult.error(call.id(),
                    "Error ejecutando la herramienta '" + call.name() + "': " + e.getMessage());
        }
    }
}
