package com.shreyasnandurkar.botvinnikapi.core.model;

/**
 * This is one normalized streaming chunk, and all provider-specific chunk shapes are flattened into this.
 *
 * @param contentDelta  token fragment, or null
 * @param toolCallDelta partial tool call, or null
 * @param finishReason  set on the final chunk only
 * @param usage         set on the final chunk only (where the provider reports it)
 */
public record ChatChunk(
        String contentDelta,
        ToolCallDelta toolCallDelta,
        String finishReason,
        TokenUsage usage) {

    public static ChatChunk content(String delta) {
        return new ChatChunk(delta, null, null, null);
    }
}