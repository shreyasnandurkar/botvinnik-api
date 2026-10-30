package com.shreyasnandurkar.botvinnikapi.providers.openai;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
import com.shreyasnandurkar.botvinnikapi.core.error.StreamIdleTimeoutException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Adapter for any OpenAI-compatible server: OpenAI itself, vLLM, llama.cpp, LM Studio,
 * Groq, Together, OpenRouter and the like. The base URL includes the version prefix
 * (e.g. https://api.openai.com/v1); the api key is optional for local servers.
 */
public class OpenAiCompatProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(60);
    private static final String DONE = "[DONE]";

    private final String name;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final Duration streamIdleTimeout;

    public OpenAiCompatProvider(String name, String baseUrl, String apiKey,
                                WebClient.Builder builder, ObjectMapper mapper) {
        this(name, baseUrl, apiKey, builder, mapper, null);
    }

    public OpenAiCompatProvider(String name, String baseUrl, String apiKey,
                                WebClient.Builder builder, ObjectMapper mapper, Duration streamIdleTimeout) {
        this.name = name;
        this.mapper = mapper;
        this.streamIdleTimeout = streamIdleTimeout == null ? DEFAULT_STREAM_IDLE_TIMEOUT : streamIdleTimeout;
        WebClient.Builder b = builder.clone()
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.client = b.build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        OpenAiApi.ChatRequest body = toOpenAiRequest(request, false);
        return client.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                .bodyToMono(OpenAiApi.ChatResponse.class)
                .map(resp -> normalize(resp, request.model()))
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        OpenAiApi.ChatRequest body = toOpenAiRequest(request, true);
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return client.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                    .bodyToFlux(String.class)
                    // Idle timeout between chunks, not total request time (§10).
                    .timeout(streamIdleTimeout)
                    .concatMap(data -> Flux.fromIterable(normalizeChunk(data, state)))
                    // Servers that ignore include_usage never send the usage chunk.
                    .concatWith(Flux.defer(() -> Flux.fromIterable(flushFinish(state))))
                    .onErrorMap(TimeoutException.class, e -> new StreamIdleTimeoutException(name, streamIdleTimeout))
                    .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e))
                    .doOnCancel(() -> log.info("Provider '{}': client cancelled stream; upstream connection closed", name));
        });
    }

    /**
     * finish_reason and usage arrive in separate chunks (usage last, with empty choices),
     * but downstream reads usage off the finish chunk, so the finish is held until usage lands.
     */
    private static final class StreamState {
        boolean hasToolCalls;
        String finishReason;
        TokenUsage usage;
        boolean finished;
    }

    private List<ChatChunk> normalizeChunk(String data, StreamState state) {
        if (data.isBlank() || data.trim().equals(DONE)) {
            return List.of();
        }
        OpenAiApi.ChatResponse chunk;
        try {
            chunk = mapper.readValue(data, OpenAiApi.ChatResponse.class);
        } catch (Exception e) {
            log.warn("Provider '{}': skipping malformed stream chunk: {}", name, e.getMessage());
            return List.of();
        }
        List<ChatChunk> out = new ArrayList<>();
        if (chunk.usage() != null) {
            state.usage = toUsage(chunk.usage());
        }
        if (chunk.choices() != null && !chunk.choices().isEmpty()) {
            OpenAiApi.Choice choice = chunk.choices().getFirst();
            OpenAiApi.Msg delta = choice.delta();
            if (delta != null) {
                if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                    out.add(ChatChunk.reasoning(delta.reasoningContent()));
                }
                if (delta.content() != null && !delta.content().isEmpty()) {
                    out.add(ChatChunk.content(delta.content()));
                }
                if (delta.toolCalls() != null) {
                    // Already fragmented OpenAI-style; pass each fragment straight through.
                    for (OpenAiApi.ToolCallObj tc : delta.toolCalls()) {
                        state.hasToolCalls = true;
                        out.add(new ChatChunk(null, null, new ToolCallDelta(
                                tc.index() == null ? 0 : tc.index(), tc.id(),
                                tc.function() == null ? null : tc.function().name(),
                                tc.function() == null ? null : tc.function().arguments()),
                                null, null));
                    }
                }
            }
            if (choice.finishReason() != null) {
                state.finishReason = choice.finishReason();
            }
        }
        if (state.finishReason != null && state.usage != null) {
            out.addAll(flushFinish(state));
        }
        return out;
    }

    private static List<ChatChunk> flushFinish(StreamState state) {
        if (state.finished || state.finishReason == null) {
            return List.of();
        }
        state.finished = true;
        return List.of(new ChatChunk(null, null, null,
                normalizeFinishReason(state.finishReason, state.hasToolCalls), state.usage));
    }

    @Override
    public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return client.post()
                .uri("/embeddings")
                .bodyValue(new OpenAiApi.EmbeddingsRequest(request.model(), request.input(),
                        request.dimensions(), "float"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                .bodyToMono(OpenAiApi.EmbeddingsResponse.class)
                .map(resp -> new EmbeddingResponse(
                        // data is documented as ordered, but index is the contract.
                        resp.data() == null ? List.of() : resp.data().stream()
                                .sorted(Comparator.comparing(d -> d.index() == null ? 0 : d.index()))
                                .map(OpenAiApi.EmbeddingObj::embedding)
                                .toList(),
                        resp.usage() == null || resp.usage().promptTokens() == null ? null
                                : new TokenUsage(resp.usage().promptTokens(), 0),
                        name, resp.model() == null ? request.model() : resp.model()))
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    @Override
    public Mono<HealthStatus> healthCheck() {
        long startNanos = System.nanoTime();
        return client.get()
                .uri("/models")
                .retrieve()
                .toBodilessEntity()
                .map(ignored -> HealthStatus.healthy((System.nanoTime() - startNanos) / 1_000_000))
                .onErrorResume(e -> Mono.just(HealthStatus.down(e.getMessage())));
    }

    @Override
    public Mono<List<ModelInfo>> listModels() {
        return client.get()
                .uri("/models")
                .retrieve()
                .bodyToMono(OpenAiApi.ModelsResponse.class)
                .map(resp -> resp.data() == null ? List.<ModelInfo>of()
                        : resp.data().stream().map(m -> new ModelInfo(m.id(), name)).toList())
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    // ── request mapping ────────────────────────────────────────────────────

    private OpenAiApi.ChatRequest toOpenAiRequest(ChatRequest request, boolean stream) {
        List<OpenAiApi.Msg> messages = request.messages().stream()
                .map(OpenAiCompatProvider::toOpenAiMessage)
                .toList();

        List<OpenAiApi.ToolDef> tools = request.tools() == null || request.tools().isEmpty() ? null
                : request.tools().stream()
                .map(t -> new OpenAiApi.ToolDef("function",
                        new OpenAiApi.FunctionDef(t.name(), t.description(), t.parameters())))
                .toList();

        return new OpenAiApi.ChatRequest(
                request.model(), messages,
                request.temperature(), request.topP(), request.maxTokens(),
                request.stop() == null || request.stop().isEmpty() ? null : request.stop(),
                stream,
                stream ? new OpenAiApi.StreamOptions(true) : null,
                // Only forwarded on opt-in: many compatible servers reject unknown fields.
                request.wantsReasoning() ? request.reasoningEffort() : null,
                tools);
    }

    private static OpenAiApi.Msg toOpenAiMessage(Message m) {
        List<OpenAiApi.ToolCallObj> toolCalls = null;
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            toolCalls = m.toolCalls().stream()
                    .map(tc -> new OpenAiApi.ToolCallObj(null, tc.id(), "function",
                            new OpenAiApi.FunctionCallObj(tc.name(),
                                    tc.argumentsJson() == null ? "{}" : tc.argumentsJson())))
                    .toList();
        }
        return new OpenAiApi.Msg(m.role(), m.content(), null, toolCalls, m.toolCallId(), m.name());
    }

    // ── response normalization ─────────────────────────────────────────────

    private ChatResponse normalize(OpenAiApi.ChatResponse resp, String requestedModel) {
        TokenUsage usage = resp.usage() == null ? TokenUsage.ZERO : toUsage(resp.usage());
        String model = resp.model() == null ? requestedModel : resp.model();

        if (resp.choices() == null || resp.choices().isEmpty() || resp.choices().getFirst().message() == null) {
            return new ChatResponse("", null, null, "stop", usage, name, model);
        }
        OpenAiApi.Choice choice = resp.choices().getFirst();
        OpenAiApi.Msg msg = choice.message();

        List<ToolCall> toolCalls = null;
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            toolCalls = msg.toolCalls().stream()
                    .map(tc -> new ToolCall(
                            tc.id() == null ? ToolCall.generatedId() : tc.id(),
                            tc.function() == null ? null : tc.function().name(),
                            tc.function() == null || tc.function().arguments() == null
                                    ? "{}" : tc.function().arguments()))
                    .toList();
        }

        return new ChatResponse(
                msg.content(),
                msg.reasoningContent() == null || msg.reasoningContent().isEmpty() ? null : msg.reasoningContent(),
                toolCalls,
                normalizeFinishReason(choice.finishReason(), toolCalls != null),
                usage, name, model);
    }

    private static TokenUsage toUsage(OpenAiApi.Usage u) {
        return new TokenUsage(
                u.promptTokens() == null ? 0 : u.promptTokens(),
                u.completionTokens() == null ? 0 : u.completionTokens());
    }

    /** Already OpenAI vocabulary; only fill the gaps some compatible servers leave. */
    private static String normalizeFinishReason(String finishReason, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_calls";
        return finishReason == null ? "stop" : finishReason;
    }
}
