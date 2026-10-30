package com.shreyasnandurkar.botvinnikapi.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.TestcontainersConfiguration;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test for the Anthropic client protocol: an Anthropic Messages request
 * goes in over HTTP, WireMock plays Ollama upstream, and an Anthropic-shaped response comes out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "botvinnik.security.auth-enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicMessagesApiTest {

    // Started at class load: PER_CLASS builds the Spring context before any @BeforeAll runs.
    static final WireMockServer wiremock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wiremock.start();
    }

    @Autowired
    WebTestClient client;

    @Autowired
    ProviderRepository providerRepository;

    @Autowired
    ConfigSnapshotService snapshots;

    /** Postgres is shared across test contexts; a leftover provider on a dead WireMock breaks routing elsewhere. */
    @AfterAll
    void cleanUp() {
        providerRepository.findByName("msg-ollama").flatMap(providerRepository::delete).block();
        snapshots.rebuild().block();
        wiremock.stop();
    }

    @DynamicPropertySource
    static void pointProvidersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "msg-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
    }

    private WebTestClient.ResponseSpec messages(String body) {
        return client.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    void anthropicRequestInAnthropicResponseOut() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"qwen3:1.7b","message":{"role":"assistant","content":"Hello from Ollama"},
                         "done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":11}
                        """)));

        messages("""
                {"model":"msg-ollama/qwen3:1.7b","max_tokens":256,
                 "system":[{"type":"text","text":"You are terse.","cache_control":{"type":"ephemeral"}}],
                 "messages":[{"role":"user","content":"hi"}],
                 "metadata":{"user_id":"u1"}}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("message")
                .jsonPath("$.role").isEqualTo("assistant")
                .jsonPath("$.id").value(id -> assertThat(id.toString()).startsWith("msg_"))
                .jsonPath("$.content[0].type").isEqualTo("text")
                .jsonPath("$.content[0].text").isEqualTo("Hello from Ollama")
                .jsonPath("$.stop_reason").isEqualTo("end_turn")
                .jsonPath("$.usage.input_tokens").isEqualTo(7)
                .jsonPath("$.usage.output_tokens").isEqualTo(11);

        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("You are terse.")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("hi")))
                .withRequestBody(matchingJsonPath("$.options.num_predict", equalTo("256"))));
    }

    @Test
    void toolUseRoundTripsThroughCommonTypes() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":{"role":"assistant","content":"",
                           "tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Delhi"}}}]},
                         "done":true,"done_reason":"stop","prompt_eval_count":20,"eval_count":8}
                        """)));

        messages("""
                {"model":"msg-ollama/qwen3:1.7b","max_tokens":256,
                 "tools":[{"name":"get_weather","description":"Weather for a city",
                           "input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],
                 "messages":[
                   {"role":"user","content":"Weather in Pune?"},
                   {"role":"assistant","content":[
                     {"type":"thinking","thinking":"need a tool","signature":"abc"},
                     {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Pune"}}]},
                   {"role":"user","content":[
                     {"type":"tool_result","tool_use_id":"toolu_1","content":"sunny"},
                     {"type":"text","text":"And Delhi?"}]}]}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.stop_reason").isEqualTo("tool_use")
                .jsonPath("$.content[0].type").isEqualTo("tool_use")
                .jsonPath("$.content[0].name").isEqualTo("get_weather")
                .jsonPath("$.content[0].input.city").isEqualTo("Delhi")
                .jsonPath("$.content[0].id").value(id -> assertThat(id.toString()).isNotBlank());

        // Thinking dropped; the tool result comes before the turn's follow-up text.
        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.messages[1].thinking", absent()))
                .withRequestBody(matchingJsonPath("$.messages[1].tool_calls[0].function.arguments.city",
                        equalTo("Pune")))
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.messages[2].content", equalTo("sunny")))
                .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[3].content", equalTo("And Delhi?")))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("get_weather"))));
    }

    @Test
    void streamingEmitsNamedAnthropicEvents() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody("""
                        {"message":{"role":"assistant","content":"Hel"},"done":false}
                        {"message":{"role":"assistant","content":"lo"},"done":false}
                        {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":2}
                        """)));

        String body = client.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"msg-ollama/qwen3:1.7b","max_tokens":64,"stream":true,
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("event:message_start", "event:content_block_start",
                "\"text\":\"Hel\"", "\"text\":\"lo\"", "event:content_block_stop",
                "\"stop_reason\":\"end_turn\"", "\"output_tokens\":2", "event:message_stop");
        assertThat(body.indexOf("event:message_start")).isLessThan(body.indexOf("event:content_block_start"));
        assertThat(body.indexOf("event:message_delta")).isLessThan(body.indexOf("event:message_stop"));
        assertThat(body).doesNotContain("[DONE]");
    }

    @Test
    void validationErrorsAreAnthropicShaped() {
        messages("""
                {"model":"msg-ollama/qwen3:1.7b","messages":[{"role":"user","content":"hi"}]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("error")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("max_tokens"));
    }

    @Test
    void unsupportedContentBlockIsRejectedBeforeDispatch() {
        messages("""
                {"model":"msg-ollama/qwen3:1.7b","max_tokens":64,
                 "messages":[{"role":"user","content":[
                   {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("image"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void unknownParameterIsRejectedLoudly() {
        messages("""
                {"model":"msg-ollama/qwen3:1.7b","max_tokens":64,"top_k":5,
                 "messages":[{"role":"user","content":"hi"}]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("top_k"));
    }

    @Test
    void malformedJsonGetsAnthropicErrorShapeOnThisPath() {
        messages("{not json")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("error")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");
    }
}
