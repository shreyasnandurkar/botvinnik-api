package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.error.NoAvailableProviderException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.providers.ollama.OllamaProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §10 failover ladder: connect-time failures walk the alias fallback chain,
 * the circuit breaker short-circuits a dead primary, and nothing fails over
 * once the first chunk has been flushed.
 */
class RoutingFailoverTest {

    private static final Duration BLOCK = Duration.ofSeconds(10);

    static WireMockServer primary;
    static WireMockServer backup;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProviderStatsRegistry stats;
    private CircuitBreakers breakers;
    private RequestRouter router;
    private ProviderRegistry registry;
    private DisposableServer nettyServer;

    @BeforeAll
    static void startServers() {
        primary = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        backup = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        primary.start();
        backup.start();
    }

    @AfterAll
    static void stopServers() {
        primary.stop();
        backup.stop();
    }

    @BeforeEach
    void setUp() {
        primary.resetAll();
        backup.resetAll();
        stats = new ProviderStatsRegistry();
        breakers = new CircuitBreakers();
        router = new RequestRouter(new PoolBalancer(stats, breakers), stats, breakers, entry -> {
        });
        registry = registryWithPrimaryAt("http://localhost:" + primary.port());
    }

    @AfterEach
    void tearDown() {
        if (nettyServer != null) {
            nettyServer.disposeNow();
            nettyServer = null;
        }
    }

    private ProviderRegistry registryWithPrimaryAt(String primaryUrl) {
        SequencedMap<String, LLMProvider> providers = new LinkedHashMap<>();
        providers.put("primary", new OllamaProvider("primary", primaryUrl, WebClient.builder(), mapper));
        providers.put("backup", new OllamaProvider("backup", "http://localhost:" + backup.port(),
                WebClient.builder(), mapper));
        return new ProviderRegistry(providers, Map.of(
                "assistant", new ProviderRegistry.AliasRoute("primary", "m", List.of("backup/m"))));
    }

    private static Function<String, ChatRequest> requestFor() {
        return model -> new ChatRequest(model, List.of(Message.text("user", "hi")),
                null, null, null, null, false, null, null);
    }

    private static void stubChatOk(WireMockServer server, String content) {
        server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"m","message":{"role":"assistant","content":"%s"},
                         "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}
                        """.formatted(content))));
    }

    private static void stubChatFailing(WireMockServer server) {
        server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse().withStatus(500)));
    }

    @Test
    void shouldFailoverWhenCircuitOpen() {
        stubChatFailing(primary);
        stubChatOk(backup, "served by backup");
        ProviderRegistry.RoutePlan plan = registry.resolveRoute("assistant");

        for (int i = 0; i < 6; i++) {
            ChatResponse resp = router.chat(plan, requestFor()).block(BLOCK);
            assertThat(resp.content()).isEqualTo("served by backup");
        }

        // 4 failures trip the breaker (minimumNumberOfCalls); afterwards the dead
        // primary is short-circuited without a wire call.
        assertThat(breakers.of("primary").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        primary.verify(4, postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void streamFailsOverBeforeFirstChunk() {
        stubChatFailing(primary);
        backup.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody("""
                        {"model":"m","message":{"role":"assistant","content":"Hello"},"done":false}
                        {"model":"m","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}
                        """)));
        ProviderRegistry.RoutePlan plan = registry.resolveRoute("assistant");

        List<ChatChunk> chunks = router.stream(plan, requestFor()).collectList().block(BLOCK);

        assertThat(chunks.getFirst().contentDelta()).isEqualTo("Hello");
        assertThat(stats.of("backup").ttftEwmaMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldNotFailoverAfterFirstChunkFlushed() {
        // Primary dies mid-stream after two chunks; the point of no return has
        // passed, so the error propagates and backup must never be dialed.
        nettyServer = HttpServer.create().port(0)
                .route(routes -> routes.post("/api/chat", (req, res) ->
                        res.sendString(req.receive().aggregate().asString().thenMany(Flux.concat(
                                Flux.just(
                                        "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"done\":false}\n",
                                        "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"!\"},\"done\":false}\n")
                                        .delayElements(Duration.ofMillis(20)),
                                Mono.delay(Duration.ofMillis(50)).then(Mono.error(new IllegalStateException("died"))))))))
                .bindNow();
        registry = registryWithPrimaryAt("http://localhost:" + nettyServer.port());
        stubChatOk(backup, "must not be used");
        ProviderRegistry.RoutePlan plan = registry.resolveRoute("assistant");

        assertThatThrownBy(() -> router.stream(plan, requestFor()).collectList().block(BLOCK))
                .isNotNull();
        backup.verify(0, postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void exhaustedChainYields503() {
        stubChatFailing(primary);
        stubChatFailing(backup);
        ProviderRegistry.RoutePlan plan = registry.resolveRoute("assistant");

        assertThatThrownBy(() -> router.chat(plan, requestFor()).block(BLOCK))
                .isInstanceOf(NoAvailableProviderException.class);
    }

    @Test
    void singleProviderErrorsKeepTheirOriginalStatus() {
        stubChatFailing(primary);
        ProviderRegistry.RoutePlan plan = registry.resolveRoute("primary/m");

        assertThatThrownBy(() -> router.chat(plan, requestFor()).block(BLOCK))
                .isInstanceOf(UpstreamException.class);
    }
}
