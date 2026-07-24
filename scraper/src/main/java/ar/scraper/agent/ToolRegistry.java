package ar.scraper.agent;

import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the exactly-3 read-only catalog tools (llm-catalog-nlp, task
 * 4.1/4.2 — design D3/D4): {@link SearchProductsTool}, {@link
 * ViewProductTool}, {@link ProposeReclassifyTool}. {@link #execute} is a
 * second line of defense on top of each tool's own boundary validation
 * (Safeguard A) — an unknown tool name or an unexpected exception inside a
 * tool NEVER escapes as an uncaught exception/500, always as an {@code
 * is_error} {@link ToolResult} the loop can feed back to the model.
 */
@Component
public class ToolRegistry {

    private final Map<String, CatalogTool> tools = new LinkedHashMap<>();

    public ToolRegistry(SearchProductsTool search, ViewProductTool view, ProposeReclassifyTool propose) {
        tools.put(search.spec().name(), search);
        tools.put(view.spec().name(), view);
        tools.put(propose.spec().name(), propose);
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(CatalogTool::spec).toList();
    }

    public ToolResult execute(ToolCall call) {
        CatalogTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(),
                    "Herramienta desconocida: '" + call.name() + "'. Herramientas disponibles: "
                            + String.join(", ", tools.keySet()));
        }
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
