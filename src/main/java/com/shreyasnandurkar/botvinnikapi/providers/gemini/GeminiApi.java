package com.shreyasnandurkar.botvinnikapi.providers.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Gemini's wire format (generateContent + models). Package-private on purpose:
 * nothing outside this adapter may ever see a Google-shaped object.
 */
final class GeminiApi {

    private GeminiApi() {
    }

    // ── request ─────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GenerateContentRequest(
            List<Content> contents,
            Content systemInstruction,
            GenerationConfig generationConfig,
            List<ToolWrapper> tools) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(String role, List<Part> parts) {
    }

    /**
     * Exactly one payload field is set per part; Gemini multiplexes text, tool calls
     * and tool results this way. thought=true marks a text part as reasoning trace.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text, Boolean thought, FunctionCall functionCall, FunctionResponse functionResponse) {

        static Part text(String text) {
            return new Part(text, null, null, null);
        }

        static Part functionCall(FunctionCall call) {
            return new Part(null, null, call, null);
        }

        static Part functionResponse(FunctionResponse response) {
            return new Part(null, null, null, response);
        }

        boolean isThought() {
            return Boolean.TRUE.equals(thought);
        }
    }

    /** Gemini's args are a structured JSON object, not a string. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionCall(String name, JsonNode args) {
    }

    record FunctionResponse(String name, JsonNode response) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GenerationConfig(
            Double temperature,
            Double topP,
            Integer maxOutputTokens,
            List<String> stopSequences,
            ThinkingConfig thinkingConfig) {
    }

    /** includeThoughts=true asks Gemini to return its reasoning as thought parts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ThinkingConfig(Boolean includeThoughts) {
    }

    record ToolWrapper(List<FunctionDeclaration> functionDeclarations) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FunctionDeclaration(String name, String description, JsonNode parameters) {
    }

    // ── response ────────────────────────────────────────────────────────────

    /**
     * A blocked prompt arrives as candidates: [] with only promptFeedback set —
     * the empty-candidates case every naive normalizer crashes on.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateContentResponse(
            List<Candidate> candidates,
            PromptFeedback promptFeedback,
            UsageMetadata usageMetadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(String blockReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<ModelEntry> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(String name, List<String> supportedGenerationMethods) {
    }
}
