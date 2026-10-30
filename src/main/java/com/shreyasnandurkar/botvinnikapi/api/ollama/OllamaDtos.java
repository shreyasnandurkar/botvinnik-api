package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/** Outbound Ollama shapes: chat/generate lines, model tags, embeddings and errors. */
public final class OllamaDtos {

    private OllamaDtos() {
    }

    /**
     * One record for /api/chat (message set) and /api/generate (response and thinking set),
     * whether it is a whole response or one NDJSON stream line; done marks the last.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            String model,
            @JsonProperty("created_at") String createdAt,
            OutMessage message,
            String response,
            String thinking,
            boolean done,
            @JsonProperty("done_reason") String doneReason,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount,
            @JsonProperty("eval_count") Integer evalCount) {

        static Line chat(String model, OutMessage message) {
            return new Line(model, now(), message, null, null, false, null, null, null);
        }

        static Line generate(String model, String response, String thinking) {
            return new Line(model, now(), null, response, thinking, false, null, null, null);
        }

        /** Usage and done_reason ride on the final line only, as Ollama sends them. */
        Line finished(String finishReason, TokenUsage usage) {
            TokenUsage u = usage == null ? TokenUsage.ZERO : usage;
            return new Line(model, createdAt, message, response, thinking, true, OllamaDtos.doneReason(finishReason),
                    u.promptTokens(), u.completionTokens());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutMessage(String role, String content, String thinking,
                             @JsonProperty("tool_calls") List<OutToolCall> toolCalls) {

        static OutMessage of(String content, String thinking, List<OutToolCall> toolCalls) {
            return new OutMessage("assistant", content == null ? "" : content, thinking, toolCalls);
        }
    }

    public record OutToolCall(OutFunction function) {
    }

    /** Ollama's arguments are a JSON object, not a string. */
    public record OutFunction(String name, JsonNode arguments) {
    }

    public record ErrorBody(String error) {
    }

    public record TagList(List<Tag> models) {
    }

    /** Ollama clients read name or model; size and digest have no meaning for a remote backend. */
    public record Tag(String name, String model, @JsonProperty("modified_at") String modifiedAt,
                      long size, String digest, TagDetails details) {

        public static Tag of(String name) {
            return new Tag(name, name, now(), 0, "", new TagDetails("", "", "", ""));
        }
    }

    public record TagDetails(String format, String family,
                             @JsonProperty("parameter_size") String parameterSize,
                             @JsonProperty("quantization_level") String quantizationLevel) {
    }

    public record EmbedResponse(String model, List<float[]> embeddings,
                                @JsonProperty("prompt_eval_count") int promptEvalCount) {
    }

    public record Version(String version) {
    }

    // ── shared mapping ──────────────────────────────────────────────────────

    /** Ollama reports "stop" even when the model called tools. */
    static String doneReason(String finishReason) {
        return "length".equals(finishReason) ? "length" : "stop";
    }

    static OutToolCall toolCall(ObjectMapper mapper, String name, String argumentsJson) {
        return new OutToolCall(new OutFunction(name, arguments(mapper, argumentsJson)));
    }

    /** A backend's malformed arguments become an empty object rather than a broken response. */
    private static JsonNode arguments(ObjectMapper mapper, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String now() {
        return Instant.now().toString();
    }
}
