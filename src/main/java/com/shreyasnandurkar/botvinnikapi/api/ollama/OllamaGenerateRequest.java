package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inbound Ollama /api/generate request: one prompt, mapped to a one-turn chat. */
public class OllamaGenerateRequest {

    public String model;
    public String prompt;
    public String system;
    public String suffix;
    public String template;
    /** Token ids from a previous response, Ollama's stateful continuation. */
    public JsonNode context;
    public Boolean raw;
    public List<String> images;
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
        if (prompt == null || prompt.isEmpty()) {
            throw new InvalidRequestException("'prompt' is required.", "prompt");
        }
        if (context != null && !context.isNull()) {
            throw new InvalidRequestException(
                    "'context' is not supported: this gateway is stateless. "
                            + "Use /api/chat and send the whole conversation in 'messages'.", "context");
        }
        if (suffix != null && !suffix.isEmpty()) {
            throw new InvalidRequestException("'suffix' (fill-in-the-middle) is not supported.", "suffix");
        }
        if (template != null && !template.isEmpty()) {
            throw new InvalidRequestException("'template' is not supported.", "template");
        }
        if (Boolean.TRUE.equals(raw)) {
            throw new InvalidRequestException("'raw' prompts are not supported.", "raw");
        }
        if (images != null && !images.isEmpty()) {
            throw new InvalidRequestException("Image input is not supported (text only).", "images");
        }
        OllamaOptions.rejectFormat(format);
        OllamaOptions.validateThink(think);
    }

    public boolean streaming() {
        return stream == null || stream;
    }

    /** Cheap size proxy for §20's max-prompt limit. */
    public long promptChars() {
        return prompt.length() + (system == null ? 0 : system.length());
    }

    public ChatRequest toChatRequest(String resolvedModel) {
        List<Message> messages = new ArrayList<>(2);
        if (system != null && !system.isEmpty()) {
            messages.add(Message.text("system", system));
        }
        messages.add(Message.text("user", prompt));
        return new ChatRequest(resolvedModel, messages, OllamaOptions.temperature(options),
                OllamaOptions.topP(options), OllamaOptions.numPredict(options), OllamaOptions.stop(options),
                streaming(), OllamaOptions.reasoningEffort(think), null);
    }
}
