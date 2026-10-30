package com.shreyasnandurkar.botvinnikapi.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Outbound OpenAI wire shapes. Records: the gateway never mutates a response. */
public final class OpenAiDtos {

    private OpenAiDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage) {

        public static ChatCompletionResponse from(ChatResponse resp) {
            List<OutgoingToolCall> toolCalls = resp.toolCalls() == null ? null
                    : resp.toolCalls().stream()
                    .map(tc -> new OutgoingToolCall(tc.id(), "function",
                            new OutgoingFunctionCall(tc.name(), tc.argumentsJson())))
                    .toList();
            OutgoingMessage message = new OutgoingMessage(
                    "assistant", resp.content(), resp.reasoningContent(), toolCalls);
            return new ChatCompletionResponse(
                    "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
                    "chat.completion",
                    Instant.now().getEpochSecond(),
                    resp.model(),
                    List.of(new Choice(0, message, resp.finishReason())),
                    resp.usage() == null ? null : new Usage(
                            resp.usage().promptTokens(),
                            resp.usage().completionTokens(),
                            resp.usage().totalTokens()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
            int index,
            OutgoingMessage message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    /** reasoning_content follows the DeepSeek/vLLM convention OpenAI clients already parse. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutgoingMessage(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("tool_calls") List<OutgoingToolCall> toolCalls) {
    }

    public record OutgoingToolCall(String id, String type, OutgoingFunctionCall function) {
    }

    public record OutgoingFunctionCall(String name, String arguments) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }

    // ── streaming wire shapes ───────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices,
            Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("tool_calls") List<DeltaToolCall> toolCalls) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeltaToolCall(int index, String id, String type, OutgoingFunctionCall function) {
    }

    public record ModelList(String object, List<ModelEntry> data) {

        public static ModelList from(List<ModelInfo> models) {
            long now = Instant.now().getEpochSecond();
            return new ModelList("list", models.stream()
                    .map(m -> new ModelEntry(m.id(), "model", now, m.provider()))
                    .toList());
        }
    }

    public record ModelEntry(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") String ownedBy) {
    }

    // ── embeddings ──────────────────────────────────────────────────────────

    public record EmbeddingList(String object, List<EmbeddingData> data, String model, EmbeddingUsage usage) {

        public static EmbeddingList from(EmbeddingResponse resp, boolean base64) {
            List<EmbeddingData> data = new ArrayList<>(resp.embeddings().size());
            for (int i = 0; i < resp.embeddings().size(); i++) {
                float[] vector = resp.embeddings().get(i);
                data.add(new EmbeddingData("embedding", i, base64 ? toBase64(vector) : vector));
            }
            int prompt = resp.usage() == null ? 0 : resp.usage().promptTokens();
            return new EmbeddingList("list", data, resp.model(), new EmbeddingUsage(prompt, prompt));
        }

        /** OpenAI's base64 form: the vector's float32 values, little-endian, then base64. */
        private static String toBase64(float[] vector) {
            ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asFloatBuffer().put(vector);
            return Base64.getEncoder().encodeToString(bytes.array());
        }
    }

    /** embedding is a float array, or a base64 string when the client asked for it. */
    public record EmbeddingData(String object, int index, Object embedding) {
    }

    public record EmbeddingUsage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }

    public record ErrorBody(ErrorDetail error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(String message, String type, String param, String code) {
    }
}