package com.shreyasnandurkar.botvinnikapi.providers.ollama;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock plays Ollama. Proves the adapter translates both directions of
 * the wire format without a live model.
 */
class OllamaProviderTest {

    static WireMockServer wiremock;
    OllamaProvider provider;

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
        provider = new OllamaProvider("test-ollama", wiremock.baseUrl(),
                WebClient.builder(), new ObjectMapper());
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest("qwen3:1.7b",
                List.of(Message.text("user", "Explain TCP backpressure")),
                0.7, null, 128, null, false, null);
    }

    @Test
    void normalizesOllamaResponseToUniversalShape() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"qwen3:1.7b",
                         "message":{"role":"assistant","content":"Backpressure is..."},
                         "done":true,"done_reason":"stop",
                         "prompt_eval_count":10,"eval_count":42}
                        """)));

        ChatResponse resp = provider.chat(simpleRequest()).block();

        assertThat(resp.content()).isEqualTo("Backpressure is...");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(10);
        assertThat(resp.usage().completionTokens()).isEqualTo(42);
        assertThat(resp.usage().totalTokens()).isEqualTo(52);
        assertThat(resp.provider()).isEqualTo("test-ollama");
        assertThat(resp.model()).isEqualTo("qwen3:1.7b");
    }

    @Test
    void mapsUniversalRequestToOllamaNativeFormat() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"qwen3:1.7b","message":{"role":"assistant","content":"ok"},
                         "done":true,"done_reason":"stop"}
                        """)));

        provider.chat(simpleRequest()).block();

        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("qwen3:1.7b")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.options.temperature", equalTo("0.7")))
                .withRequestBody(matchingJsonPath("$.options.num_predict", equalTo("128"))));
    }

    @Test
    void normalizesStructuredToolCallArgumentsToJsonString() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"qwen3:4b",
                         "message":{"role":"assistant","content":"",
                           "tool_calls":[{"function":{"name":"get_weather",
                                          "arguments":{"location":"Bangalore"}}}]},
                         "done":true,"done_reason":"stop"}
                        """)));

        ChatResponse resp = provider.chat(simpleRequest()).block();

        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().getFirst().name()).isEqualTo("get_weather");
        assertThat(resp.toolCalls().getFirst().id()).startsWith("call_");
        // OpenAI convention: arguments must be a JSON *string* that parses on its own.
        assertThat(resp.toolCalls().getFirst().argumentsJson())
                .isEqualTo("{\"location\":\"Bangalore\"}");
    }

    @Test
    void upstreamErrorStatusBecomesUpstreamException() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(500).withBody("model runner crashed")));

        assertThatThrownBy(() -> provider.chat(simpleRequest()).block())
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("model runner crashed");
    }

    @Test
    void connectionFailureBecomesProviderUnreachable() {
        OllamaProvider dead = new OllamaProvider("dead-ollama",
                "http://127.0.0.1:1", WebClient.builder(), new ObjectMapper());

        assertThatThrownBy(() -> dead.chat(simpleRequest()).block())
                .isInstanceOf(ProviderUnreachableException.class);
    }

    @Test
    void listModelsReturnsDiscoveredTags() {
        wiremock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"models":[{"name":"qwen3:1.7b"},{"name":"deepseek-r1:1.5b"}]}
                                """)));

        var models = provider.listModels().block();

        assertThat(models).extracting("id")
                .containsExactly("qwen3:1.7b", "deepseek-r1:1.5b");
        assertThat(models).allMatch(m -> m.provider().equals("test-ollama"));
    }
}