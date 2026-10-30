package com.shreyasnandurkar.botvinnikapi.api.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** Outbound Anthropic Messages shapes: the full message, stream events and errors. */
public final class AnthropicDtos {

    private AnthropicDtos() {
    }

    static String messageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    public record MessageResponse(
            String id,
            String type,
            String role,
            String model,
            List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            Usage usage) {
    }

    /** One record for text, thinking and tool_use blocks, discriminated by type. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlock(String type, String text, String thinking, String signature,
                               String id, String name, JsonNode input) {

        static ContentBlock text(String text) {
            return new ContentBlock("text", text, null, null, null, null, null);
        }

        /** No backend produces Anthropic signatures; an empty one keeps SDK parsers happy. */
        static ContentBlock thinking(String thinking) {
            return new ContentBlock("thinking", null, thinking, "", null, null, null);
        }

        static ContentBlock toolUse(String id, String name, JsonNode input) {
            return new ContentBlock("tool_use", null, null, null, id, name, input);
        }
    }

    public record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens) {

        static Usage of(TokenUsage usage) {
            return usage == null ? new Usage(0, 0) : new Usage(usage.promptTokens(), usage.completionTokens());
        }
    }

    // ── stream events ───────────────────────────────────────────────────────

    public record MessageStart(String type, MessageResponse message) {
    }

    public record ContentBlockStart(String type, int index,
                                    @JsonProperty("content_block") ContentBlock contentBlock) {
    }

    public record ContentBlockDelta(String type, int index, Delta delta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String type, String text, String thinking,
                        @JsonProperty("partial_json") String partialJson) {
    }

    public record ContentBlockStop(String type, int index) {
    }

    public record MessageDelta(String type, MessageDeltaBody delta, Usage usage) {
    }

    public record MessageDeltaBody(
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence) {
    }

    public record MessageStop(String type) {
    }

    // ── errors ──────────────────────────────────────────────────────────────

    public record ErrorBody(String type, ErrorDetail error) {
    }

    public record ErrorDetail(String type, String message) {
    }
}
