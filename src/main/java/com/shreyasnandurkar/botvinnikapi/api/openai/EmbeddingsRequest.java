package com.shreyasnandurkar.botvinnikapi.api.openai;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingRequest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inbound OpenAI embeddings request: one string or a list of strings. */
public class EmbeddingsRequest {

    /** OpenAI's own per-request limit. */
    private static final int MAX_INPUTS = 2048;

    public String model;
    public JsonNode input;
    /** "float" or "base64"; the OpenAI Python SDK sends base64 by default and decodes it itself. */
    @JsonProperty("encoding_format")
    public String encodingFormat;
    public Integer dimensions;
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
        if (encodingFormat != null && !encodingFormat.equals("float") && !encodingFormat.equals("base64")) {
            throw new InvalidRequestException("'encoding_format' must be float or base64.", "encoding_format");
        }
        if (dimensions != null && dimensions < 1) {
            throw new InvalidRequestException("'dimensions' must be at least 1.", "dimensions");
        }
        List<String> texts = texts();
        if (texts.size() > MAX_INPUTS) {
            throw new InvalidRequestException(
                    "'input' may hold at most " + MAX_INPUTS + " items.", "input");
        }
    }

    /** Cheap size proxy for §20's max-prompt limit. */
    public long promptChars() {
        return texts().stream().mapToLong(String::length).sum();
    }

    public EmbeddingRequest toEmbeddingRequest(String resolvedModel) {
        return new EmbeddingRequest(resolvedModel, texts(), dimensions);
    }

    public boolean base64() {
        return "base64".equals(encodingFormat);
    }

    /** Token-id arrays are rejected: ids are tokenizer-specific and mean nothing to another backend. */
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
            if (!item.isString()) {
                throw new InvalidRequestException(
                        "input[" + i + "] must be a string; token-array input is not supported.", "input");
            }
            if (item.asString().isEmpty()) {
                throw new InvalidRequestException("input[" + i + "] must not be empty.", "input");
            }
            texts.add(item.asString());
        }
        return texts;
    }
}
