package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RED→GREEN coverage for {@link OpenAiCompatProvider} (llm-catalog-nlp,
 * design D2/D8): wire↔domain mapping for the Ollama/OpenAI-compatible
 * {@code /chat/completions} + {@code /models} endpoints, the {@code
 * think:false} + ~120s timeout requirement, and the per-request model
 * override (D8). {@link HttpClient} is injected via the package-private
 * test constructor and mocked — no real network call happens in this suite.
 */
@Epic("LLM Catalog Agent")
@Feature("OpenAiCompatProvider")
@Story("Wire↔domain mapping, think:false, model override, listModels")
@DisplayName("OpenAiCompatProvider — Ollama/OpenAI-compatible wire adapter")
class OpenAiCompatProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentConfig configWithDefault(String defaultModel) {
        AgentConfig config = mock(AgentConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:11434/v1");
        when(config.model()).thenReturn(defaultModel);
        when(config.hasApiKey()).thenReturn(false);
        return config;
    }

    // ── 2.1: wire→domain mapping (tool_calls/finish_reason fixtures) ──────

    @Test
    @DisplayName("parseChatResponse maps tool_calls fixture into domain ToolCall list")
    void parseChatResponseMapsToolCallsFixture() {
        String fixture = """
            {
              "choices": [
                {
                  "finish_reason": "tool_calls",
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_abc",
                        "type": "function",
                        "function": { "name": "search_products", "arguments": "{\\"query\\":\\"remera\\",\\"limit\\":10}" }
                      }
                    ]
                  }
                }
              ]
            }
            """;

        ChatResponse resp = OpenAiCompatProvider.parseChatResponse(fixture);

        assertThat(resp.done()).isFalse();
        assertThat(resp.toolCalls()).hasSize(1);
        ToolCall call = resp.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.name()).isEqualTo("search_products");
        assertThat(call.arguments().get("query").asText()).isEqualTo("remera");
        assertThat(call.arguments().get("limit").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("parseChatResponse maps a final-answer (finish_reason=stop) fixture into plain text, no tool calls")
    void parseChatResponseMapsFinalAnswerFixture() {
        String fixture = """
            {
              "choices": [
                { "finish_reason": "stop", "message": { "role": "assistant", "content": "Listo, encontré 3 productos." } }
              ]
            }
            """;

        ChatResponse resp = OpenAiCompatProvider.parseChatResponse(fixture);

        assertThat(resp.done()).isTrue();
        assertThat(resp.assistantText()).isEqualTo("Listo, encontré 3 productos.");
        assertThat(resp.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("parseModelsResponse parses a /v1/models fixture into an id list (D8)")
    void parseModelsResponseParsesFixture() {
        String fixture = """
            {
              "object": "list",
              "data": [
                { "id": "qwen3:14b", "object": "model" },
                { "id": "llama3.1:8b", "object": "model" }
              ]
            }
            """;

        List<String> ids = OpenAiCompatProvider.parseModelsResponse(fixture);

        assertThat(ids).containsExactly("qwen3:14b", "llama3.1:8b");
    }

    // ── 2.3/2.4: think:false + ~120s timeout ───────────────────────────────

    @Test
    @DisplayName("buildChatRequestBody sets think:false and the effective model")
    void buildChatRequestBodySetsThinkFalse() {
        ObjectNode body = OpenAiCompatProvider.buildChatRequestBody(
                List.of(ChatMessage.user("hola")), List.of(), "qwen3:14b");

        assertThat(body.get("think").asBoolean()).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("qwen3:14b");
        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("hola");
    }

    @Test
    @DisplayName("next() sends an HttpRequest with a ~120s timeout and think:false in the body")
    void nextSendsRequestWithTimeoutAndThinkFalse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AgentConfig config = configWithDefault("qwen3:14b");
        OpenAiCompatProvider provider = new OpenAiCompatProvider(client, config);

        @SuppressWarnings("unchecked")
        HttpResponse<String> httpResp = mock(HttpResponse.class);
        when(httpResp.statusCode()).thenReturn(200);
        when(httpResp.body()).thenReturn("""
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
            """);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(captor.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResp);

        ChatResponse resp = provider.next(List.of(ChatMessage.user("hola")), List.of(), null);

        assertThat(resp.assistantText()).isEqualTo("ok");
        HttpRequest sent = captor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("http://localhost:11434/v1/chat/completions");
        assertThat(sent.timeout()).isPresent();
        assertThat(sent.timeout().get()).isGreaterThanOrEqualTo(Duration.ofSeconds(90));
        JsonNode sentBody = readSentBody(sent);
        assertThat(sentBody.get("think").asBoolean()).isFalse();
    }

    // ── 2.7: model param overrides env default, else env default is used ──

    @Test
    @DisplayName("next() uses the passed model when non-null")
    void nextUsesPassedModelWhenPresent() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AgentConfig config = configWithDefault("qwen3:14b");
        OpenAiCompatProvider provider = new OpenAiCompatProvider(client, config);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        HttpResponse<String> httpResp = stubHttpResponse();
        when(client.send(captor.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResp);

        provider.next(List.of(ChatMessage.user("hola")), List.of(), "llama3.1:8b");

        JsonNode sentBody = readSentBody(captor.getValue());
        assertThat(sentBody.get("model").asText()).isEqualTo("llama3.1:8b");
    }

    @Test
    @DisplayName("next() falls back to the env default model when model param is null")
    void nextFallsBackToEnvDefaultWhenModelNull() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AgentConfig config = configWithDefault("qwen3:14b");
        OpenAiCompatProvider provider = new OpenAiCompatProvider(client, config);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        HttpResponse<String> httpResp = stubHttpResponse();
        when(client.send(captor.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResp);

        provider.next(List.of(ChatMessage.user("hola")), List.of(), null);

        JsonNode sentBody = readSentBody(captor.getValue());
        assertThat(sentBody.get("model").asText()).isEqualTo("qwen3:14b");
    }

    // ── agent-chat-response-quality: provider failure becomes unrepresentable
    // as an answer — thrown, never a manufactured ChatResponse ────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("next() throws ProviderUnavailableException(HTTP_ERROR) when the provider responds HTTP >= 400")
    void nextThrowsHttpErrorOnStatusCodeAtLeast400() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AgentConfig config = configWithDefault("qwen3:14b");
        OpenAiCompatProvider provider = new OpenAiCompatProvider(client, config);

        HttpResponse<String> httpResp = mock(HttpResponse.class);
        when(httpResp.statusCode()).thenReturn(500);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResp);

        assertThatThrownBy(() -> provider.next(List.of(ChatMessage.user("hola")), List.of(), null))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProviderUnavailableException.Reason.HTTP_ERROR));
    }

    @Test
    @DisplayName("next() throws ProviderUnavailableException(UNREACHABLE) when the connection fails/times out")
    void nextThrowsUnreachableOnConnectionFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AgentConfig config = configWithDefault("qwen3:14b");
        OpenAiCompatProvider provider = new OpenAiCompatProvider(client, config);

        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        assertThatThrownBy(() -> provider.next(List.of(ChatMessage.user("hola")), List.of(), null))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProviderUnavailableException.Reason.UNREACHABLE));
    }

    @Test
    @DisplayName("parseChatResponse throws ProviderUnavailableException(INVALID_RESPONSE) when the body has no usable choices")
    void parseChatResponseThrowsInvalidResponseOnMissingChoices() {
        String fixture = "{\"choices\": []}";

        assertThatThrownBy(() -> OpenAiCompatProvider.parseChatResponse(fixture))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProviderUnavailableException.Reason.INVALID_RESPONSE));
    }

    @Test
    @DisplayName("parseChatResponse throws ProviderUnavailableException(INVALID_RESPONSE) on an unparseable body")
    void parseChatResponseThrowsInvalidResponseOnUnparseableBody() {
        String fixture = "{not-json-at-all";

        assertThatThrownBy(() -> OpenAiCompatProvider.parseChatResponse(fixture))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProviderUnavailableException.Reason.INVALID_RESPONSE));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private HttpResponse<String> stubHttpResponse() {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("""
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
            """);
        return resp;
    }

    private JsonNode readSentBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        // java.net.http doesn't expose body content directly off BodyPublisher;
        // use a simple Flow.Subscriber collector instead.
        var bodyBytes = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer item) {
                byte[] arr = new byte[item.remaining()];
                item.get(arr);
                bodyBytes.writeBytes(arr);
            }
            @Override public void onError(Throwable throwable) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        return MAPPER.readTree(bodyBytes.toByteArray());
    }
}
