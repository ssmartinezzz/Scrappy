package ar.scraper.agent;

import java.util.List;

/**
 * Every concrete adapter (currently only {@link OpenAiCompatProvider}; a future
 * {@code AnthropicProvider} is the documented escape hatch, not built now) translates its own wire
 * protocol to/from these domain types internally — the tool-use loop, the endpoint, and the tools
 * depend ONLY on this interface and never see a provider-specific shape.
 */
public interface ChatProvider {

    ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model);

    List<String> listModels();
}
