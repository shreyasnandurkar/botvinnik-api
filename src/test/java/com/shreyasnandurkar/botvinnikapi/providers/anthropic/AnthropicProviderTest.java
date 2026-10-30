package com.shreyasnandurkar.botvinnikapi.providers.anthropic;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingRequest;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WireMock plays the Anthropic Messages API. */
class AnthropicProviderTest {

    private static final String MESSAGES_URL = "/v1/messages";

    static WireMockServer wiremock;
    AnthropicProvider provider;

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
        provider = new AnthropicProvider("test-anthropic", wiremock.baseUrl(), "sk-ant-test",
                WebClient.builder(), new ObjectMapper(), null);
    }

    private static ChatRequest request(boolean stream) {
        return new ChatRequest("claude-haiku-5-5",
                List.of(Message.text("system", "You are terse."), Message.text("user", "hi")),
                0.7, null, 128, List.of("END"), stream, null, null);
    }

    @Test
    void normalizesResponseToUniversalShape() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                         "content":[{"type":"thinking","thinking":"short.","signature":"sig"},
                                    {"type":"text","text":"hel"},{"type":"text","text":"lo"}],
                         "stop_reason":"end_turn",
                         "usage":{"input_tokens":11,"output_tokens":3,
                                  "cache_creation_input_tokens":4,"cache_read_input_tokens":5}}
                        """)));

        ChatResponse resp = provider.chat(request(false)).block();

        assertThat(resp.content()).isEqualTo("hello");
        assertThat(resp.reasoningContent()).isEqualTo("short.");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(20);
        assertThat(resp.usage().completionTokens()).isEqualTo(3);
        assertThat(resp.provider()).isEqualTo("test-anthropic");
        assertThat(resp.model()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void sendsAnthropicHeadersAndTopLevelSystem() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """)));

        provider.chat(request(false)).block();

        wiremock.verify(postRequestedFor(urlEqualTo(MESSAGES_URL))
                .withHeader("x-api-key", equalTo("sk-ant-test"))
                .withHeader("anthropic-version", equalTo(AnthropicProvider.API_VERSION))
                .withoutHeader("Authorization")
                .withRequestBody(matchingJsonPath("$.system", equalTo("You are terse.")))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].text", equalTo("hi")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("128")))
                .withRequestBody(matchingJsonPath("$.stop_sequences[0]", equalTo("END")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.thinking", absent())));
    }

    @Test
    void defaultsMaxTokensAndMapsReasoningToAdaptiveThinking() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """)));

        provider.chat(new ChatRequest("claude-opus-5-5", List.of(Message.text("user", "hi")),
                null, null, null, null, false, "minimal", null)).block();

        wiremock.verify(postRequestedFor(urlEqualTo(MESSAGES_URL))
                .withRequestBody(matchingJsonPath("$.max_tokens",
                        equalTo(String.valueOf(AnthropicProvider.DEFAULT_MAX_TOKENS))))
                .withRequestBody(matchingJsonPath("$.thinking.type", equalTo("adaptive")))
                .withRequestBody(matchingJsonPath("$.thinking.display", equalTo("summarized")))
                .withRequestBody(matchingJsonPath("$.output_config.effort", equalTo("low"))));
    }

    @Test
    void roundTripsToolCallsAsContentBlocks() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"content":[{"type":"text","text":"Checking."},
                                    {"type":"tool_use","id":"toolu_abc","name":"get_weather","input":{"city":"Pune"}}],
                         "stop_reason":"tool_use"}
                        """)));

        ChatRequest withHistory = new ChatRequest("claude-haiku-5-5", List.of(
                Message.text("user", "weather in Pune and Delhi?"),
                new Message("assistant", null, List.of(
                        new ToolCall("call_1", "get_weather", "{\"city\":\"Pune\"}"),
                        new ToolCall("call_2", "get_weather", "{\"city\":\"Delhi\"}")), null, null),
                new Message("tool", "sunny", null, "call_1", null),
                new Message("tool", "rainy", null, "call_2", null)),
                null, null, null, null, false, null, null);

        ChatResponse resp = provider.chat(withHistory).block();

        assertThat(resp.content()).isEqualTo("Checking.");
        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.id()).isEqualTo("toolu_abc");
            assertThat(tc.name()).isEqualTo("get_weather");
            assertThat(tc.argumentsJson()).isEqualTo("{\"city\":\"Pune\"}");
        });
        // Both tool results share one user turn, as Anthropic requires.
        wiremock.verify(postRequestedFor(urlEqualTo(MESSAGES_URL))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("3")))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].type", equalTo("tool_use")))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].id", equalTo("call_1")))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].input",
                        equalToJson("{\"city\":\"Pune\"}")))
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].tool_use_id", equalTo("call_1")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[1].tool_use_id", equalTo("call_2")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[1].content", equalTo("rainy"))));
    }

    @Test
    void refusalIsContentFilterNotAnError() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}}
                        """)));

        ChatResponse resp = provider.chat(request(false)).block();

        assertThat(resp.finishReason()).isEqualTo("content_filter");
        assertThat(resp.content()).isEmpty();
    }

    @Test
    void streamMapsNamedEventsAndPutsUsageOnFinish() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg_1","model":"claude-haiku-5-5","content":[],"usage":{"input_tokens":9,"cache_read_input_tokens":1,"output_tokens":1}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}

                        event: content_block_stop
                        data: {"type":"content_block_stop","index":0}

                        event: ping
                        data: {"type":"ping"}

                        event: content_block_start
                        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"TCP"}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" is"}}

                        event: content_block_stop
                        data: {"type":"content_block_stop","index":1}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":2}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """)));

        List<ChatChunk> chunks = provider.stream(request(true)).collectList().block();

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).reasoningDelta()).isEqualTo("hmm");
        assertThat(chunks.get(1).contentDelta()).isEqualTo("TCP");
        assertThat(chunks.get(2).contentDelta()).isEqualTo(" is");
        assertThat(chunks.get(3).finishReason()).isEqualTo("length");
        assertThat(chunks.get(3).usage().promptTokens()).isEqualTo(10);
        assertThat(chunks.get(3).usage().completionTokens()).isEqualTo(2);
        wiremock.verify(postRequestedFor(urlEqualTo(MESSAGES_URL))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    void streamRenumbersToolBlocksForOpenAiIndexes() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"usage":{"input_tokens":5,"output_tokens":1}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me check."}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_x","name":"get_weather","input":{}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"Pune\\"}"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """)));

        List<ChatChunk> chunks = provider.stream(request(true)).collectList().block();

        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0).contentDelta()).isEqualTo("Let me check.");
        assertThat(chunks.get(1).toolCallDelta().index()).isZero();
        assertThat(chunks.get(1).toolCallDelta().id()).isEqualTo("toolu_x");
        assertThat(chunks.get(1).toolCallDelta().name()).isEqualTo("get_weather");
        assertThat(chunks.get(2).toolCallDelta().index()).isZero();
        assertThat(chunks.get(2).toolCallDelta().id()).isNull();
        assertThat(chunks.get(2).toolCallDelta().argumentsFragment()
                + chunks.get(3).toolCallDelta().argumentsFragment()).isEqualTo("{\"city\":\"Pune\"}");
        assertThat(chunks.get(4).finishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(4).usage().completionTokens()).isEqualTo(7);
    }

    @Test
    void inBandStreamErrorBecomesUpstreamException() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

                        event: error
                        data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                        """)));

        assertThatThrownBy(() -> provider.stream(request(true)).collectList().block())
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.upstreamStatus()).isEqualTo(529));
    }

    @Test
    void upstreamErrorStatusBecomesUpstreamException() {
        wiremock.stubFor(post(urlEqualTo(MESSAGES_URL)).willReturn(aResponse()
                .withStatus(429).withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}")));

        assertThatThrownBy(() -> provider.chat(request(false)).block())
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("429");
    }

    @Test
    void listModelsReadsDataIds() {
        wiremock.stubFor(get(urlEqualTo("/v1/models?limit=1000")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"},
                                 {"type":"model","id":"claude-haiku-5-5"}],"has_more":false}
                        """)));

        List<ModelInfo> models = provider.listModels().block();

        assertThat(models).extracting(ModelInfo::id).containsExactly("claude-opus-5-5", "claude-haiku-5-5");
        assertThat(models).allSatisfy(m -> assertThat(m.provider()).isEqualTo("test-anthropic"));
        wiremock.verify(getRequestedFor(urlEqualTo("/v1/models?limit=1000"))
                .withHeader("x-api-key", equalTo("sk-ant-test")));
    }

    @Test
    void embeddingsAreNotSupported() {
        assertThatThrownBy(() -> provider.embed(new EmbeddingRequest("claude", List.of("a"), null)).block())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not support embeddings");
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }
}
