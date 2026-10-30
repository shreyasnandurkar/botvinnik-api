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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test for /v1/embeddings: an OpenAI embeddings request goes in over
 * HTTP, WireMock plays Ollama's /api/embed, and an OpenAI embedding list comes out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "botvinnik.security.auth-enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddingsApiTest {

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
        providerRepository.findByName("emb-ollama").flatMap(providerRepository::delete).block();
        snapshots.rebuild().block();
        wiremock.stop();
    }

    @DynamicPropertySource
    static void pointProvidersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("botvinnik.providers[0].name", () -> "emb-ollama");
        registry.add("botvinnik.providers[0].type", () -> "ollama");
        registry.add("botvinnik.providers[0].base-url", () -> wiremock.baseUrl());
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"nomic-embed-text","embeddings":[[0.5,-1.25],[2.0,0.0]],"prompt_eval_count":4}
                        """)));
    }

    private WebTestClient.ResponseSpec embeddings(String body) {
        return client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    void openAiRequestInEmbeddingListOut() {
        embeddings("""
                {"model":"emb-ollama/nomic-embed-text","input":["first","second"],
                 "encoding_format":"float","dimensions":2}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.model").isEqualTo("nomic-embed-text")
                .jsonPath("$.data[0].object").isEqualTo("embedding")
                .jsonPath("$.data[0].index").isEqualTo(0)
                .jsonPath("$.data[0].embedding[1]").isEqualTo(-1.25)
                .jsonPath("$.data[1].index").isEqualTo(1)
                .jsonPath("$.data[1].embedding[0]").isEqualTo(2.0)
                .jsonPath("$.usage.prompt_tokens").isEqualTo(4)
                .jsonPath("$.usage.total_tokens").isEqualTo(4);

        wiremock.verify(postRequestedFor(urlEqualTo("/api/embed"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("nomic-embed-text")))
                .withRequestBody(matchingJsonPath("$.input[1]", equalTo("second")))
                .withRequestBody(matchingJsonPath("$.dimensions", equalTo("2"))));
    }

    @Test
    void base64EncodingIsLittleEndianFloat32() {
        byte[] body = embeddings("""
                {"model":"emb-ollama/nomic-embed-text","input":"just one","encoding_format":"base64"}
                """)
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        JsonNode json = mapper.readTree(body);
        byte[] raw = Base64.getDecoder().decode(json.at("/data/0/embedding").asString());
        ByteBuffer floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(raw).hasSize(2 * Float.BYTES);
        assertThat(floats.getFloat()).isEqualTo(0.5f);
        assertThat(floats.getFloat()).isEqualTo(-1.25f);
        wiremock.verify(postRequestedFor(urlEqualTo("/api/embed"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("just one"))));
    }

    @Test
    void tokenArrayInputIsRejectedBeforeDispatch() {
        embeddings("""
                {"model":"emb-ollama/nomic-embed-text","input":[[1,2,3]]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.param").isEqualTo("input")
                .jsonPath("$.error.message").value(m -> assertThat(m.toString()).contains("token-array"));
        wiremock.verify(0, postRequestedFor(urlEqualTo("/api/embed")));
    }

    @Test
    void unknownParameterIsRejectedLoudly() {
        embeddings("""
                {"model":"emb-ollama/nomic-embed-text","input":"x","task_type":"retrieval"}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.param").isEqualTo("task_type");
    }
}
