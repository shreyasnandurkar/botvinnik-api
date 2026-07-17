package com.shreyasnandurkar.botvinnikapi.providers.gemini;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the Gemini API. Genuinely different wire format from both OpenAI
 * and Ollama: model name in the URL path, api key in a header, contents/parts
 * instead of messages, a separate systemInstruction, and provider-level safety
 * filters that must be normalized rather than treated as failures.
 */
public class GeminiProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    private final String name;
    private final WebClient client;
    private final ObjectMapper mapper;

    public GeminiProvider(String name, String baseUrl, String apiKey,
                          WebClient.Builder builder, ObjectMapper mapper) {
        this.name = name;
        this.mapper = mapper;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(RESPONSE_TIMEOUT);
        this.client = builder.clone()
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl)
                .defaultHeader("x-goog-api-key", apiKey)
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        GeminiApi.GenerateContentRequest body = toGeminiRequest(request);
        long startNanos = System.nanoTime();
        return client.post()
                .uri("/v1beta/models/{model}:generateContent", request.model())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                .bodyToMono(GeminiApi.GenerateContentResponse.class)
                .map(resp -> normalize(resp, request.model(), startNanos))
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        GeminiApi.GenerateContentRequest body = toGeminiRequest(request);
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return client.post()
                    .uri("/v1beta/models/{model}:streamGenerateContent?alt=sse", request.model())
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(errBody -> new UpstreamException(name, resp.statusCode().value(), errBody)))
                    .bodyToFlux(String.class)
                    .concatMap(data -> Flux.fromIterable(normalizeChunk(data, state)))
                    .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
        });
    }

    /** Per-stream mutable state: tool-call indexing and the running usage snapshot. */
    private static final class StreamState {
        int toolCallIndex;
        boolean hasToolCalls;
        TokenUsage usage = TokenUsage.ZERO;
    }

    private List<ChatChunk> normalizeChunk(String data, StreamState state) {
        if (data.isBlank()) {
            return List.of();
        }
        GeminiApi.GenerateContentResponse chunk;
        try {
            chunk = mapper.readValue(data, GeminiApi.GenerateContentResponse.class);
        } catch (Exception e) {
            log.warn("Provider '{}': skipping malformed stream chunk: {}", name, e.getMessage());
            return List.of();
        }
        if (chunk.usageMetadata() != null) {
            state.usage = new TokenUsage(
                    nullToZero(chunk.usageMetadata().promptTokenCount()),
                    nullToZero(chunk.usageMetadata().candidatesTokenCount()));
        }
        List<ChatChunk> out = new ArrayList<>();
        if (chunk.candidates() == null || chunk.candidates().isEmpty()) {
            if (chunk.promptFeedback() != null && chunk.promptFeedback().blockReason() != null) {
                out.add(new ChatChunk(null, null, null, "content_filter", state.usage));
            }
            return out;
        }
        GeminiApi.Candidate candidate = chunk.candidates().getFirst();
        if (candidate.content() != null && candidate.content().parts() != null) {
            for (GeminiApi.Part part : candidate.content().parts()) {
                if (part.text() != null && !part.text().isEmpty()) {
                    // Thought parts stream as reasoning deltas, not answer content.
                    out.add(part.isThought() ? ChatChunk.reasoning(part.text())
                            : ChatChunk.content(part.text()));
                }
                if (part.functionCall() != null) {
                    // Gemini emits each tool call whole (a structured part) — one delta carries everything.
                    state.hasToolCalls = true;
                    out.add(new ChatChunk(null, null, new ToolCallDelta(
                            state.toolCallIndex++, ToolCall.generatedId(), part.functionCall().name(),
                            part.functionCall().args() == null ? "{}" : part.functionCall().args().toString()),
                            null, null));
                }
            }
        }
        if (candidate.finishReason() != null) {
            out.add(new ChatChunk(null, null, null,
                    normalizeFinishReason(candidate.finishReason(), state.hasToolCalls),
                    state.usage));
        }
        return out;
    }

    @Override
    public Mono<HealthStatus> healthCheck() {
        long startNanos = System.nanoTime();
        return client.get()
                .uri("/v1beta/models?pageSize=1")
                .retrieve()
                .toBodilessEntity()
                .map(ignored -> HealthStatus.healthy((System.nanoTime() - startNanos) / 1_000_000))
                .onErrorResume(e -> Mono.just(HealthStatus.down(e.getMessage())));
    }

    @Override
    public Mono<List<ModelInfo>> listModels() {
        return client.get()
                .uri("/v1beta/models")
                .retrieve()
                .bodyToMono(GeminiApi.ModelsResponse.class)
                .map(resp -> resp.models() == null ? List.<ModelInfo>of()
                        : resp.models().stream()
                        .filter(m -> m.supportedGenerationMethods() != null
                                && m.supportedGenerationMethods().contains("generateContent"))
                        .map(m -> new ModelInfo(m.name().replaceFirst("^models/", ""), name))
                        .toList())
                .onErrorMap(WebClientRequestException.class, e -> new ProviderUnreachableException(name, e));
    }

    // ── request mapping ────────────────────────────────────────────────────

    private GeminiApi.GenerateContentRequest toGeminiRequest(ChatRequest request) {
        List<GeminiApi.Content> contents = new ArrayList<>();
        StringBuilder system = new StringBuilder();

        for (Message m : request.messages()) {
            switch (m.role()) {
                // Gemini takes system prompts out of band, not as a message.
                case "system" -> {
                    if (!system.isEmpty()) system.append("\n\n");
                    system.append(m.content() == null ? "" : m.content());
                }
                case "user" -> contents.add(new GeminiApi.Content("user",
                        List.of(GeminiApi.Part.text(m.content() == null ? "" : m.content()))));
                case "assistant" -> contents.add(new GeminiApi.Content("model", assistantParts(m)));
                case "tool" -> contents.add(new GeminiApi.Content("user",
                        List.of(GeminiApi.Part.functionResponse(toFunctionResponse(m)))));
                default -> throw new InvalidRequestException(
                        "Unsupported message role '" + m.role() + "'.", "messages");
            }
        }

        GeminiApi.ThinkingConfig thinking = request.wantsReasoning()
                ? new GeminiApi.ThinkingConfig(true) : null;

        GeminiApi.GenerationConfig config = null;
        if (request.temperature() != null || request.topP() != null
                || request.maxTokens() != null || (request.stop() != null && !request.stop().isEmpty())
                || thinking != null) {
            config = new GeminiApi.GenerationConfig(
                    request.temperature(), request.topP(), request.maxTokens(),
                    request.stop() == null || request.stop().isEmpty() ? null : request.stop(),
                    thinking);
        }

        List<GeminiApi.ToolWrapper> tools = null;
        if (request.tools() != null && !request.tools().isEmpty()) {
            tools = List.of(new GeminiApi.ToolWrapper(request.tools().stream()
                    .map(t -> new GeminiApi.FunctionDeclaration(t.name(), t.description(), t.parameters()))
                    .toList()));
        }

        GeminiApi.Content systemInstruction = system.isEmpty() ? null
                : new GeminiApi.Content(null, List.of(GeminiApi.Part.text(system.toString())));

        return new GeminiApi.GenerateContentRequest(contents, systemInstruction, config, tools);
    }

    private List<GeminiApi.Part> assistantParts(Message m) {
        List<GeminiApi.Part> parts = new ArrayList<>();
        if (m.content() != null && !m.content().isBlank()) {
            parts.add(GeminiApi.Part.text(m.content()));
        }
        if (m.toolCalls() != null) {
            for (ToolCall tc : m.toolCalls()) {
                //Gemini wants the object instead of string
                parts.add(GeminiApi.Part.functionCall(new GeminiApi.FunctionCall(
                        tc.name(), mapper.readTree(tc.argumentsJson() == null ? "{}" : tc.argumentsJson()))));
            }
        }
        return parts;
    }

    private GeminiApi.FunctionResponse toFunctionResponse(Message m) {
        String functionName = m.name() != null ? m.name() : m.toolCallId();
        String content = m.content() == null ? "" : m.content();
        JsonNode response;
        try {
            JsonNode parsed = mapper.readTree(content);
            response = parsed.isObject() ? parsed : mapper.createObjectNode().set("result", parsed);
        } catch (Exception notJson) {
            response = mapper.createObjectNode().put("result", content);
        }
        return new GeminiApi.FunctionResponse(functionName, response);
    }

    // ── response normalization ─────────────────────────────────────────────

    private ChatResponse normalize(GeminiApi.GenerateContentResponse resp, String model, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        TokenUsage usage = resp.usageMetadata() == null ? TokenUsage.ZERO
                : new TokenUsage(
                nullToZero(resp.usageMetadata().promptTokenCount()),
                nullToZero(resp.usageMetadata().candidatesTokenCount()));

        // Blocked prompt: candidates is empty and only promptFeedback explains why.
        // This is a SUCCESS with finish_reason content_filter — never an exception,
        // never a failover (the fallback model would block it too).
        if (resp.candidates() == null || resp.candidates().isEmpty()) {
            return new ChatResponse("", null, null, "content_filter", usage, name, model, latencyMs);
        }

        GeminiApi.Candidate candidate = resp.candidates().getFirst();
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = null;
        if (candidate.content() != null && candidate.content().parts() != null) {
            for (GeminiApi.Part part : candidate.content().parts()) {
                if (part.text() != null) {
                    // Thought parts are the reasoning trace, not the answer.
                    (part.isThought() ? reasoning : text).append(part.text());
                }
                if (part.functionCall() != null) {
                    if (toolCalls == null) toolCalls = new ArrayList<>();
                    // Gemini assigns no call id — generate one for OpenAI-shaped clients.
                    toolCalls.add(new ToolCall(
                            ToolCall.generatedId(),
                            part.functionCall().name(),
                            part.functionCall().args() == null ? "{}" : part.functionCall().args().toString()));
                }
            }
        }

        return new ChatResponse(
                text.toString(),
                reasoning.isEmpty() ? null : reasoning.toString(),
                toolCalls,
                normalizeFinishReason(candidate.finishReason(), toolCalls != null),
                usage, name, model, latencyMs);
    }

    /** Map Gemini's finishReason vocabulary onto OpenAI's. */
    private static String normalizeFinishReason(String finishReason, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_calls";
        if (finishReason == null) return "stop";
        return switch (finishReason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY" ->
                    "content_filter";
            default -> finishReason.toLowerCase();
        };
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}