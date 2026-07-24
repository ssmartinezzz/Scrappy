package ar.scraper.agent;

import java.util.List;

/**
 * Provider-agnostic chat seam (llm-catalog-nlp, design D1). Every concrete
 * adapter (currently only {@link OpenAiCompatProvider}; a future
 * {@code AnthropicProvider} is the documented escape hatch, not built now)
 * translates its own wire protocol to/from these domain types internally —
 * the tool-use loop, the endpoint, and the tools depend ONLY on this
 * interface and never see a provider-specific shape.
 */
public interface ChatProvider {

    /**
     * Runs a single turn: send the conversation history and tool
     * definitions to the provider, get back either a final answer or a
     * request to execute tool calls.
     *
     * @param model the model id to use for this call, or {@code null} to
     *              use the provider's env-configured default (D8 runtime
     *              model selector) — never mutates any server-side state.
     */
    ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model);

    /** Discovers the model ids currently available from this provider (D8). */
    List<String> listModels();
}
