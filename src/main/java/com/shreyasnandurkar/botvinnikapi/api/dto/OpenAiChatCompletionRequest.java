package com.shreyasnandurkar.botvinnikapi.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.Tool;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound OpenAI wire request. A mutable class (not a record) so {@code @JsonAnySetter}
 * can capture parameters we did not declare — §5 stage 3: reject unknown params loudly
 * rather than silently dropping them.
 */
public class OpenAiChatCompletionRequest {

    public String model;
    public List<IncomingMessage> messages;
    public Double temperature;
    @JsonProperty("top_p")
    public Double topP;
    @JsonProperty("max_tokens")
    public Integer maxTokens;
    @JsonProperty("max_completion_tokens")
    public Integer maxCompletionTokens;
    /** String or array of strings — normalized in {@link #stopList()}. */
    public JsonNode stop;
    public Boolean stream;
    /** Accepted now, meaningful in streaming (build step 3). */
    @JsonProperty("stream_options")
    public JsonNode streamOptions;
    public List<IncomingTool> tools;
    /** Only "auto" (the default) is supported until routing can honor it. */
    @JsonProperty("tool_choice")
    public JsonNode toolChoice;
    /** Declared so we can reject n > 1 specifically instead of "unknown param". */
    public Integer n;
    /** OpenAI treats this as telemetry; accepting it costs nothing. */
    public String user;

    private final Map<String, JsonNode> unknown = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknown(String key, JsonNode value) {
        unknown.put(key, value);
    }

    /** Fail fast with a message naming the exact offending parameter. */
    public void validate() {
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException(
                    "Unknown parameter(s): " + String.join(", ", unknown.keySet())
                            + ". This gateway rejects parameters it cannot honor.",
                    unknown.keySet().iterator().next());
        }
        if (model == null || model.isBlank()) {
            throw new InvalidRequestException("'model' is required.", "model");
        }
        if (messages == null || messages.isEmpty()) {
            throw new InvalidRequestException("'messages' must be a non-empty array.", "messages");
        }
        if (n != null && n != 1) {
            throw new InvalidRequestException("'n' > 1 is not supported.", "n");
        }
        if (toolChoice != null && !(toolChoice.isTextual() && toolChoice.textValue().equals("auto"))) {
            throw new InvalidRequestException("Only tool_choice: \"auto\" is supported.", "tool_choice");
        }
        for (int i = 0; i < messages.size(); i++) {
            IncomingMessage m = messages.get(i);
            if (m.role == null || m.role.isBlank()) {
                throw new InvalidRequestException("messages[" + i + "].role is required.", "messages");
            }
            boolean hasToolCalls = m.toolCalls != null && !m.toolCalls.isEmpty();
            if (m.content == null && !hasToolCalls) {
                throw new InvalidRequestException(
                        "messages[" + i + "] must have content (or tool_calls for the assistant role).",
                        "messages");
            }
        }
    }

    /** Map into the universal ChatRequest, with the model name already resolved. */
    public ChatRequest toChatRequest(String resolvedModel) {
        List<Message> mapped = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            mapped.add(messages.get(i).toMessage(i));
        }
        List<Tool> mappedTools = tools == null ? null
                : tools.stream().map(IncomingTool::toTool).toList();
        Integer effectiveMaxTokens = maxTokens != null ? maxTokens : maxCompletionTokens;
        return new ChatRequest(resolvedModel, mapped, temperature, topP,
                effectiveMaxTokens, stopList(), stream, mappedTools);
    }

    private List<String> stopList() {
        if (stop == null || stop.isNull()) return null;
        if (stop.isTextual()) return List.of(stop.textValue());
        if (stop.isArray()) {
            List<String> out = new ArrayList<>();
            stop.forEach(node -> out.add(node.asString()));
            return out;
        }
        throw new InvalidRequestException("'stop' must be a string or an array of strings.", "stop");
    }

    // ── nested wire shapes ──────────────────────────────────────────────────

    public static class IncomingMessage {
        public String role;
        /** String, or OpenAI's multi-part array — text parts are joined, other parts rejected. */
        public JsonNode content;
        public String name;
        @JsonProperty("tool_call_id")
        public String toolCallId;
        @JsonProperty("tool_calls")
        public List<IncomingToolCall> toolCalls;

        Message toMessage(int index) {
            List<ToolCall> calls = toolCalls == null ? null
                    : toolCalls.stream()
                    .map(tc -> new ToolCall(tc.id, tc.function == null ? null : tc.function.name,
                            tc.function == null ? null : tc.function.arguments))
                    .toList();
            return new Message(role, normalizedContent(index), calls, toolCallId, name);
        }

        private String normalizedContent(int index) {
            if (content == null || content.isNull()) return null;
            if (content.isTextual()) return content.textValue();
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    String type = part.path("type").asString();
                    if (!"text".equals(type)) {
                        throw new InvalidRequestException(
                                "messages[" + index + "]: content part type '" + type
                                        + "' is not supported (text only).", "messages");
                    }
                    text.append(part.path("text").asString());
                }
                return text.toString();
            }
            throw new InvalidRequestException(
                    "messages[" + index + "].content must be a string or an array of content parts.",
                    "messages");
        }
    }

    public static class IncomingToolCall {
        public String id;
        public String type;
        public IncomingFunctionCall function;
    }

    public static class IncomingFunctionCall {
        public String name;
        /** OpenAI convention: arguments arrive as a JSON *string*. */
        public String arguments;
    }

    public static class IncomingTool {
        public String type;
        public IncomingFunction function;

        Tool toTool() {
            if (function == null || function.name == null) {
                throw new InvalidRequestException("Every tool needs a function with a name.", "tools");
            }
            return new Tool(function.name, function.description, function.parameters);
        }
    }

    public static class IncomingFunction {
        public String name;
        public String description;
        public JsonNode parameters;
    }
}
