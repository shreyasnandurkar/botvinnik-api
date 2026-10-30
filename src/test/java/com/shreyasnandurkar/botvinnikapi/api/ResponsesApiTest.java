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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test for the Responses client protocol: a Responses request goes in over
 * HTTP, WireMock plays Ollama upstream, and a Responses-shaped answer comes out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "botvinnik.security.auth-enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponsesApiTest {

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
        providerRepository.findByName("resp-ollama").flatMap(providerRepository::delete).block();
        snapshots.rebuild().block();
        wiremock.stop();
    }

    @DynamicPropertySource
    static void pointProvidersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "resp-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
    }

    private WebTestClient.ResponseSpec responses(String body) {
        return client.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    void stringInputInResponseObjectOut() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"qwen3:1.7b","message":{"role":"assistant","content":"Hello from Ollama"},
                         "done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":11}
                        """)));

        responses("""
                {"model":"resp-ollama/qwen3:1.7b","instructions":"You are terse.","input":"hi",
                 "max_output_tokens":256,"store":false,"metadata":{"k":"v"}}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("response")
                .jsonPath("$.id").value(id -> assertThat(id.toString()).startsWith("resp_"))
                .jsonPath("$.status").isEqualTo("completed")
                .jsonPath("$.output[0].type").isEqualTo("message")
                .jsonPath("$.output[0].role").isEqualTo("assistant")
                .jsonPath("$.output[0].content[0].type").isEqualTo("output_text")
                .jsonPath("$.output[0].content[0].text").isEqualTo("Hello from Ollama")
                .jsonPath("$.usage.input_tokens").isEqualTo(7)
                .jsonPath("$.usage.output_tokens").isEqualTo(11)
                .jsonPath("$.usage.total_tokens").isEqualTo(18);

        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("You are terse.")))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("hi")))
                .withRequestBody(matchingJsonPath("$.options.num_predict", equalTo("256"))));
    }

    @Test
    void functionCallItemsRoundTripThroughCommonTypes() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":{"role":"assistant","content":"",
                           "tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Delhi"}}}]},
                         "done":true,"done_reason":"stop","prompt_eval_count":20,"eval_count":8}
                        """)));

        responses("""
                {"model":"resp-ollama/qwen3:1.7b",
                 "tools":[{"type":"function","name":"get_weather","description":"Weather for a city",
                           "parameters":{"type":"object","properties":{"city":{"type":"string"}}},"strict":true}],
                 "input":[
                   {"role":"developer","content":"Use tools."},
                   {"type":"message","role":"user","content":[{"type":"input_text","text":"Weather in Pune?"}]},
                   {"type":"reasoning","id":"rs_1","summary":[]},
                   {"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"Pune\\"}"},
                   {"type":"function_call_output","call_id":"call_1","output":"sunny"},
                   {"role":"user","content":"And Delhi?"}]}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output[0].type").isEqualTo("function_call")
                .jsonPath("$.output[0].name").isEqualTo("get_weather")
                .jsonPath("$.output[0].arguments").isEqualTo("{\"city\":\"Delhi\"}")
                .jsonPath("$.output[0].call_id").value(id -> assertThat(id.toString()).isNotBlank())
                .jsonPath("$.output[0].id").value(id -> assertThat(id.toString()).startsWith("fc_"));

        // Developer becomes system, reasoning is dropped, the call becomes an assistant turn.
        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("Weather in Pune?")))
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].function.arguments.city",
                        equalTo("Pune")))
                .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.messages[3].content", equalTo("sunny")))
                .withRequestBody(matchingJsonPath("$.messages[4].content", equalTo("And Delhi?")))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("get_weather"))));
    }

    @Test
    void streamingEmitsNamedResponseEvents() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody("""
                        {"message":{"role":"assistant","content":"Hel"},"done":false}
                        {"message":{"role":"assistant","content":"lo"},"done":false}
                        {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":2}
                        """)));

        String body = client.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"resp-ollama/qwen3:1.7b","stream":true,"input":"hi"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("event:response.created", "event:response.output_item.added",
                "\"delta\":\"Hel\"", "\"delta\":\"lo\"", "event:response.output_text.done",
                "\"text\":\"Hello\"", "event:response.completed", "\"output_tokens\":2");
        assertThat(body.indexOf("event:response.created")).isLessThan(body.indexOf("event:response.output_item.added"));
        assertThat(body.indexOf("event:response.output_item.done")).isLessThan(body.indexOf("event:response.completed"));
        assertThat(body).doesNotContain("[DONE]");
    }

    @Test
    void previousResponseIdIsRejectedClearly() {
        responses("""
                {"model":"resp-ollama/qwen3:1.7b","input":"and then?","previous_response_id":"resp_abc"}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.param").isEqualTo("previous_response_id")
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("stateless"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void hostedToolsAreRejectedBeforeDispatch() {
        responses("""
                {"model":"resp-ollama/qwen3:1.7b","input":"news?","tools":[{"type":"web_search"}]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.param").isEqualTo("tools")
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("web_search"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void imageInputIsRejectedBeforeDispatch() {
        responses("""
                {"model":"resp-ollama/qwen3:1.7b","input":[{"role":"user","content":[
                  {"type":"input_image","image_url":"https://example.com/a.png"}]}]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("input_image"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void cutOffAnswerIsIncomplete() {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":{"role":"assistant","content":"Once upon"},
                         "done":true,"done_reason":"length","prompt_eval_count":3,"eval_count":2}
                        """)));

        responses("""
                {"model":"resp-ollama/qwen3:1.7b","input":"tell a story","max_output_tokens":2}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("incomplete")
                .jsonPath("$.incomplete_details.reason").isEqualTo("max_output_tokens");
    }
}
