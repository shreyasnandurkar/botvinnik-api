package com.shreyasnandurkar.botvinnikapi.providers.gemini;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock plays Gemini. Covers the format translation plus the two landmines:
 * a blocked prompt (candidates: []) and a SAFETY finish, both of which must be
 * normal responses with finish_reason content_filter — never exceptions.
 */
class GeminiProviderTest {

    private static final String CHAT_URL = "/v1beta/models/gemini-2.0-flash:generateContent";

    static WireMockServer wiremock;
    GeminiProvider provider;

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
        provider = new GeminiProvider("test-gemini", wiremock.baseUrl(), "AIza-test-key",
                WebClient.builder(), new ObjectMapper());
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest("gemini-2.0-flash",
                List.of(Message.text("user", "Explain TCP backpressure")),
                0.7, null, 128, null, false, null, null);
    }

    @Test
    void normalizesGeminiResponseToUniversalShape() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"role":"model",
                           "parts":[{"text":"Backpressure is..."}]},
                           "finishReason":"STOP"}],
                         "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":42,
                                          "totalTokenCount":52}}
                        """)));

        ChatResponse resp = provider.chat(simpleRequest()).block();

        assertThat(resp.content()).isEqualTo("Backpressure is...");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(10);
        assertThat(resp.usage().completionTokens()).isEqualTo(42);
        assertThat(resp.provider()).isEqualTo("test-gemini");
    }

    @Test
    void mapsUniversalRequestToGeminiFormatWithSystemInstruction() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}
                        """)));

        ChatRequest request = new ChatRequest("gemini-2.0-flash",
                List.of(Message.text("system", "You are terse."),
                        Message.text("user", "hi")),
                0.7, null, 128, List.of("END"), false, null, null);

        provider.chat(request).block();

        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL))
                .withHeader("x-goog-api-key", equalTo("AIza-test-key"))
                // System messages leave the contents array entirely.
                .withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("You are terse.")))
                .withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("hi")))
                .withRequestBody(matchingJsonPath("$.generationConfig.temperature", equalTo("0.7")))
                .withRequestBody(matchingJsonPath("$.generationConfig.maxOutputTokens", equalTo("128")))
                .withRequestBody(matchingJsonPath("$.generationConfig.stopSequences[0]", equalTo("END"))));
    }

    @Test
    void blockedPromptWithEmptyCandidatesIsContentFilterNotACrash() {
        // The empty-candidates landmine: only promptFeedback, no candidates at all.
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},
                         "usageMetadata":{"promptTokenCount":12,"totalTokenCount":12}}
                        """)));

        ChatResponse resp = provider.chat(simpleRequest()).block();

        assertThat(resp.finishReason()).isEqualTo("content_filter");
        assertThat(resp.content()).isEmpty();
        assertThat(resp.usage().promptTokens()).isEqualTo(12);
    }

    @Test
    void safetyFinishReasonBecomesContentFilter() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"parts":[{"text":"partial"}]},
                           "finishReason":"SAFETY"}]}
                        """)));

        ChatResponse resp = provider.chat(simpleRequest()).block();

        assertThat(resp.finishReason()).isEqualTo("content_filter");
        assertThat(resp.content()).isEqualTo("partial");
    }

    @Test
    void functionCallPartBecomesOpenAiShapedToolCall() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"parts":[
                           {"functionCall":{"name":"get_weather","args":{"location":"Bangalore"}}}]},
                           "finishReason":"STOP"}]}
                        """)));

        ChatResponse resp = provider.chat(simpleRequest()).block();

        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().getFirst().name()).isEqualTo("get_weather");
        assertThat(resp.toolCalls().getFirst().id()).startsWith("call_");
        assertThat(resp.toolCalls().getFirst().argumentsJson())
                .isEqualTo("{\"location\":\"Bangalore\"}");
    }

    @Test
    void toolResultMessageBecomesFunctionResponsePart() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"parts":[{"text":"22C"}]},"finishReason":"STOP"}]}
                        """)));

        ChatRequest request = new ChatRequest("gemini-2.0-flash", List.of(
                Message.text("user", "weather in Bangalore?"),
                new Message("assistant", null,
                        List.of(new ToolCall("call_1", "get_weather", "{\"location\":\"Bangalore\"}")),
                        null, null),
                new Message("tool", "{\"temp\":22}", null, "call_1", "get_weather")),
                null, null, null, null, false, null, null);

        provider.chat(request).block();

        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL))
                .withRequestBody(matchingJsonPath("$.contents[1].role", equalTo("model")))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].functionCall.name",
                        equalTo("get_weather")))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].functionCall.args.location",
                        equalTo("Bangalore")))
                .withRequestBody(matchingJsonPath("$.contents[2].parts[0].functionResponse.name",
                        equalTo("get_weather")))
                .withRequestBody(matchingJsonPath("$.contents[2].parts[0].functionResponse.response.temp",
                        equalTo("22"))));
    }

    private static final String STREAM_URL =
            "/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse";

    private static ChatRequest streamingRequest() {
        return new ChatRequest("gemini-2.0-flash",
                List.of(Message.text("user", "Explain TCP backpressure")),
                0.7, null, 128, null, true, null, null);
    }

    @Test
    void reasoningOptInSendsIncludeThoughtsAndThoughtPartsBecomeReasoningContent() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"parts":[
                           {"text":"Considering the options...","thought":true},
                           {"text":"The answer is 42."}]},
                           "finishReason":"STOP"}]}
                        """)));

        ChatRequest request = new ChatRequest("gemini-2.0-flash",
                List.of(Message.text("user", "meaning of life?")),
                null, null, null, null, false, "high", null);
        ChatResponse resp = provider.chat(request).block();

        wiremock.verify(postRequestedFor(urlEqualTo(CHAT_URL))
                .withRequestBody(matchingJsonPath(
                        "$.generationConfig.thinkingConfig.includeThoughts", equalTo("true"))));
        // Thought parts must never leak into content.
        assertThat(resp.content()).isEqualTo("The answer is 42.");
        assertThat(resp.reasoningContent()).isEqualTo("Considering the options...");
    }

    @Test
    void normalizesSseStreamAndCarriesLatestUsageOnFinalChunk() {
        // Gemini reports usageMetadata on every chunk; the last snapshot must win.
        wiremock.stubFor(post(urlEqualTo(STREAM_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"candidates":[{"content":{"parts":[{"text":"TCP"}]}}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":1}}

                        data: {"candidates":[{"content":{"parts":[{"text":" is"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2}}

                        """)));

        List<ChatChunk> chunks = provider.stream(streamingRequest()).collectList().block();

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).contentDelta()).isEqualTo("TCP");
        // The final SSE event carries text AND finishReason — split into two chunks.
        assertThat(chunks.get(1).contentDelta()).isEqualTo(" is");
        assertThat(chunks.get(2).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).usage().promptTokens()).isEqualTo(9);
        assertThat(chunks.get(2).usage().completionTokens()).isEqualTo(2);
    }

    @Test
    void streamedThoughtPartsBecomeReasoningDeltas() {
        wiremock.stubFor(post(urlEqualTo(STREAM_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"candidates":[{"content":{"parts":[{"text":"Weighing options...","thought":true}]}}]}

                        data: {"candidates":[{"content":{"parts":[{"text":"42"}]},"finishReason":"STOP"}]}

                        """)));

        List<ChatChunk> chunks = provider.stream(streamingRequest()).collectList().block();

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).reasoningDelta()).isEqualTo("Weighing options...");
        assertThat(chunks.get(0).contentDelta()).isNull();
        assertThat(chunks.get(1).contentDelta()).isEqualTo("42");
        assertThat(chunks.get(2).finishReason()).isEqualTo("stop");
    }

    @Test
    void midStreamSafetyTripEndsWithContentFilterNotAnError() {
        // Gemini can stream tokens, then trip its filter (§8): the stream must end
        // with a normal content_filter chunk — never an exception, never a failover.
        wiremock.stubFor(post(urlEqualTo(STREAM_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"candidates":[{"content":{"parts":[{"text":"partial answer"}]}}]}

                        data: {"candidates":[{"finishReason":"SAFETY"}]}

                        """)));

        List<ChatChunk> chunks = provider.stream(streamingRequest()).collectList().block();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).contentDelta()).isEqualTo("partial answer");
        assertThat(chunks.get(1).finishReason()).isEqualTo("content_filter");
    }

    @Test
    void streamedFunctionCallBecomesOneToolCallDelta() {
        wiremock.stubFor(post(urlEqualTo(STREAM_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"Bangalore"}}}]},"finishReason":"STOP"}]}

                        """)));

        List<ChatChunk> chunks = provider.stream(streamingRequest()).collectList().block();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).toolCallDelta().name()).isEqualTo("get_weather");
        assertThat(chunks.get(0).toolCallDelta().id()).startsWith("call_");
        assertThat(chunks.get(0).toolCallDelta().argumentsFragment())
                .isEqualTo("{\"location\":\"Bangalore\"}");
        assertThat(chunks.get(1).finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void malformedSseChunkIsSkippedAndTheStreamSurvives() {
        wiremock.stubFor(post(urlEqualTo(STREAM_URL)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}

                        data: {{{garbage

                        data: {"candidates":[{"finishReason":"STOP"}]}

                        """)));

        List<ChatChunk> chunks = provider.stream(streamingRequest()).collectList().block();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).contentDelta()).isEqualTo("ok");
        assertThat(chunks.get(1).finishReason()).isEqualTo("stop");
    }

    @Test
    void upstreamErrorStatusBecomesUpstreamException() {
        wiremock.stubFor(post(urlEqualTo(CHAT_URL)).willReturn(aResponse()
                .withStatus(429).withBody("quota exceeded")));

        assertThatThrownBy(() -> provider.chat(simpleRequest()).block())
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("429");
    }

    @Test
    void listModelsFiltersToGenerateContentAndStripsPrefix() {
        wiremock.stubFor(get(urlPathEqualTo("/v1beta/models")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"models":[
                          {"name":"models/gemini-2.0-flash",
                           "supportedGenerationMethods":["generateContent","countTokens"]},
                          {"name":"models/text-embedding-004",
                           "supportedGenerationMethods":["embedContent"]}]}
                        """)));

        var models = provider.listModels().block();

        assertThat(models).extracting("id").containsExactly("gemini-2.0-flash");
        assertThat(models.getFirst().provider()).isEqualTo("test-gemini");
    }
}
