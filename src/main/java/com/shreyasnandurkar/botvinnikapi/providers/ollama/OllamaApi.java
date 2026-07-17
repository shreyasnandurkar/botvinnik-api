package com.shreyasnandurkar.botvinnikapi.providers.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Ollama's native wire format (/api/chat, /api/tags). Package-private on purpose:
 * nothing outside this adapter may ever see an Ollama-shaped object.
 */
final class OllamaApi {

    private OllamaApi() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatRequest(
            String model,
            List<Msg> messages,
            boolean stream,
            /* Always sent explicitly: thinking models default it to true and burn hidden tokens. */
            boolean think,
            Map<String, Object> options,
            List<ToolDef> tools) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Msg(
            String role,
            String content,
            /* Ollama separates the reasoning trace from content for thinking models. */
            String thinking,
            @JsonProperty("tool_calls") List<ToolCallObj> toolCalls) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolDef(String type, FunctionDef function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FunctionDef(String name, String description, JsonNode parameters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCallObj(FunctionCallObj function) {
    }

    /** Ollama emits {@code arguments} as a structured JSON object, not a string. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionCallObj(String name, JsonNode arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(
            String model,
            Msg message,
            boolean done,
            @JsonProperty("done_reason") String doneReason,
            @JsonProperty("eval_count") Integer evalCount,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TagsResponse(List<Tag> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tag(String name) {
    }
}
