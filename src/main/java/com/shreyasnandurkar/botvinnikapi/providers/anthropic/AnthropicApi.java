package com.shreyasnandurkar.botvinnikapi.providers.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Anthropic Messages wire format (/v1/messages, /v1/models). Package-private:
 * nothing outside this adapter may see an Anthropic-shaped object.
 */
final class AnthropicApi {

    private AnthropicApi() {
    }

    // ── request ─────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Msg> messages,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("stop_sequences") List<String> stopSequences,
            boolean stream,
            List<ToolDef> tools,
            Thinking thinking,
            @JsonProperty("output_config") OutputConfig outputConfig) {
    }

    /** Content is always a block list; roles are only "user" and "assistant". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Msg(String role, List<Block> content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolDef(String name, String description, @JsonProperty("input_schema") JsonNode inputSchema) {
    }

    /** display=summarized: newer models otherwise stream thinking blocks with empty text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Thinking(String type, String display) {
    }

    record OutputConfig(String effort) {
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /**
     * One record for every block type, discriminated by type: text, thinking,
     * tool_use (id, name, input) and tool_result (tool_use_id, content).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Block(
            String type,
            String text,
            String thinking,
            String id,
            String name,
            /* Tool arguments are a structured JSON object here, not a string. */
            JsonNode input,
            @JsonProperty("tool_use_id") String toolUseId,
            String content) {

        static Block text(String text) {
            return new Block("text", text, null, null, null, null, null, null);
        }

        static Block toolUse(String id, String name, JsonNode input) {
            return new Block("tool_use", null, null, id, name, input, null, null);
        }

        static Block toolResult(String toolUseId, String content) {
            return new Block("tool_result", null, null, null, null, null, toolUseId, content);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {
    }

    // ── response ────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagesResponse(
            String model,
            List<Block> content,
            @JsonProperty("stop_reason") String stopReason,
            Usage usage) {
    }

    /**
     * One SSE event; type names it (message_start, content_block_start/delta/stop,
     * message_delta, message_stop, ping, error). Only the fields for that type are set.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamEvent(
            String type,
            MessagesResponse message,
            Integer index,
            @JsonProperty("content_block") Block contentBlock,
            Delta delta,
            Usage usage,
            ErrorObj error) {
    }

    /** text_delta, thinking_delta, input_json_delta, or (on message_delta) the stop reason. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(
            String type,
            String text,
            String thinking,
            @JsonProperty("partial_json") String partialJson,
            @JsonProperty("stop_reason") String stopReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorObj(String type, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<ModelObj> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelObj(String id) {
    }
}
