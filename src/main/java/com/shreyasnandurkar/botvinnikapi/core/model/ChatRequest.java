package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * The universal, provider-agnostic completion request.
 * The API layer maps OpenAI wire JSON into this, each adapter maps it out
 * to its provider's native format.
 */
public record
ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        Boolean stream,
        List<Tool> tools) {
}