package com.shreyasnandurkar.botvinnikapi.api.anthropic;

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
import java.util.Set;

/**
 * Inbound Anthropic Messages request. Content stays JSON until mapping because
 * every content field may be a string or a list of typed blocks.
 */
public class AnthropicMessagesRequest {

    /** Accepted and ignored: they change nothing about the answer. */
    private static final Set<String> IGNORED = Set.of("metadata", "service_tier", "cache_control");
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    public String model;
    @JsonProperty("max_tokens")
    public Integer maxTokens;
    public JsonNode system;
    public List<IncomingMessage> messages;
    public Double temperature;
    @JsonProperty("top_p")
    public Double topP;
    @JsonProperty("stop_sequences")
    public List<String> stopSequences;
    public Boolean stream;
    public List<JsonNode> tools;
    @JsonProperty("tool_choice")
    public JsonNode toolChoice;
    public JsonNode thinking;
    @JsonProperty("output_config")
    public JsonNode outputConfig;

    private final Map<String, JsonNode> unknown = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknown(String key, JsonNode value) {
        if (!IGNORED.contains(key)) {
            unknown.put(key, value);
        }
    }

    public static class IncomingMessage {
        public String role;
        public JsonNode content;
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
            throw new InvalidRequestException("model: Field required", "model");
        }
        if (maxTokens == null || maxTokens < 1) {
            throw new InvalidRequestException("max_tokens: Field required and must be at least 1", "max_tokens");
        }
        if (messages == null || messages.isEmpty()) {
            throw new InvalidRequestException("messages: must be a non-empty array", "messages");
        }
        for (int i = 0; i < messages.size(); i++) {
            String role = messages.get(i).role;
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new InvalidRequestException(
                        "messages." + i + ".role: must be 'user' or 'assistant'", "messages");
            }
        }
        if (toolChoice != null && !"auto".equals(toolChoice.path("type").asString(""))) {
            throw new InvalidRequestException("Only tool_choice {\"type\": \"auto\"} is supported.", "tool_choice");
        }
        if (toolChoice != null && toolChoice.path("disable_parallel_tool_use").asBoolean(false)) {
            throw new InvalidRequestException("disable_parallel_tool_use is not supported.", "tool_choice");
        }
        if (thinking != null && !Set.of("enabled", "adaptive", "disabled").contains(thinking.path("type").asString(""))) {
            throw new InvalidRequestException(
                    "thinking.type: must be one of enabled, adaptive, disabled", "thinking");
        }
        String effort = effort();
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new InvalidRequestException(
                    "output_config.effort: must be one of low, medium, high, xhigh, max", "output_config");
        }
        if (tools != null) {
            for (JsonNode tool : tools) {
                String type = tool.path("type").asString("custom");
                if (!"custom".equals(type)) {
                    throw new InvalidRequestException(
                            "Server tools (" + type + ") are not supported; only custom tools with input_schema.",
                            "tools");
                }
                if (tool.path("name").asString("").isBlank()) {
                    throw new InvalidRequestException("tools: every tool needs a name", "tools");
                }
            }
        }
        // Mapping walks every block, so unsupported block types fail here, before dispatch.
        toMessages();
    }

    /** Cheap size proxy for §20's max-prompt limit. */
    public long promptChars() {
        long total = textOf(system, "system").length();
        for (IncomingMessage m : messages) {
            if (m.content == null) continue;
            if (m.content.isString()) {
                total += m.content.asString().length();
            } else {
                for (JsonNode block : m.content) {
                    total += block.path("text").asString("").length();
                    JsonNode result = block.path("content");
                    total += result.isString() ? result.asString().length() : 0;
                }
            }
        }
        return total;
    }

    public ChatRequest toChatRequest(String resolvedModel) {
        List<Tool> mappedTools = tools == null || tools.isEmpty() ? null
                : tools.stream()
                .map(t -> new Tool(t.path("name").asString(),
                        t.hasNonNull("description") ? t.get("description").asString() : null,
                        t.get("input_schema")))
                .toList();
        return new ChatRequest(resolvedModel, toMessages(), temperature, topP, maxTokens,
                stopSequences == null || stopSequences.isEmpty() ? null : stopSequences,
                stream, reasoningEffort(), mappedTools);
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private List<Message> toMessages() {
        List<Message> out = new ArrayList<>();
        String systemText = textOf(system, "system");
        if (!systemText.isEmpty()) {
            out.add(Message.text("system", systemText));
        }
        for (int i = 0; i < messages.size(); i++) {
            IncomingMessage m = messages.get(i);
            String where = "messages." + i + ".content";
            if (m.content == null || m.content.isString()) {
                out.add(Message.text(m.role, m.content == null ? "" : m.content.asString()));
            } else if ("assistant".equals(m.role)) {
                out.add(toAssistant(m.content, where));
            } else {
                addUserTurn(m.content, where, out);
            }
        }
        return out;
    }

    private static Message toAssistant(JsonNode blocks, String where) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : blocks) {
            switch (block.path("type").asString("")) {
                case "text" -> text.append(block.path("text").asString(""));
                case "tool_use" -> toolCalls.add(new ToolCall(
                        block.path("id").asString(null),
                        block.path("name").asString(null),
                        block.has("input") ? block.get("input").toString() : "{}"));
                // Clients replay thinking blocks every turn; without signatures no backend can use them.
                case "thinking", "redacted_thinking" -> {
                }
                default -> throw unsupported(block, where);
            }
        }
        return new Message("assistant", text.isEmpty() ? null : text.toString(),
                toolCalls.isEmpty() ? null : toolCalls, null, null);
    }

    /** tool_result blocks become role "tool" messages, emitted before the turn's remaining text. */
    private static void addUserTurn(JsonNode blocks, String where, List<Message> out) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : blocks) {
            switch (block.path("type").asString("")) {
                case "text" -> {
                    if (!text.isEmpty()) text.append("\n\n");
                    text.append(block.path("text").asString(""));
                }
                case "tool_result" -> {
                    String result = textOf(block.get("content"), where);
                    if (block.path("is_error").asBoolean(false)) {
                        result = "Error: " + result;
                    }
                    out.add(new Message("tool", result, null, block.path("tool_use_id").asString(null), null));
                }
                default -> throw unsupported(block, where);
            }
        }
        if (!text.isEmpty()) {
            out.add(Message.text("user", text.toString()));
        }
    }

    /** A string, or a list of text blocks joined; anything else (images, documents) is rejected. */
    private static String textOf(JsonNode node, String where) {
        if (node == null || node.isNull()) return "";
        if (node.isString()) return node.asString();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : node) {
            if (!"text".equals(block.path("type").asString(""))) {
                throw unsupported(block, where);
            }
            if (!text.isEmpty()) text.append("\n\n");
            text.append(block.path("text").asString(""));
        }
        return text.toString();
    }

    private static InvalidRequestException unsupported(JsonNode block, String where) {
        return new InvalidRequestException(
                where + ": content block type '" + block.path("type").asString("") + "' is not supported.",
                "messages");
    }

    // ── reasoning ───────────────────────────────────────────────────────────

    private String effort() {
        if (outputConfig == null || !outputConfig.hasNonNull("effort")) return null;
        return outputConfig.get("effort").asString();
    }

    /** Gateway vocabulary tops out at "high"; reasoning stays off unless the client asks for it. */
    private String reasoningEffort() {
        String type = thinking == null ? null : thinking.path("type").asString(null);
        if ("disabled".equals(type)) return "none";
        String effort = effort();
        if (effort == null) return type == null ? null : "medium";
        return effort.equals("xhigh") || effort.equals("max") ? "high" : effort;
    }
}
