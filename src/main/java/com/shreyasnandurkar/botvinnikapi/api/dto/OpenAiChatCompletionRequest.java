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
 * Inbound OpenAI wire request.
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
    public JsonNode stop;
    public Boolean stream;
    /** Only {"include_usage": bool} is honored.*/
    @JsonProperty("stream_options")
    public JsonNode streamOptions;
    public List<IncomingTool> tools;
    /** Only "auto" (the default) is supported until routing can honor it. */
    @JsonProperty("tool_choice")
    public JsonNode toolChoice;
    /**
     * OpenAI's reasoning_effort. Gateway default is OFF ("none") — thinking models
     * otherwise bill thousands of hidden tokens per request. Any other value opts in,
     * and the trace comes back as reasoning_content.
     */
    @JsonProperty("reasoning_effort")
    public String reasoningEffort;
    public Integer n;
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
        if (reasoningEffort != null && !List.of("none", "minimal", "low", "medium", "high").contains(reasoningEffort)) {
            throw new InvalidRequestException(
                    "'reasoning_effort' must be one of none, minimal, low, medium, high.",
                    "reasoning_effort");
        }
        if (streamOptions != null && !streamOptions.isNull()) {
            if (!Boolean.TRUE.equals(stream)) {
                throw new InvalidRequestException(
                        "'stream_options' is only allowed with stream: true.", "stream_options");
            }
            if (!streamOptions.isObject()) {
                throw new InvalidRequestException("'stream_options' must be an object.", "stream_options");
            }
            for (var property : streamOptions.properties()) {
                if (!property.getKey().equals("include_usage")) {
                    throw new InvalidRequestException(
                            "Unknown stream_options field '" + property.getKey() + "'.", "stream_options");
                }
            }
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

    /** Cheap size proxy for §20's max-prompt limit — rejected at ingress, before dispatch. */
    public long promptChars() {
        long total = 0;
        for (IncomingMessage m : messages) {
            if (m.content == null) {
                continue;
            }
            if (m.content.isTextual()) {
                total += m.content.textValue().length();
            } else if (m.content.isArray()) {
                for (JsonNode part : m.content) {
                    total += part.path("text").asString("").length();
                }
            }
        }
        return total;
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
                effectiveMaxTokens, stopList(), stream, reasoningEffort, mappedTools);
    }

    /** stream_options.include_usage — OpenAI's opt-in for a final usage-only chunk. */
    public boolean includeStreamUsage() {
        if (streamOptions == null) {
            return false;
        }
        JsonNode flag = streamOptions.path("include_usage");
        return flag.isBoolean() && flag.booleanValue();
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