package com.shreyasnandurkar.botvinnikapi.providers.openai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WireMock plays an OpenAI-compatible server (vLLM, Groq, OpenAI itself). */
class OpenAiCompatProviderTest {

    private static final String CHAT_URL = "/v1/chat/completions";

    static WireMockServer wiremock;
    OpenAiCompatProvider provider;

    @BeforeAll
    static void startServer() {
        wiremock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopServer() {
        wiremock.stop();
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        provider = new OpenAiCompatProvider("test-openai", wiremock.baseUrl() + "/v1", "sk-test",
                WebClient.builder(), new ObjectMapper());
    }

    private static ChatRequest request(boolean stream) {
        return new ChatRequest("llama-3.1-8b",
                List.of(Message.text("system", "You are terse."), Message.text("user", "hi")),
                0.7, null, 128, List.of("END"), stream, null, null);
    }

    @Test
    void normalizesResponseToUniversalShape() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"llama-3.1-8b-instruct",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"hello",
                                     "reasoning_content":"thinking..."},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}
                        """)));

        ChatResponse resp = provider.chat(request(false)).block();

        assertThat(resp.content()).isEqualTo("hello");
        assertThat(resp.reasoningContent()).isEqualTo("thinking...");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(11);
        assertThat(resp.usage().completionTokens()).isEqualTo(3);
        assertThat(resp.provider()).isEqualTo("test-openai");
        assertThat(resp.model()).isEqualTo("llama-3.1-8b-instruct");
    }

    @Test
    void readsGroqStyleReasoningField() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"choices":[{"message":{"content":"pong","reasoning":"User wants pong."},
                           "finish_reason":"stop"}]}
                        """)));

        ChatResponse resp = provider.chat(request(false)).block();

        assertThat(resp.reasoningContent()).isEqualTo("User wants pong.");
    }

    @Test
    void sendsBearerKeyAndOpenAiShapedBody() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                        """)));

        provider.chat(request(false)).block();

        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("hi")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("128")))
                .withRequestBody(matchingJsonPath("$.stop[0]", equalTo("END")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.stream_options", absent()))
                .withRequestBody(matchingJsonPath("$.reasoning_effort", absent())));
    }

    @Test
    void omitsAuthorizationWhenNoKeyForLocalServers() {
        provider = new OpenAiCompatProvider("local-vllm", wiremock.baseUrl() + "/v1", null,
                WebClient.builder(), new ObjectMapper());
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                        """)));

        provider.chat(request(false)).block();

        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL)).withoutHeader("Authorization"));
    }

    @Test
    void roundTripsToolCallsAndToolResults() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_abc","type":"function",
                           "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Pune\\"}"}}]},
                           "finish_reason":"tool_calls"}]}
                        """)));

        ChatRequest withHistory = new ChatRequest("llama-3.1-8b", List.of(
                Message.text("user", "weather?"),
                new Message("assistant", null, List.of(new ToolCall("call_1", "get_weather", "{}")), null, null),
                new Message("tool", "sunny", null, "call_1", null)),
                null, null, null, null, false, null, null);

        ChatResponse resp = provider.chat(withHistory).block();

        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.id()).isEqualTo("call_abc");
            assertThat(tc.name()).isEqualTo("get_weather");
            assertThat(tc.argumentsJson()).isEqualTo("{\"city\":\"Pune\"}");
        });
        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL))
                .withRequestBody(matchingJsonPath("$.messages[1].tool_calls[0].id", equalTo("call_1")))
                .withRequestBody(matchingJsonPath("$.messages[1].tool_calls[0].function.arguments", equalTo("{}")))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_call_id", equalTo("call_1"))));
    }

    @Test
    void streamHoldsFinishUntilTrailingUsageChunk() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"TCP"}}]}

                        data: {"choices":[{"index":0,"delta":{"content":" is"},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2}}

                        data: [DONE]

                        """)));

        List<ChatChunk> chunks = provider.stream(request(true)).collectList().block();

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).contentDelta()).isEqualTo("TCP");
        assertThat(chunks.get(1).contentDelta()).isEqualTo(" is");
        assertThat(chunks.get(2).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).usage().promptTokens()).isEqualTo(9);
        assertThat(chunks.get(2).usage().completionTokens()).isEqualTo(2);
        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL))
                .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));
    }

    @Test
    void streamWithoutUsageStillEmitsFinish() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"length"}]}

                        data: [DONE]

                        """)));

        List<ChatChunk> chunks = provider.stream(request(true)).collectList().block();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).finishReason()).isEqualTo("length");
        assertThat(chunks.get(1).usage()).isNull();
    }

    @Test
    void streamPassesFragmentedToolCallsThrough() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":"}}]}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Pune\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":7}}

                        data: [DONE]

                        """)));

        List<ChatChunk> chunks = provider.stream(request(true)).collectList().block();

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).toolCallDelta().id()).isEqualTo("call_x");
        assertThat(chunks.get(0).toolCallDelta().name()).isEqualTo("get_weather");
        assertThat(chunks.get(1).toolCallDelta().id()).isNull();
        assertThat(chunks.get(1).toolCallDelta().argumentsFragment()
                + chunks.get(2).toolCallDelta().argumentsFragment()).isEqualTo("{\"city\":\"Pune\"}");
        assertThat(chunks.get(3).finishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(3).usage().completionTokens()).isEqualTo(7);
    }

    @Test
    void upstreamErrorStatusBecomesUpstreamException() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withStatus(429).withBody("rate limited")));

        assertThatThrownBy(() -> provider.chat(request(false)).block())
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("429");
    }

    @Test
    void listModelsReadsDataIds() {
        wiremock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"object":"list","data":[{"id":"llama-3.1-8b","object":"model"},{"id":"qwen3-4b"}]}
                        """)));

        List<ModelInfo> models = provider.listModels().block();

        assertThat(models).extracting(ModelInfo::id).containsExactly("llama-3.1-8b", "qwen3-4b");
        assertThat(models).allSatisfy(m -> assertThat(m.provider()).isEqualTo("test-openai"));
    }
}
