package com.shreyasnandurkar.botvinnikapi.providers.openai;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * OpenAI-compatible wire format (/chat/completions, /models), as spoken upstream.
 * Kept separate from api.dto: that is the client-facing side, this is the provider side.
 */
final class OpenAiApi {

    private OpenAiApi() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatRequest(
            String model,
            List<Msg> messages,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("max_tokens") Integer maxTokens,
            List<String> stop,
            boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions,
            @JsonProperty("reasoning_effort") String reasoningEffort,
            List<ToolDef> tools) {
    }

    /* Without include_usage, streaming responses carry no token counts at all. */
    record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Msg(
            String role,
            String content,
            /* Non-standard: vLLM and DeepSeek call it reasoning_content, Groq calls it reasoning. */
            @JsonProperty("reasoning_content") @JsonAlias("reasoning") String reasoningContent,
            @JsonProperty("tool_calls") List<ToolCallObj> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId,
            String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolDef(String type, FunctionDef function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FunctionDef(String name, String description, JsonNode parameters) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCallObj(Integer index, String id, String type, FunctionCallObj function) {
    }

    /** Arguments are a JSON string here, already the gateway's convention. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionCallObj(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(String model, List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            Integer index,
            Msg message,
            /* Streaming chunks carry delta instead of message. */
            Msg delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<ModelObj> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelObj(String id) {
    }

    // ── embeddings (/embeddings) ────────────────────────────────────────────

    /** encoding_format pinned to float: base64 is a client-side transport choice the gateway re-applies. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EmbeddingsRequest(
            String model,
            List<String> input,
            Integer dimensions,
            @JsonProperty("encoding_format") String encodingFormat) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingsResponse(String model, List<EmbeddingObj> data, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingObj(Integer index, float[] embedding) {
    }
}
