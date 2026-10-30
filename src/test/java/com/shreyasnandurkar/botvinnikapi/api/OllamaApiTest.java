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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test for the Ollama client API: Ollama-shaped requests go in over HTTP,
 * WireMock plays an OpenAI-compatible backend, and Ollama-shaped answers come out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "botvinnik.security.auth-enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OllamaApiTest {

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

    private final ObjectMapper mapper = new ObjectMapper();

    /** Postgres is shared across test contexts; a leftover provider on a dead WireMock breaks routing elsewhere. */
    @AfterAll
    void cleanUp() {
        providerRepository.findByName("oll-up").flatMap(providerRepository::delete).block();
        snapshots.rebuild().block();
        wiremock.stop();
    }

    @DynamicPropertySource
    static void pointProvidersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "oll-up");
        registry.add("botvinnik.providers[0].type", () -> "openai");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl() + "/v1");
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
    }

    private void stubChat(String content) {
        wiremock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
                          "finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}
                        """.formatted(content))));
    }

    private WebTestClient.ResponseSpec send(String uri, String body) {
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    @Test
    void nonStreamingChatInOllamaShapeOut() {
        stubChat("Hello from upstream");

        send("/api/chat", """
                {"model":"oll-up/m","stream":false,"keep_alive":"5m",
                 "options":{"temperature":0.2,"num_predict":64,"num_ctx":8192},
                 "messages":[{"role":"system","content":"Be terse."},{"role":"user","content":"hi"}]}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("m")
                .jsonPath("$.message.role").isEqualTo("assistant")
                .jsonPath("$.message.content").isEqualTo("Hello from upstream")
                .jsonPath("$.done").isEqualTo(true)
                .jsonPath("$.done_reason").isEqualTo("stop")
                .jsonPath("$.prompt_eval_count").isEqualTo(5)
                .jsonPath("$.eval_count").isEqualTo(2);

        wiremock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.2")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("64"))));
    }

    @Test
    void chatStreamsNdjsonByDefault() {
        wiremock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}

                        data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}

                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                        data: [DONE]

                        """)));

        var result = client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"oll-up/m","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .expectBody(String.class).returnResult().getResponseBody();

        List<JsonNode> lines = result.lines().filter(l -> !l.isBlank()).map(mapper::readTree).toList();
        assertThat(lines).extracting(l -> l.at("/message/content").asString()).containsExactly("Hel", "lo", "");
        assertThat(lines.getLast().path("done").asBoolean()).isTrue();
        assertThat(lines.getLast().path("eval_count").asInt()).isEqualTo(2);
    }

    @Test
    void toolResultsGetIdsMatchingTheirCalls() {
        stubChat("It is sunny.");

        send("/api/chat", """
                {"model":"oll-up/m","stream":false,
                 "tools":[{"type":"function","function":{"name":"get_weather","description":"Weather",
                           "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],
                 "messages":[
                   {"role":"user","content":"Weather in Pune?"},
                   {"role":"assistant","content":"","thinking":"need a tool",
                    "tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Pune"}}}]},
                   {"role":"tool","tool_name":"get_weather","content":"sunny"}]}
                """)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.message.content").isEqualTo("It is sunny.");

        // OpenAI-style backends reject a tool result whose tool_call_id matches no call.
        JsonNode upstream = mapper.readTree(
                wiremock.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        String callId = upstream.at("/messages/1/tool_calls/0/id").asString();
        assertThat(callId).startsWith("call_");
        assertThat(upstream.at("/messages/1/tool_calls/0/function/arguments").asString())
                .isEqualTo("{\"city\":\"Pune\"}");
        assertThat(upstream.at("/messages/2/role").asString()).isEqualTo("tool");
        assertThat(upstream.at("/messages/2/tool_call_id").asString()).isEqualTo(callId);
        assertThat(upstream.at("/tools/0/function/name").asString()).isEqualTo("get_weather");
    }

    @Test
    void generateAnswersInTheResponseField() {
        stubChat("Paris");

        send("/api/generate", """
                {"model":"oll-up/m","stream":false,"system":"One word.","prompt":"Capital of France?"}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.response").isEqualTo("Paris")
                .jsonPath("$.done").isEqualTo(true)
                .jsonPath("$.message").doesNotExist();

        wiremock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("One word.")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("Capital of France?"))));
    }

    @Test
    void statefulContextIsRejectedInOllamaErrorShape() {
        send("/api/generate", """
                {"model":"oll-up/m","prompt":"and then?","context":[1,2,3]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").value(m -> assertThat(m.toString()).contains("stateless"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void tagsListDiscoveredModelsAndVersionAnswers() {
        wiremock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"object":"list","data":[{"id":"m"},{"id":"text-embedder"}]}
                        """)));

        client.get().uri("/api/tags").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.models[?(@.name == 'm')].model").isEqualTo("m")
                .jsonPath("$.models[?(@.name == 'text-embedder')]").exists();
        client.get().uri("/api/version").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.version").isNotEmpty();
    }

    @Test
    void embedReturnsOllamaShape() {
        wiremock.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"text-embedder","data":[{"index":0,"embedding":[0.5,0.25]}],
                         "usage":{"prompt_tokens":3,"total_tokens":3}}
                        """)));

        send("/api/embed", """
                {"model":"oll-up/text-embedder","input":"hello","truncate":true}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("text-embedder")
                .jsonPath("$.embeddings[0][1]").isEqualTo(0.25)
                .jsonPath("$.prompt_eval_count").isEqualTo(3);
    }

    @Test
    void bodyIsJsonWhateverTheContentTypeLikeOllama() {
        stubChat("ok");

        // What `curl -d` sends; Ollama's own docs use it.
        client.post().uri("/api/chat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("""
                        {"model":"oll-up/m","stream":false,"messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.message.content").isEqualTo("ok");
    }

    @Test
    void otherPathsStillRejectNonJsonWith415NotA500() {
        client.post().uri("/v1/chat/completions").contentType(MediaType.TEXT_PLAIN)
                .bodyValue("hello")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().jsonPath("$.error.type").isEqualTo("invalid_request_error");
    }
}
