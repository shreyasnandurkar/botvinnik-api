package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingRequest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inbound Ollama /api/embed request: one string or a list of strings. */
public class OllamaEmbedRequest {

    public String model;
    public JsonNode input;
    /** Ollama truncates over-long input by default; backends apply their own policy. */
    public Boolean truncate;
    public Integer dimensions;
    public JsonNode options;
    @JsonProperty("keep_alive")
    public JsonNode keepAlive;

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
        if (dimensions != null && dimensions < 1) {
            throw new InvalidRequestException("'dimensions' must be at least 1.", "dimensions");
        }
        texts();
    }

    public long promptChars() {
        return texts().stream().mapToLong(String::length).sum();
    }

    public EmbeddingRequest toEmbeddingRequest(String resolvedModel) {
        return new EmbeddingRequest(resolvedModel, texts(), dimensions);
    }

    private List<String> texts() {
        if (input != null && input.isString() && !input.asString().isEmpty()) {
            return List.of(input.asString());
        }
        if (input == null || !input.isArray() || input.isEmpty()) {
            throw new InvalidRequestException(
                    "'input' must be a non-empty string or a non-empty array of strings.", "input");
        }
        List<String> texts = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            JsonNode item = input.get(i);
            if (!item.isString() || item.asString().isEmpty()) {
                throw new InvalidRequestException("input[" + i + "] must be a non-empty string.", "input");
            }
            texts.add(item.asString());
        }
        return texts;
    }
}
