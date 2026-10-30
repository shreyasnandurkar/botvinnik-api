package com.shreyasnandurkar.botvinnikapi.api.openai;

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
 * Inbound OpenAI Responses request, stateless only: the client sends the whole conversation
 * in {@code input} because the gateway stores no responses to continue from.
 */
public class ResponsesRequest {

    /** Accepted and ignored: they change nothing about the answer, or ask for storage we never do. */
    private static final Set<String> IGNORED = Set.of("metadata", "user", "store", "include", "truncation",
            "service_tier", "prompt_cache_key", "safety_identifier", "stream_options");
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh");

    public String model;
    public JsonNode input;
    public String instructions;
    @JsonProperty("max_output_tokens")
    public Integer maxOutputTokens;
    public Double temperature;
    @JsonProperty("top_p")
    public Double topP;
    public Boolean stream;
    public List<JsonNode> tools;
    @JsonProperty("tool_choice")
    public JsonNode toolChoice;
    @JsonProperty("parallel_tool_calls")
    public Boolean parallelToolCalls;
    public JsonNode reasoning;
    public JsonNode text;
    @JsonProperty("previous_response_id")
    public String previousResponseId;
    public JsonNode conversation;
    public Boolean background;

    private final Map<String, JsonNode> unknown = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknown(String key, JsonNode value) {
        if (!IGNORED.contains(key)) {
            unknown.put(key, value);
        }
    }

