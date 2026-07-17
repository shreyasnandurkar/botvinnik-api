package com.shreyasnandurkar.botvinnikapi.control;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.TestcontainersConfiguration;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Control-plane contract: a provider registered over REST must be routable on
 * the very next data-plane request — write → snapshot rebuild → hot path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class ControlPlaneApiTest {

    static WireMockServer wiremock;

    @Autowired
    WebTestClient client;
    @Autowired
    ProviderRepository providerRepository;
    @Autowired
    AliasRepository aliasRepository;
    @Autowired
    ConfigSnapshotService snapshots;

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
    static void yamlSeeds(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "seed-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
        registry.add("botvinnik.providers[1].name", () -> "seed-gemini");
        registry.add("botvinnik.providers[1].type", () -> "gemini");
        registry.add("botvinnik.providers[1].base-url", () -> wiremock.baseUrl());
        registry.add("botvinnik.providers[1].api-key", () -> "AIza-test");
    }

    @BeforeEach
    void cleanSlate() {
        aliasRepository.deleteAll().block();
        providerRepository.deleteAll().block();
        snapshots.rebuild().block();
        wiremock.resetAll();
    }

    // ── providers ───────────────────────────────────────────────────────────

    @Test
    void registeredProviderServesTrafficImmediately() {
        register("dyn-ollama");
        stubOllamaChat("Hello from dynamic provider");

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"dyn-ollama/qwen3:1.7b",
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Hello from dynamic provider");
    }

    @Test
    void registrationIsValidatedStrictly() {
        client.post().uri("/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"no-url","type":"ollama"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.param").isEqualTo("base_url");

        client.post().uri("/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"mystery","type":"openai","base_url":"http://x"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.param").isEqualTo("type");

        register("dup");
        client.post().uri("/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"dup","type":"ollama","base_url":"http://x"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.param").isEqualTo("name");
    }

    @Test
    void apiKeyIsNeverEchoed() {
        client.post().uri("/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"cloud","type":"gemini","api_key":"AIza-secret"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("cloud")
                .jsonPath("$.api_key").doesNotExist();

        client.get().uri("/v1/providers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("cloud")
                .jsonPath("$[0].api_key").doesNotExist();
    }

    @Test
    void disabledProviderStopsRoutingAndReenablingRestoresIt() {
        String id = register("toggle-me");
        stubOllamaChat("ok");

        patchProvider(id, """
                {"status":"disabled"}
                """);
        chat("toggle-me/qwen3:1.7b").expectStatus().isNotFound();

        patchProvider(id, """
                {"status":"active"}
                """);
        chat("toggle-me/qwen3:1.7b").expectStatus().isOk();
    }

    @Test
    void deletedProviderStopsRouting() {
        String id = register("short-lived");
        client.delete().uri("/v1/providers/" + id).exchange().expectStatus().isNoContent();

        chat("short-lived/qwen3:1.7b").expectStatus().isNotFound();
        client.get().uri("/v1/providers")
                .exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    // ── aliases ─────────────────────────────────────────────────────────────

    @Test
    void aliasRoutesToItsTargetAndRetargetsWithoutClientChanges() {
        register("dyn-ollama");
        stubOllamaChat("aliased");

        byte[] created = client.post().uri("/v1/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"alias":"assistant","primary":"dyn-ollama/qwen3:1.7b",
                         "fallbacks":["dyn-ollama/qwen3:4b"]}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.primary").isEqualTo("dyn-ollama/qwen3:1.7b")
                .jsonPath("$.fallbacks[0]").isEqualTo("dyn-ollama/qwen3:4b")
                .returnResult().getResponseBody();
        String aliasId = idOf(created);

        chat("assistant").expectStatus().isOk();
        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("qwen3:1.7b"))));

        client.patch().uri("/v1/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"primary":"dyn-ollama/qwen3:4b"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.primary").isEqualTo("dyn-ollama/qwen3:4b");

        chat("assistant").expectStatus().isOk();
        wiremock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("qwen3:4b"))));
    }

    @Test
    void aliasToUnknownProviderIsRejected() {
        client.post().uri("/v1/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"alias":"ghost","primary":"nobody/some-model"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.param").isEqualTo("primary");
    }

    @Test
    void providerTargetedByAliasCannotBeDeleted() {
        String id = register("pinned");
        client.post().uri("/v1/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"alias":"keeper","primary":"pinned/qwen3:1.7b"}
                        """)
                .exchange()
                .expectStatus().isCreated();

        client.delete().uri("/v1/providers/" + id)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String register(String name) {
        byte[] body = client.post().uri("/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"%s","type":"ollama","base_url":"%s"}
                        """.formatted(name, wiremock.baseUrl()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("active")
                .returnResult().getResponseBody();
        return idOf(body);
    }

    private void patchProvider(String id, String body) {
        client.patch().uri("/v1/providers/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec chat(String model) {
        return client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(model))
                .exchange();
    }

    private void stubOllamaChat(String content) {
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"m","message":{"role":"assistant","content":"%s"},
                         "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}
                        """.formatted(content))));
    }

    private static String idOf(byte[] responseBody) {
        String json = new String(responseBody, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no id in: " + json);
        }
        return m.group(1);
    }
}
