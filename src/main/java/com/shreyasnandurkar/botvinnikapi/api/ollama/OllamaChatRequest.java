package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.Tool;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Inbound Ollama /api/chat request. Note Ollama's default is stream: true. */
public class OllamaChatRequest {

    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");

    public String model;
    public List<IncomingMessage> messages;
    public List<JsonNode> tools;
    public JsonNode format;
    public JsonNode options;
    public Boolean stream;
    @JsonProperty("keep_alive")
    public JsonNode keepAlive;
    public JsonNode think;

    private final Map<String, JsonNode> unknown = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknown(String key, JsonNode value) {
        unknown.put(key, value);
    }

    public static class IncomingMessage {
        public String role;
        public String content;
        /** A previous turn's reasoning; dropped, as no other backend can use it. */
        public String thinking;
        public List<String> images;
        @JsonProperty("tool_calls")
        public List<JsonNode> toolCalls;
        @JsonProperty("tool_name")
        public String toolName;
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
        for (int i = 0; i < messages.size(); i++) {
            IncomingMessage m = messages.get(i);
            if (!ROLES.contains(m.role)) {
                throw new InvalidRequestException(
                        "messages[" + i + "].role must be one of system, user, assistant, tool.", "messages");
            }
            if (m.images != null && !m.images.isEmpty()) {
                throw new InvalidRequestException(
                        "messages[" + i + "].images: image input is not supported (text only).", "messages");
            }
        }
        OllamaOptions.rejectFormat(format);
        OllamaOptions.validateThink(think);
        if (tools != null) {
            for (JsonNode tool : tools) {
                if (!"function".equals(tool.path("type").asString("function"))
                        || tool.path("function").path("name").asString("").isBlank()) {
                    throw new InvalidRequestException(
                            "Every tool must be {\"type\": \"function\", \"function\": {\"name\": ...}}.", "tools");
                }
            }
        }
    }

    public boolean streaming() {
        return stream == null || stream;
    }

    /** Cheap size proxy for §20's max-prompt limit. */
    public long promptChars() {
        return messages.stream().mapToLong(m -> m.content == null ? 0 : m.content.length()).sum();
    }

    public ChatRequest toChatRequest(String resolvedModel) {
        List<Tool> mappedTools = tools == null || tools.isEmpty() ? null
                : tools.stream()
                .map(t -> new Tool(t.path("function").path("name").asString(),
                        t.path("function").hasNonNull("description")
                                ? t.path("function").get("description").asString() : null,
                        t.path("function").get("parameters")))
                .toList();
        return new ChatRequest(resolvedModel, toMessages(), OllamaOptions.temperature(options),
                OllamaOptions.topP(options), OllamaOptions.numPredict(options), OllamaOptions.stop(options),
                streaming(), OllamaOptions.reasoningEffort(think), mappedTools);
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    /**
     * Ollama tool calls carry no ids, but OpenAI-style backends require tool results to cite one.
     * Calls get generated ids; each tool message claims the oldest unanswered call with its tool_name.
     */
    private List<Message> toMessages() {
        List<Message> out = new ArrayList<>(messages.size());
        List<ToolCall> unanswered = new ArrayList<>();
        for (IncomingMessage m : messages) {
            switch (m.role) {
                case "assistant" -> {
                    List<ToolCall> calls = null;
                    if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                        calls = m.toolCalls.stream()
                                .map(tc -> new ToolCall(ToolCall.generatedId(),
                                        tc.path("function").path("name").asString(null),
                                        argumentsJson(tc.path("function").get("arguments"))))
                                .toList();
                        unanswered.addAll(calls);
                    }
                    out.add(new Message("assistant", m.content, calls, null, null));
                }
                case "tool" -> out.add(new Message("tool", m.content == null ? "" : m.content, null,
                        claim(unanswered, m.toolName), m.toolName));
                default -> out.add(Message.text(m.role, m.content == null ? "" : m.content));
            }
        }
        return out;
    }

    private static String claim(List<ToolCall> unanswered, String toolName) {
        Iterator<ToolCall> it = unanswered.iterator();
        while (it.hasNext()) {
            ToolCall call = it.next();
            if (toolName == null || toolName.equals(call.name())) {
                it.remove();
                return call.id();
            }
        }
        return ToolCall.generatedId();
    }

    /** Ollama sends arguments as an object; the gateway's convention is a JSON string. */
    private static String argumentsJson(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) return "{}";
        return arguments.isString() ? arguments.asString() : arguments.toString();
    }
}