    /** Fail fast with a message naming the exact offending parameter. */
    public void validate() {
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException(
                    "Unknown parameter(s): " + String.join(", ", unknown.keySet())
                            + ". This gateway rejects parameters it cannot honor.",
                    unknown.keySet().iterator().next());
        }
        if (previousResponseId != null) {
            throw new InvalidRequestException(
                    "'previous_response_id' is not supported: this gateway is stateless and stores no responses. "
                            + "Send the full conversation in 'input' instead.", "previous_response_id");
        }
        if (conversation != null && !conversation.isNull()) {
            throw new InvalidRequestException(
                    "'conversation' is not supported: this gateway is stateless. "
                            + "Send the full conversation in 'input' instead.", "conversation");
        }
        if (Boolean.TRUE.equals(background)) {
            throw new InvalidRequestException("'background' mode is not supported.", "background");
        }
        if (model == null || model.isBlank()) {
            throw new InvalidRequestException("'model' is required.", "model");
        }
        if (input == null || input.isNull() || (input.isArray() && input.isEmpty())
                || (input.isString() && input.asString().isEmpty())) {
            throw new InvalidRequestException("'input' must be a non-empty string or array of items.", "input");
        }
        if (!input.isString() && !input.isArray()) {
            throw new InvalidRequestException("'input' must be a string or an array of items.", "input");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new InvalidRequestException("'max_output_tokens' must be at least 1.", "max_output_tokens");
        }
        if (toolChoice != null && !(toolChoice.isString() && toolChoice.asString().equals("auto"))) {
            throw new InvalidRequestException("Only tool_choice: \"auto\" is supported.", "tool_choice");
        }
        if (Boolean.FALSE.equals(parallelToolCalls)) {
            throw new InvalidRequestException("parallel_tool_calls: false is not supported.", "parallel_tool_calls");
        }
        String effort = effort();
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new InvalidRequestException(
                    "'reasoning.effort' must be one of none, minimal, low, medium, high, xhigh.", "reasoning");
        }
        String format = text == null ? null : text.path("format").path("type").asString(null);
        if (format != null && !format.equals("text")) {
            throw new InvalidRequestException(
                    "text.format type '" + format + "' is not supported; only plain text.", "text");
        }
        if (tools != null) {
            for (JsonNode tool : tools) {
                String type = tool.path("type").asString("");
                if (!"function".equals(type)) {
                    throw new InvalidRequestException(
                            "Tool type '" + type + "' is not supported; only function tools.", "tools");
                }
                if (tool.path("name").asString("").isBlank()) {
                    throw new InvalidRequestException("Every function tool needs a name.", "tools");
                }
            }
        }
        // Mapping walks every item, so unsupported item and part types fail here, before dispatch.
        toMessages();
    }

    /** Cheap size proxy for §20's max-prompt limit. */
    public long promptChars() {
        long total = instructions == null ? 0 : instructions.length();
        if (input.isString()) {
            return total + input.asString().length();
        }
        for (JsonNode item : input) {
            JsonNode content = item.path("content");
            if (content.isString()) {
                total += content.asString().length();
            } else {
                for (JsonNode part : content) {
                    total += part.path("text").asString("").length();
                }
            }
            JsonNode output = item.path("output");
            total += output.isString() ? output.asString().length() : 0;
            total += item.path("arguments").asString("").length();
        }
        return total;
    }

    public ChatRequest toChatRequest(String resolvedModel) {
        List<Tool> mappedTools = tools == null || tools.isEmpty() ? null
                : tools.stream()
                .map(t -> new Tool(t.path("name").asString(),
                        t.hasNonNull("description") ? t.get("description").asString() : null,
                        t.get("parameters")))
                .toList();
        return new ChatRequest(resolvedModel, toMessages(), temperature, topP, maxOutputTokens,
                null, stream, reasoningEffort(), mappedTools);
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private List<Message> toMessages() {
        List<Message> out = new ArrayList<>();
        if (instructions != null && !instructions.isEmpty()) {
            out.add(Message.text("system", instructions));
        }
        if (input.isString()) {
            out.add(Message.text("user", input.asString()));
            return out;
        }
        for (int i = 0; i < input.size(); i++) {
            JsonNode item = input.get(i);
            String where = "input[" + i + "]";
            // Easy-input messages ({role, content}) carry no type.
            String type = item.path("type").asString(item.has("role") ? "message" : "");
            switch (type) {
                case "message" -> out.add(toMessage(item, where));
                case "function_call" -> addFunctionCall(item, out);
                case "function_call_output" -> out.add(new Message("tool",
                        textOf(item.get("output"), where), null, item.path("call_id").asString(null), null));
                // Replayed reasoning is encrypted or provider-specific; no other backend can use it.
                case "reasoning" -> {
                }
                case "item_reference" -> throw new InvalidRequestException(
                        where + ": item_reference needs stored responses; this gateway is stateless. "
                                + "Send the referenced item itself.", "input");
                default -> throw new InvalidRequestException(
                        where + ": item type '" + type + "' is not supported.", "input");
            }
        }
        return out;
    }

    private static Message toMessage(JsonNode item, String where) {
        String role = item.path("role").asString("");
        String mapped = switch (role) {
            case "user", "assistant", "system" -> role;
            case "developer" -> "system";
            default -> throw new InvalidRequestException(
                    where + ".role must be one of user, assistant, system, developer.", "input");
        };
        return Message.text(mapped, textOf(item.get("content"), where));
    }

    /** Chat-style backends want one assistant turn carrying every call, so calls join the turn before them. */
    private static void addFunctionCall(JsonNode item, List<Message> out) {
        ToolCall call = new ToolCall(item.path("call_id").asString(null), item.path("name").asString(null),
                item.path("arguments").asString("{}"));
        Message last = out.isEmpty() ? null : out.getLast();
        if (last != null && "assistant".equals(last.role())) {
            List<ToolCall> calls = new ArrayList<>(last.toolCalls() == null ? List.of() : last.toolCalls());
            calls.add(call);
            out.set(out.size() - 1, new Message("assistant", last.content(), calls, null, null));
        } else {
            out.add(new Message("assistant", null, List.of(call), null, null));
        }
    }

    /** A string, or a list of text parts joined; images, files and audio are rejected. */
    private static String textOf(JsonNode node, String where) {
        if (node == null || node.isNull()) return "";
        if (node.isString()) return node.asString();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : node) {
            String type = part.path("type").asString("");
            String value = switch (type) {
                case "input_text", "output_text" -> part.path("text").asString("");
                case "refusal" -> part.path("refusal").asString("");
                default -> throw new InvalidRequestException(
                        where + ": content part type '" + type + "' is not supported (text only).", "input");
            };
            text.append(value);
        }
        return text.toString();
    }

    // ── reasoning ───────────────────────────────────────────────────────────

    private String effort() {
        if (reasoning == null || !reasoning.hasNonNull("effort")) return null;
        return reasoning.get("effort").asString();
    }

    /** Gateway vocabulary tops out at "high"; reasoning stays off unless the client asks for it. */
    private String reasoningEffort() {
        if (reasoning == null || reasoning.isNull()) return null;
        String effort = effort();
        if (effort == null) return "medium";
        return effort.equals("xhigh") ? "high" : effort;
    }
}
