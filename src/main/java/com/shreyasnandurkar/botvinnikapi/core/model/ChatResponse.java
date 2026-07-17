package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * The universal, provider-agnostic completion response.
 *
 * @param reasoningContent the model's thinking trace where the provider separates it
 *                         (Ollama's "thinking", Gemini's thought parts), or null.
 *                         Surfaced to clients as OpenAI-style reasoning_content.
 * @param finishReason     normalized to OpenAI vocabulary
 */
public record ChatResponse(
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        String finishReason,
        TokenUsage usage,
        String provider,
        String model,
        long latencyMs) {
}
