package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * The universal, provider-agnostic completion response.
 *
 * @param finishReason normalized to OpenAI vocabulary"
 */
public record ChatResponse(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        TokenUsage usage,
        String provider,
        String model,
        long latencyMs) {
}