package com.shreyasnandurkar.botvinnikapi.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.TestcontainersConfiguration;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 7 ingress contract: auth, rate limits, spend caps, key-at-rest
 * encryption and SSRF rejection — all against the real HTTP surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    static WireMockServer wiremock;

    @Autowired
    WebTestClient client;
    @Autowired
    ApiKeyRepository apiKeyRepository;
    @Autowired
    ProviderRepository providerRepository;
    @Autowired
    ConfigSnapshotService snapshots;

    private final ObjectMapper mapper = new ObjectMapper();

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
    static void gatewayConfig(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "auth-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
        // $0.01 in + $0.01 out per stubbed request (10 + 5 tokens) — makes spend caps testable.
        registry.add("botvinnik.pricing[0].model", () -> "paid-model");
        registry.add("botvinnik.pricing[0].input-usd-per-1m", () -> "1000");
        registry.add("botvinnik.pricing[0].output-usd-per-1m", () -> "2000");
    }

    @BeforeEach
    void cleanSlate() {
        apiKeyRepository.deleteAll().block();
        snapshots.rebuild().block();
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"paid-model","message":{"role":"assistant","content":"hello"},
                         "done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5}
                        """)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String mintKey(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "test-app"));
        body.putAll(overrides);
        byte[] response = client.post().uri("/v1/apikeys")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();
        return mapper.readTree(response).path("key").asString();
    }

    private WebTestClient.ResponseSpec chat(String bearer) {
        WebTestClient.RequestBodySpec spec = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = (WebTestClient.RequestBodySpec) spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.bodyValue(Map.of(
                        "model", "auth-ollama/paid-model",
                        "messages", java.util.List.of(Map.of("role", "user", "content", "hi"))))
                .exchange();
    }

    // ── auth ────────────────────────────────────────────────────────────────

    @Test
    void missingKeyIs401() {
        chat(null).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("missing_api_key");
    }

    @Test
    void unknownKeyIs401() {
        chat("sk_live_not-a-real-key").expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_api_key");
    }

    @Test
    void controlPlaneStaysOpen() {
        client.get().uri("/v1/providers").exchange().expectStatus().isOk();
    }

    @Test
    void mintedKeyWorksAndIsNeverShownAgain() {
        String key = mintKey(Map.of());
        assertThat(key).startsWith("sk_live_");

        chat(key).expectStatus().isOk()
                .expectBody().jsonPath("$.choices[0].message.content").isEqualTo("hello");
        client.get().uri("/v1/models").header("Authorization", "Bearer " + key)
                .exchange().expectStatus().isOk();

        // The listing carries metadata only — no key, no hash.
        byte[] listing = client.get().uri("/v1/apikeys").exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertThat(new String(listing)).doesNotContain(key).doesNotContain(Sha256.hex(key));
    }

    @Test
    void deletedKeyStopsWorking() {
        String key = mintKey(Map.of());
        byte[] listing = client.get().uri("/v1/apikeys").exchange()
                .expectBody().returnResult().getResponseBody();
        String id = mapper.readTree(listing).get(0).path("id").asString();

        client.delete().uri("/v1/apikeys/" + id).exchange().expectStatus().isNoContent();
        chat(key).expectStatus().isUnauthorized();
    }

    // ── rate limits and spend caps ──────────────────────────────────────────

    @Test
    void rateLimitRejectsWithRetryAfter() {
        String key = mintKey(Map.of("rate_limit_rpm", 2));
        chat(key).expectStatus().isOk();
        chat(key).expectStatus().isOk();
        chat(key).expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.error.code").isEqualTo("rate_limit_exceeded");
    }

    @Test
    void spendCapBlocksOnceExhausted() throws InterruptedException {
        String key = mintKey(Map.of("spend_cap_usd", 0.01));
        chat(key).expectStatus().isOk();
        // Telemetry records spend on the provider call's terminal signal; give it a beat.
        for (int i = 0; i < 50; i++) {
            byte[] body = chat(key).expectBody().returnResult().getResponseBody();
            JsonNode json = mapper.readTree(body);
            if (json.path("error").path("code").asString("").equals("spend_cap_exceeded")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Spend cap never kicked in");
    }

    @Test
    void maxTokensCapIsClampedIntoTheUpstreamRequest() {
        String key = mintKey(Map.of("max_tokens_cap", 42));
        chat(key).expectStatus().isOk();
        String upstream = wiremock.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(upstream).contains("\"num_predict\":42");
    }

    // ── credentials at rest and SSRF ────────────────────────────────────────

    @Test
    void providerKeysAreEncryptedAtRest() {
        client.post().uri("/v1/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "enc-gemini", "type", "gemini", "api_key", "AIza-plain-secret"))
                .exchange().expectStatus().isCreated();

        ProviderEntity stored = providerRepository.findByName("enc-gemini").block();
        assertThat(stored.encryptedApiKey()).isNotEqualTo("AIza-plain-secret");
        assertThat(stored.nonce()).isNotNull();

        client.delete().uri("/v1/providers/" + stored.id()).exchange().expectStatus().isNoContent();
    }

    @Test
    void metadataEndpointRegistrationIsRejected() {
        client.post().uri("/v1/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "evil", "type", "ollama",
                        "base_url", "http://169.254.169.254/latest/meta-data/"))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.param").isEqualTo("base_url");
    }

    // ── telemetry ───────────────────────────────────────────────────────────

    @Test
    void requestsLandInLogsAndUsage() throws InterruptedException {
        String key = mintKey(Map.of("log_content", true));
        chat(key).expectStatus().isOk();

        // The sink drains on a 1s cadence (§13) — poll rather than sleep-and-pray.
        for (int i = 0; i < 30; i++) {
            byte[] logs = client.get().uri("/v1/logs?provider=auth-ollama&outcome=success")
                    .exchange().expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();
            JsonNode rows = mapper.readTree(logs);
            if (!rows.isEmpty()) {
                JsonNode row = rows.get(0);
                assertThat(row.path("model").asString()).isEqualTo("paid-model");
                assertThat(row.path("prompt_tokens").asInt()).isEqualTo(10);
                assertThat(row.path("cost_usd").asDouble()).isGreaterThan(0);
                assertThat(row.path("response_excerpt").asString()).isEqualTo("hello");

                byte[] usage = client.get().uri("/v1/usage").exchange().expectStatus().isOk()
                        .expectBody().returnResult().getResponseBody();
                assertThat(new String(usage)).contains("paid-model");
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Request never appeared in /v1/logs");
    }
}
