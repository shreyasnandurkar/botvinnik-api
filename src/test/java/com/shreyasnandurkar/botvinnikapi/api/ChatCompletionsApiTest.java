package com.shreyasnandurkar.botvinnikapi.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test: an OpenAI-shaped request goes in over HTTP, WireMock
 * plays Ollama upstream, and an OpenAI-shaped response must come out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ChatCompletionsApiTest {

    static WireMockServer wiremock;

    @Autowired
    WebTestClient client;

    @BeforeAll
    static void startServer() {
        wiremock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopServer() {
        wiremock.stop();
    }

    @DynamicPropertySource
    static void pointProvidersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "test-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
        registry.add("botvinnik.providers[1].name", () -> "test-gemini");
        registry.add("botvinnik.providers[1].type", () -> "gemini");
        registry.add("botvinnik.providers[1].base-url", () -> wiremock.baseUrl());
        registry.add("botvinnik.providers[1].api-key", () -> "AIza-test");
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
    }

    @Test
    void openAiRequestInOpenAiResponseOut() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"qwen3:1.7b",
                         "message":{"role":"assistant","content":"Hello from Ollama"},
                         "done":true,"done_reason":"stop",
                         "prompt_eval_count":7,"eval_count":11}
                        """)));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "temperature":0.7,"stream":false}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.id").value(id -> {
                    if (!id.toString().startsWith("chatcmpl-")) {
                        throw new AssertionError("id must start with chatcmpl-, was " + id);
                    }
                })
                .jsonPath("$.choices[0].message.role").isEqualTo("assistant")
                .jsonPath("$.choices[0].message.content").isEqualTo("Hello from Ollama")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
                .jsonPath("$.usage.prompt_tokens").isEqualTo(7)
                .jsonPath("$.usage.completion_tokens").isEqualTo(11)
                .jsonPath("$.usage.total_tokens").isEqualTo(18);
    }

    @Test
    void providerPrefixRoutesToGeminiWithoutTouchingTheRouter() {
        wiremock.stubFor(post(urlEqualTo("/v1beta/models/gemini-2.0-flash:generateContent"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"candidates":[{"content":{"parts":[{"text":"Hello from Gemini"}]},
                                   "finishReason":"STOP"}],
                                 "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3}}
                                """)));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"test-gemini/gemini-2.0-flash",
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Hello from Gemini")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
                .jsonPath("$.usage.total_tokens").isEqualTo(8);
    }

    @Test
    void unknownParameterIsRejectedLoudly() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "frobnicate": true}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.message").value(msg ->
                        org.assertj.core.api.Assertions.assertThat(msg.toString()).contains("frobnicate"));
    }

    @Test
    void streamingEmitsOpenAiSseTerminatedByDone() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody("""
                        {"message":{"role":"assistant","content":"Hel"},"done":false}
                        {"message":{"role":"assistant","content":"lo"},"done":false}
                        {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":2}
                        """)));

        List<String> frames = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "stream": true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        // data: {chunk}, data: {chunk}, data: {finish}, data: [DONE] — the exact
        // frames the OpenAI Python SDK parses with stream=True.
        assertThat(frames).hasSize(4);
        assertThat(frames.getFirst())
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("\"role\":\"assistant\"")
                .contains("\"content\":\"Hel\"");
        assertThat(frames.get(1)).contains("\"content\":\"lo\"").doesNotContain("\"role\"");
        assertThat(frames.get(2)).contains("\"finish_reason\":\"stop\"");
        // The literal sentinel, not the JSON string "[DONE]" (§6 gotcha).
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void includeUsageAppendsUsageOnlyFrameBeforeDone() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody("""
                        {"message":{"role":"assistant","content":"Hi"},"done":false}
                        {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":2}
                        """)));

        List<String> frames = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "stream": true,
                         "stream_options": {"include_usage": true}}
                        """)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        String usageFrame = frames.get(frames.size() - 2);
        assertThat(usageFrame)
                .contains("\"choices\":[]")
                .contains("\"prompt_tokens\":5")
                .contains("\"completion_tokens\":2")
                .contains("\"total_tokens\":7");
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void reasoningContentSurfacesInBothModes() {
        // Non-streaming: message.reasoning_content
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":{"role":"assistant","content":"42","thinking":"hmm..."},
                         "done":true,"done_reason":"stop","prompt_eval_count":3,"eval_count":9}
                        """)));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"meaning of life?"}],
                         "reasoning_effort":"high"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("42")
                .jsonPath("$.choices[0].message.reasoning_content").isEqualTo("hmm...");

        // Streaming: delta.reasoning_content
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody("""
                        {"message":{"role":"assistant","content":"","thinking":"hmm..."},"done":false}
                        {"message":{"role":"assistant","content":"42"},"done":true,"done_reason":"stop","prompt_eval_count":3,"eval_count":9}
                        """)));

        List<String> frames = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"meaning of life?"}],
                         "reasoning_effort":"high","stream":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(frames.getFirst()).contains("\"reasoning_content\":\"hmm...\"");
        assertThat(frames.get(1)).contains("\"content\":\"42\"").doesNotContain("reasoning_content");
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void invalidReasoningEffortIsRejected() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "reasoning_effort":"maximum"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.param").isEqualTo("reasoning_effort");
    }

    @Test
    void streamOptionsWithoutStreamIsRejected() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "stream_options": {"include_usage": true}}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.param").isEqualTo("stream_options");
    }

    @Test
    void unknownProviderPrefixIs404ModelNotFound() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"no-such-provider/some-model",
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("model_not_found");
    }

    @Test
    void upstreamFailureIs502NeverAStackTrace() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(500).withBody("boom")));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("api_error")
                .jsonPath("$.error.code").isEqualTo("upstream_error");
    }

    @Test
    void malformedJsonIs400() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");
    }
}