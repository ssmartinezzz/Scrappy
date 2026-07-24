package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatProvider} adapter for the OpenAI-compatible {@code
 * /chat/completions} + {@code /models} wire protocol (llm-catalog-nlp,
 * design D2/D8) — works against both a local Ollama instance and any remote
 * OpenAI-compatible endpoint. Matches the project's existing HTTP
 * convention ({@code java.net.http.HttpClient} + Jackson, see
 * {@code InflacionService}/{@code PythonRunner}) — no new HTTP dependency.
 *
 * <p>Sends {@code "think": false} on every chat request (qwen3 "thinking"
 * models otherwise burn tokens on a hidden reasoning phase before replying)
 * and uses a ~120s request timeout (a 14b model partially spilled to CPU can
 * be slow). {@link #listModels()} discovers the currently available model
 * ids from {@code GET {baseUrl}/models} — the D8 runtime model selector.</p>
 *
 * <p>The {@link HttpClient} is constructor-injected so unit tests can supply
 * a mock and assert on the exact {@link HttpRequest} built (timeout, body
 * shape) without any real network call; the wire↔domain mapping methods
 * ({@link #buildChatRequestBody}, {@link #parseChatResponse},
 * {@link #parseModelsResponse}) are package-private statics, unit-testable
 * directly against JSON fixtures.</p>
 */
@Component
public class OpenAiCompatProvider implements ChatProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CHAT_TIMEOUT     = Duration.ofSeconds(120);
    private static final Duration MODELS_TIMEOUT   = Duration.ofSeconds(15);

    private final HttpClient client;
    private final AgentConfig config;

    @Autowired
    public OpenAiCompatProvider(AgentConfig config) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), config);
    }

    /** Test-only constructor — injects a mock/fake {@link HttpClient}. */
    OpenAiCompatProvider(HttpClient client, AgentConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public ChatResponse next(List<ChatMessage> history, List<ToolSpec> tools, String model) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : config.model();
        ObjectNode bodyNode = buildChatRequestBody(history, tools, effectiveModel);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(CHAT_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyNode.toString()));
        if (config.hasApiKey()) req.header("Authorization", "Bearer " + config.apiKey());

        try {
            HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                LOG.warn("[Agent] LLM provider HTTP {} on chat/completions", resp.statusCode());
                return new ChatResponse(
                        "El proveedor LLM devolvió un error (HTTP " + resp.statusCode() + ").", List.of());
            }
            return parseChatResponse(resp.body());
        } catch (Exception e) {
            LOG.warn("[Agent] Error contactando al proveedor LLM: {}", e.getMessage());
            return new ChatResponse("No se pudo contactar al proveedor LLM: " + e.getMessage(), List.of());
        }
    }

    @Override
    public List<String> listModels() {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/models"))
                .timeout(MODELS_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (config.hasApiKey()) req.header("Authorization", "Bearer " + config.apiKey());

        try {
            HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                LOG.warn("[Agent] LLM provider HTTP {} on /models", resp.statusCode());
                return List.of();
            }
            return parseModelsResponse(resp.body());
        } catch (Exception e) {
            LOG.warn("[Agent] Error listando modelos del proveedor LLM: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Wire ↔ domain mapping — package-private, unit-tested directly ─────

    static ObjectNode buildChatRequestBody(List<ChatMessage> history, List<ToolSpec> tools, String model) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("think", false);
        root.put("stream", false);

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : history) {
            ObjectNode msg = messages.addObject();
            msg.put("role", wireRole(m.role()));
            if (m.role() == Role.TOOL) {
                msg.put("tool_call_id", m.toolCallId());
                msg.put("content", m.text() == null ? "" : m.text());
                continue;
            }
            msg.put("content", m.text() == null ? "" : m.text());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode toolCalls = msg.putArray("tool_calls");
                for (ToolCall call : m.toolCalls()) {
                    ObjectNode callNode = toolCalls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode fn = callNode.putObject("function");
                    fn.put("name", call.name());
                    fn.put("arguments", call.arguments() == null ? "{}" : call.arguments().toString());
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = root.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode toolNode = toolsArr.addObject();
                toolNode.put("type", "function");
                ObjectNode fn = toolNode.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", spec.paramsSchema());
            }
        }
        return root;
    }

    static ChatResponse parseChatResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return new ChatResponse("", List.of());
            }
            JsonNode message = choices.get(0).path("message");
            JsonNode contentNode = message.path("content");
            String content = (contentNode.isMissingNode() || contentNode.isNull())
                    ? "" : contentNode.asText();

            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.path("id").asText();
                    JsonNode fn = tc.path("function");
                    String name = fn.path("name").asText();
                    JsonNode args;
                    try {
                        args = MAPPER.readTree(fn.path("arguments").asText("{}"));
                    } catch (Exception e) {
                        args = MAPPER.createObjectNode();
                    }
                    calls.add(new ToolCall(id, name, args));
                }
            }
            return new ChatResponse(content, calls);
        } catch (Exception e) {
            LOG.warn("[Agent] Respuesta inválida del proveedor LLM: {}", e.getMessage());
            return new ChatResponse("Respuesta inválida del proveedor LLM.", List.of());
        }
    }

    static List<String> parseModelsResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root.path("data");
            List<String> ids = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode m : data) {
                    String id = m.path("id").asText(null);
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }
            return ids;
        } catch (Exception e) {
            LOG.warn("[Agent] Respuesta inválida de /models: {}", e.getMessage());
            return List.of();
        }
    }

    private static String wireRole(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }
}
