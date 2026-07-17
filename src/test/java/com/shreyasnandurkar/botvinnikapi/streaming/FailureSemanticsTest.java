package com.shreyasnandurkar.botvinnikapi.streaming;

import com.shreyasnandurkar.botvinnikapi.api.OpenAiSseEncoder;
import com.shreyasnandurkar.botvinnikapi.core.error.StreamIdleTimeoutException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.providers.ollama.OllamaProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §17 failure-mode suite. Uses a raw reactor-netty server instead of WireMock:
 * these tests need a server that can die mid-body, hang forever, and report
 * when its connection is disposed — none of which a stub server can simulate.
 */
class FailureSemanticsTest {

    private static final Duration BLOCK = Duration.ofSeconds(10);

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiSseEncoder encoder = new OpenAiSseEncoder(mapper);
    private DisposableServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void shouldCancelUpstreamWhenClientDisconnects() throws Exception {
        CountDownLatch upstreamClosed = new CountDownLatch(1);
        serve((req, res) -> {
            req.withConnection(c -> c.onDispose(upstreamClosed::countDown));
            // An endless stream: only cancellation can end it.
            return res.sendString(req.receive().aggregate().asString()
                    .thenMany(Flux.interval(Duration.ofMillis(20)).map(i -> line("tok" + i))));
        });

        List<ChatChunk> received = provider(null).stream(request())
                .take(3)
                .collectList()
                .block(BLOCK);

        assertThat(received).hasSize(3);
        assertThat(upstreamClosed.await(5, TimeUnit.SECONDS))
                .as("cancel must propagate through the chain and close the upstream connection")
                .isTrue();
    }

    @Test
    void shouldEmitErrorChunkWhenProviderDiesMidStream() {
        serveDyingMidStream();

        List<String> frames = encoder.encode(provider(null).stream(request()), "m", false)
                .collectList()
                .block(BLOCK);

        assertThat(frames.getFirst()).contains("Hello");
        assertThat(frames.get(frames.size() - 2))
                .contains("\"error\"")
                .contains("stream_interrupted");
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void shouldNotFailoverAfterFirstChunkFlushed() {
        serveDyingMidStream();

        AtomicReference<Throwable> escaped = new AtomicReference<>();
        List<String> frames = encoder.encode(provider(null).stream(request()), "m", false)
                .doOnError(escaped::set)
                .onErrorResume(e -> Flux.empty())
                .collectList()
                .block(BLOCK);

        assertThat(escaped.get())
                .as("once a frame is flushed the 200 is committed — no error may escape the stream")
                .isNull();
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void shouldTimeoutOnIdleStream() {
        serve((req, res) -> res.sendString(req.receive().aggregate().asString()
                .thenMany(Flux.concat(Flux.just(line("tok")), Flux.never()))));

        List<String> frames = encoder.encode(provider(Duration.ofMillis(300)).stream(request()), "m", false)
                .collectList()
                .block(BLOCK);

        assertThat(frames.getFirst()).contains("tok");
        assertThat(frames.get(frames.size() - 2)).contains("stream_idle_timeout");
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void idleTimeoutBeforeFirstChunkPropagatesAsException() {
        serve((req, res) -> res.sendString(req.receive().aggregate().asString()
                .thenMany(Flux.never())));

        assertThatThrownBy(() -> encoder.encode(provider(Duration.ofMillis(200)).stream(request()), "m", false)
                .collectList()
                .block(BLOCK))
                .isInstanceOf(StreamIdleTimeoutException.class);
    }

    @Test
    void errorBeforeFirstChunkStaysFailoverEligible() {
        serve((req, res) -> res.status(500).sendString(Mono.just("boom")));

        List<String> emitted = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> encoder.encode(provider(null).stream(request()), "m", false)
                .doOnNext(emitted::add)
                .collectList()
                .block(BLOCK))
                .isInstanceOf(UpstreamException.class);
        assertThat(emitted).as("no frames before the failure means failover is still possible").isEmpty();
    }

    // ── scaffolding ─────────────────────────────────────────────────────────

    private void serve(BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> handler) {
        server = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/api/chat", handler))
                .bindNow();
    }

    /** Two good NDJSON lines, then the connection is aborted without a terminal chunk. */
    private void serveDyingMidStream() {
        serve((req, res) -> res.sendString(req.receive().aggregate().asString()
                .thenMany(Flux.concat(
                        Flux.just(line("Hello"), line(" world")).delayElements(Duration.ofMillis(20)),
                        Mono.delay(Duration.ofMillis(50)).then(Mono.<String>error(
                                new IllegalStateException("upstream died")))))));
    }

    private OllamaProvider provider(Duration idleTimeout) {
        return new OllamaProvider("test-ollama", "http://localhost:" + server.port(),
                WebClient.builder(), mapper, idleTimeout);
    }

    private static String line(String content) {
        return "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"done\":false}\n";
    }

    private static ChatRequest request() {
        return new ChatRequest("m", List.of(Message.text("user", "hi")),
                null, null, null, null, true, null, null);
    }
}
