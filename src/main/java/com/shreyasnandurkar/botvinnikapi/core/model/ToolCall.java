package com.shreyasnandurkar.botvinnikapi.core.model;

/**
 * A completed tool invocation requested by the model.
 *
 * @param id            provider-assigned or gateway-generated call id
 * @param name          function name
 * @param argumentsJson the arguments as a JSON string (OpenAI convention — always a string,
 *                      never a structured object, regardless of what the provider emitted)
 */
public record ToolCall(String id, String name, String argumentsJson) {

    /** Ollama and Gemini assign no call id; OpenAI-shaped clients require one. */
    public static String generatedId() {
        return "call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
