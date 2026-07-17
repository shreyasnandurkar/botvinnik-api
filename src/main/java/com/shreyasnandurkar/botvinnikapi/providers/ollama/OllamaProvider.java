package com.shreyasnandurkar.botvinnikapi.providers.ollama;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
import com.shreyasnandurkar.botvinnikapi.core.error.StreamIdleTimeoutException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.Tool;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Adapter for Ollama's native API. Speaks /api/chat and /api/tags directly and
 * not Ollama's OpenAI-compat endpoint, so the LLMProvider abstraction is exercised
 * by a genuinely different wire format.
 */
public class OllamaProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(60);

    private final String name;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final Duration streamIdleTimeout;

    public OllamaProvider(String name, String baseUrl, WebClient.Builder builder, ObjectMapper mapper) {
        this(name, baseUrl, builder, mapper, null);
    }

    public OllamaProvider(String name, String baseUrl, WebClient.Builder builder, ObjectMapper mapper,
                          Duration streamIdleTimeout) {
        this.name = name;
        this.mapper = mapper;
        this.streamIdleTimeout = streamIdleTimeout == null ? DEFAULT_STREAM_IDLE_TIMEOUT : streamIdleTimeout;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(RESPONSE_TIMEOUT);
        this.client = builder.clone()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        OllamaApi.ChatRequest body = toOllamaRequest(request, false);
        long startNanos = System.nanoTime();
        return client.post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                .bodyToMono(OllamaApi.ChatResponse.class)
                .map(resp -> normalize(resp, startNanos))
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        OllamaApi.ChatRequest body = toOllamaRequest(request, true);
        // defer: the per-stream state must be fresh for every subscription.
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return client.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                    // Ollama streams NDJSON; the default String decoder splits it into lines.
                    .bodyToFlux(String.class)
                    // Idle timeout between chunks, not total request time (§10).
                    .timeout(streamIdleTimeout)
                    .concatMap(line -> Flux.fromIterable(normalizeLine(line, state)))
                    .onErrorMap(TimeoutException.class, e -> new StreamIdleTimeoutException(name, streamIdleTimeout))
                    .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e))
                    .doOnCancel(() -> log.info("Provider '{}': client cancelled stream; upstream connection closed", name));
        });
    }

    /** Per-stream mutable state: tool-call indexing and the finish-reason override. */
    private static final class StreamState {
        int toolCallIndex;
        boolean hasToolCalls;
    }

    private List<ChatChunk> normalizeLine(String line, StreamState state) {
        if (line.isBlank()) {
            return List.of();
        }
        OllamaApi.ChatResponse chunk;
        try {
            chunk = mapper.readValue(line, OllamaApi.ChatResponse.class);
        } catch (Exception e) {
            // §8: fail soft — one malformed chunk must not kill an otherwise good stream.
            log.warn("Provider '{}': skipping malformed stream chunk: {}", name, e.getMessage());
            return List.of();
        }
        List<ChatChunk> out = new ArrayList<>();
        if (chunk.message() != null) {
            if (chunk.message().thinking() != null && !chunk.message().thinking().isEmpty()) {
                out.add(ChatChunk.reasoning(chunk.message().thinking()));
            }
            if (chunk.message().content() != null && !chunk.message().content().isEmpty()) {
                out.add(ChatChunk.content(chunk.message().content()));
            }
            if (chunk.message().toolCalls() != null) {
                // Ollama emits each tool call whole, not fragmented — one delta carries everything.
                for (OllamaApi.ToolCallObj tc : chunk.message().toolCalls()) {
                    state.hasToolCalls = true;
                    out.add(new ChatChunk(null, null, new ToolCallDelta(
                            state.toolCallIndex++, ToolCall.generatedId(), tc.function().name(),
                            tc.function().arguments() == null ? "{}" : tc.function().arguments().toString()),
                            null, null));
                }
            }
        }
        if (chunk.done()) {
            // Usage exists only here, in the final chunk (§6 ⑪).
            out.add(new ChatChunk(null, null, null,
                    normalizeFinishReason(chunk.doneReason(), state.hasToolCalls),
                    new TokenUsage(
                            chunk.promptEvalCount() == null ? 0 : chunk.promptEvalCount(),
                            chunk.evalCount() == null ? 0 : chunk.evalCount())));
        }
        return out;
    }

    @Override
    public Mono<HealthStatus> healthCheck() {
        long startNanos = System.nanoTime();
        return client.get()
                .uri("/api/tags")
                .retrieve()
                .toBodilessEntity()
                .map(ignored -> HealthStatus.healthy(millisSince(startNanos)))
                .onErrorResume(e -> Mono.just(HealthStatus.down(e.getMessage())));
    }

    @Override
    public Mono<List<ModelInfo>> listModels() {
        return client.get()
                .uri("/api/tags")
                .retrieve()
                .bodyToMono(OllamaApi.TagsResponse.class)
                .map(tags -> tags.models() == null ? List.<ModelInfo>of()
                        : tags.models().stream().map(t -> new ModelInfo(t.name(), name)).toList())
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    // ── request mapping ────────────────────────────────────────────────────

    private OllamaApi.ChatRequest toOllamaRequest(ChatRequest request, boolean stream) {
        List<OllamaApi.Msg> messages = request.messages().stream()
                .map(this::toOllamaMessage)
                .toList();

        Map<String, Object> options = new LinkedHashMap<>();
        if (request.temperature() != null) options.put("temperature", request.temperature());
        if (request.topP() != null) options.put("top_p", request.topP());
        if (request.maxTokens() != null) options.put("num_predict", request.maxTokens());
        if (request.stop() != null && !request.stop().isEmpty()) options.put("stop", request.stop());

        List<OllamaApi.ToolDef> tools = request.tools() == null ? null
                : request.tools().stream()
                .map(t -> new OllamaApi.ToolDef("function",
                        new OllamaApi.FunctionDef(t.name(), t.description(), t.parameters())))
                .toList();

        return new OllamaApi.ChatRequest(
                request.model(), messages, stream,
                // Explicit think:false unless the client opted in via reasoning_effort —
                // otherwise qwen3-style models bill thousands of tokens nobody sees.
                request.wantsReasoning(),
                options.isEmpty() ? null : options, tools);
    }

    private OllamaApi.Msg toOllamaMessage(Message m) {
        List<OllamaApi.ToolCallObj> toolCalls = null;
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            toolCalls = m.toolCalls().stream()
                    // OpenAI carries arguments as a JSON string; Ollama wants the object.
                    .map(tc -> new OllamaApi.ToolCallObj(new OllamaApi.FunctionCallObj(
                            tc.name(), mapper.readTree(tc.argumentsJson() == null ? "{}" : tc.argumentsJson()))))
                    .toList();
        }
        return new OllamaApi.Msg(m.role(), m.content(), null, toolCalls);
    }

    // ── response normalization ─────────────────────────────────────────────

    private ChatResponse normalize(OllamaApi.ChatResponse resp, long startNanos) {
        String content = resp.message() == null ? null : resp.message().content();
        String reasoning = resp.message() == null ? null : emptyToNull(resp.message().thinking());

        List<ToolCall> toolCalls = null;
        if (resp.message() != null && resp.message().toolCalls() != null && !resp.message().toolCalls().isEmpty()) {
            toolCalls = new ArrayList<>();
            for (OllamaApi.ToolCallObj tc : resp.message().toolCalls()) {
                // Ollama assigns no call id — generate one so OpenAI clients get the shape they expect.
                toolCalls.add(new ToolCall(
                        ToolCall.generatedId(),
                        tc.function().name(),
                        tc.function().arguments() == null ? "{}" : tc.function().arguments().toString()));
            }
        }

        TokenUsage usage = new TokenUsage(
                resp.promptEvalCount() == null ? 0 : resp.promptEvalCount(),
                resp.evalCount() == null ? 0 : resp.evalCount());

        return new ChatResponse(
                content, reasoning, toolCalls,
                normalizeFinishReason(resp.doneReason(), toolCalls != null),
                usage, name, resp.model(), millisSince(startNanos));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Map Ollama's done_reason vocabulary onto OpenAI's. */
    private static String normalizeFinishReason(String doneReason, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_calls";
        if (doneReason == null || doneReason.equals("stop")) return "stop";
        if (doneReason.equals("length")) return "length";
        return doneReason;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}