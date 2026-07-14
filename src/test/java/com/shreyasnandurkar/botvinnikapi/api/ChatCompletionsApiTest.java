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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

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
    static void pointProviderAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "test-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
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
    void streamingIsRejectedUntilImplemented() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}],
                         "stream": true}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.param").isEqualTo("stream");
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