package com.shreyasnandurkar.botvinnikapi.providers.anthropic;

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
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Adapter for Anthropic's Messages API: system prompt out of band, tool calls and
 * results as content blocks, and streaming as named SSE events.
 */
public class AnthropicProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final String API_VERSION = "2023-06-01";
    // max_tokens is mandatory on Anthropic; OpenAI clients usually omit it.
    static final int DEFAULT_MAX_TOKENS = 16000;
    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(60);

    private final String name;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final Duration streamIdleTimeout;

    public AnthropicProvider(String name, String baseUrl, String apiKey,
                             WebClient.Builder builder, ObjectMapper mapper, Duration streamIdleTimeout) {
        this.name = name;
        this.mapper = mapper;
        this.streamIdleTimeout = streamIdleTimeout == null ? DEFAULT_STREAM_IDLE_TIMEOUT : streamIdleTimeout;
        this.client = builder.clone()
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        AnthropicApi.MessagesRequest body = toAnthropicRequest(request, false);
        return client.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                .bodyToMono(AnthropicApi.MessagesResponse.class)
                .map(resp -> normalize(resp, request.model()))
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        AnthropicApi.MessagesRequest body = toAnthropicRequest(request, true);
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return client.post()
                    .uri("/v1/messages")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                    // SSE "event:" names are dropped here; each data payload repeats its type.
                    .bodyToFlux(String.class)
                    // Idle timeout between chunks, not total request time (§10).
                    .timeout(streamIdleTimeout)
                    .concatMap(data -> Flux.fromIterable(normalizeEvent(data, state)))
                    .onErrorMap(TimeoutException.class, e -> new StreamIdleTimeoutException(name, streamIdleTimeout))
                    .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e))
                    .doOnCancel(() -> log.info("Provider '{}': client cancelled stream; upstream connection closed", name));
        });
    }

    /**
     * Prompt tokens arrive on message_start, output tokens and stop_reason on message_delta.
     * Block indexes count text blocks too, so tool blocks are renumbered 0..n for OpenAI.
     */
    private static final class StreamState {
        int promptTokens;
        final Map<Integer, Integer> toolIndexByBlock = new HashMap<>();
        boolean finished;
    }

    private List<ChatChunk> normalizeEvent(String data, StreamState state) {
        if (data.isBlank()) {
            return List.of();
        }
        AnthropicApi.StreamEvent event;
        try {
            event = mapper.readValue(data, AnthropicApi.StreamEvent.class);
        } catch (Exception e) {
            log.warn("Provider '{}': skipping malformed stream event: {}", name, e.getMessage());
            return List.of();
        }
        if (event.type() == null) {
            return List.of();
        }
        switch (event.type()) {
            case "message_start" -> {
                if (event.message() != null && event.message().usage() != null) {
                    state.promptTokens = promptTokens(event.message().usage());
                }
            }
            case "content_block_start" -> {
                AnthropicApi.Block block = event.contentBlock();
                if (block != null && "tool_use".equals(block.type()) && event.index() != null) {
                    int toolIndex = state.toolIndexByBlock.size();
                    state.toolIndexByBlock.put(event.index(), toolIndex);
                    return List.of(toolDelta(new ToolCallDelta(toolIndex,
                            block.id() == null ? ToolCall.generatedId() : block.id(), block.name(), "")));
                }
            }
            case "content_block_delta" -> {
                AnthropicApi.Delta delta = event.delta();
                if (delta == null || delta.type() == null) {
                    return List.of();
                }
                switch (delta.type()) {
                    case "text_delta" -> {
                        if (delta.text() != null && !delta.text().isEmpty()) {
                            return List.of(ChatChunk.content(delta.text()));
                        }
                    }
                    case "thinking_delta" -> {
                        if (delta.thinking() != null && !delta.thinking().isEmpty()) {
                            return List.of(ChatChunk.reasoning(delta.thinking()));
                        }
                    }
                    case "input_json_delta" -> {
                        Integer toolIndex = state.toolIndexByBlock.get(event.index());
                        if (toolIndex != null && delta.partialJson() != null && !delta.partialJson().isEmpty()) {
                            return List.of(toolDelta(new ToolCallDelta(toolIndex, null, null, delta.partialJson())));
                        }
                    }
                    default -> {
                        // signature_delta and future delta types carry nothing for OpenAI clients.
                    }
                }
            }
            case "message_delta" -> {
                if (state.finished) {
                    return List.of();
                }
                state.finished = true;
                AnthropicApi.Usage u = event.usage();
                int prompt = u != null && u.inputTokens() != null ? promptTokens(u) : state.promptTokens;
                int completion = u == null || u.outputTokens() == null ? 0 : u.outputTokens();
                String stopReason = event.delta() == null ? null : event.delta().stopReason();
                return List.of(new ChatChunk(null, null, null,
                        normalizeStopReason(stopReason, !state.toolIndexByBlock.isEmpty()),
                        new TokenUsage(prompt, completion)));
            }
            // Errors can arrive mid-stream with HTTP 200 already sent, e.g. overloaded_error.
            case "error" -> throw new UpstreamException(name, errorStatus(event.error()), data);
            default -> {
                // ping, content_block_stop, message_stop
            }
        }
        return List.of();
    }

    private static ChatChunk toolDelta(ToolCallDelta delta) {
        return new ChatChunk(null, null, delta, null, null);
    }

    /** Maps in-band error types to the HTTP status Anthropic uses for them, so breakers classify them. */
    private static int errorStatus(AnthropicApi.ErrorObj error) {
        if (error == null || error.type() == null) return 500;
        return switch (error.type()) {
            case "invalid_request_error" -> 400;
            case "authentication_error" -> 401;
            case "permission_error" -> 403;
            case "not_found_error" -> 404;
            case "request_too_large" -> 413;
            case "rate_limit_error" -> 429;
            case "overloaded_error" -> 529;
            default -> 500;
        };
    }

    @Override
    public Mono<HealthStatus> healthCheck() {
        long startNanos = System.nanoTime();
        return client.get()
                .uri("/v1/models?limit=1")
                .retrieve()
                .toBodilessEntity()
                .map(ignored -> HealthStatus.healthy((System.nanoTime() - startNanos) / 1_000_000))
                .onErrorResume(e -> Mono.just(HealthStatus.down(e.getMessage())));
    }

    @Override
    public Mono<List<ModelInfo>> listModels() {
        return client.get()
                // 1000 is the page maximum; a single page covers Anthropic's whole catalog.
                .uri("/v1/models?limit=1000")
                .retrieve()
                .bodyToMono(AnthropicApi.ModelsResponse.class)
                .map(resp -> resp.data() == null ? List.<ModelInfo>of()
                        : resp.data().stream().map(m -> new ModelInfo(m.id(), name)).toList())
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    // ── request mapping ────────────────────────────────────────────────────

    private AnthropicApi.MessagesRequest toAnthropicRequest(ChatRequest request, boolean stream) {
        StringBuilder system = new StringBuilder();
        List<AnthropicApi.Msg> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            if ("system".equals(m.role())) {
                // Anthropic takes the system prompt as a top-level field, not a message.
                if (m.content() != null && !m.content().isBlank()) {
                    if (!system.isEmpty()) system.append("\n\n");
                    system.append(m.content());
                }
                continue;
            }
            List<AnthropicApi.Block> blocks = toBlocks(m);
            if (blocks.isEmpty()) {
                continue;
            }
            String role = "assistant".equals(m.role()) ? "assistant" : "user";
            // Tool results become user turns; parallel results must share one message.
            if (!messages.isEmpty() && messages.getLast().role().equals(role)) {
                messages.getLast().content().addAll(blocks);
            } else {
                messages.add(new AnthropicApi.Msg(role, new ArrayList<>(blocks)));
            }
        }

        List<AnthropicApi.ToolDef> tools = request.tools() == null || request.tools().isEmpty() ? null
                : request.tools().stream()
                .map(t -> new AnthropicApi.ToolDef(t.name(), t.description(),
                        t.parameters() == null ? mapper.createObjectNode().put("type", "object") : t.parameters()))
                .toList();

        // Reasoning off means no thinking field; models whose thinking is always on still think.
        boolean reasoning = request.wantsReasoning();
        return new AnthropicApi.MessagesRequest(
                request.model(),
                request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens(),
                system.isEmpty() ? null : system.toString(),
                messages,
                request.temperature(), request.topP(),
                request.stop() == null || request.stop().isEmpty() ? null : request.stop(),
                stream,
                tools,
                reasoning ? new AnthropicApi.Thinking("adaptive", "summarized") : null,
                reasoning ? new AnthropicApi.OutputConfig(toEffort(request.reasoningEffort())) : null);
    }

    private List<AnthropicApi.Block> toBlocks(Message m) {
        List<AnthropicApi.Block> blocks = new ArrayList<>();
        if ("tool".equals(m.role())) {
            blocks.add(AnthropicApi.Block.toolResult(m.toolCallId(), m.content() == null ? "" : m.content()));
            return blocks;
        }
        // Anthropic rejects empty text blocks.
        if (m.content() != null && !m.content().isEmpty()) {
            blocks.add(AnthropicApi.Block.text(m.content()));
        }
        if (m.toolCalls() != null) {
            for (ToolCall tc : m.toolCalls()) {
                blocks.add(AnthropicApi.Block.toolUse(tc.id(), tc.name(), parseArguments(tc.argumentsJson())));
            }
        }
        return blocks;
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception e) {
            log.warn("Provider '{}': tool arguments are not valid JSON, sending {{}}: {}", name, e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** OpenAI's "minimal" has no Anthropic equivalent; the rest share names. */
    private static String toEffort(String reasoningEffort) {
        return "minimal".equals(reasoningEffort) ? "low" : reasoningEffort;
    }

    // ── response normalization ─────────────────────────────────────────────

    private ChatResponse normalize(AnthropicApi.MessagesResponse resp, String requestedModel) {
        AnthropicApi.Usage u = resp.usage();
        TokenUsage usage = u == null ? TokenUsage.ZERO
                : new TokenUsage(promptTokens(u), u.outputTokens() == null ? 0 : u.outputTokens());
        String model = resp.model() == null ? requestedModel : resp.model();

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (resp.content() != null) {
            for (AnthropicApi.Block block : resp.content()) {
                switch (block.type() == null ? "" : block.type()) {
                    case "text" -> text.append(block.text() == null ? "" : block.text());
                    case "thinking" -> reasoning.append(block.thinking() == null ? "" : block.thinking());
                    case "tool_use" -> toolCalls.add(new ToolCall(
                            block.id() == null ? ToolCall.generatedId() : block.id(),
                            block.name(),
                            block.input() == null ? "{}" : mapper.writeValueAsString(block.input())));
                    default -> {
                        // redacted_thinking and server-tool blocks have no OpenAI equivalent.
                    }
                }
            }
        }

        return new ChatResponse(
                toolCalls.isEmpty() && text.isEmpty() ? "" : text.isEmpty() ? null : text.toString(),
                reasoning.isEmpty() ? null : reasoning.toString(),
                toolCalls.isEmpty() ? null : toolCalls,
                normalizeStopReason(resp.stopReason(), !toolCalls.isEmpty()),
                usage, name, model);
    }

    /** Cached prompt tokens are reported separately from input_tokens but are still prompt. */
    private static int promptTokens(AnthropicApi.Usage u) {
        return (u.inputTokens() == null ? 0 : u.inputTokens())
                + (u.cacheCreationInputTokens() == null ? 0 : u.cacheCreationInputTokens())
                + (u.cacheReadInputTokens() == null ? 0 : u.cacheReadInputTokens());
    }

    /** A refusal is a normal response with content_filter, never an exception. */
    private static String normalizeStopReason(String stopReason, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_calls";
        if (stopReason == null) return "stop";
        return switch (stopReason) {
            case "max_tokens", "model_context_window_exceeded" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> "stop";
        };
    }
}
