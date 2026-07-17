package com.shreyasnandurkar.botvinnikapi.core.model;

/**
 * This is one normalized streaming chunk, and all provider-specific chunk shapes are flattened into this.
 *
 * @param contentDelta   token fragment, or null
 * @param reasoningDelta thinking-trace fragment (surfaced as reasoning_content), or null
 * @param toolCallDelta  partial tool call, or null
 * @param finishReason   set on the final chunk only
 * @param usage          set on the final chunk only (where the provider reports it)
 */
public record ChatChunk(
        String contentDelta,
        String reasoningDelta,
        ToolCallDelta toolCallDelta,
        String finishReason,
        TokenUsage usage) {

    public static ChatChunk content(String delta) {
        return new ChatChunk(delta, null, null, null, null);
    }

    public static ChatChunk reasoning(String delta) {
        return new ChatChunk(null, delta, null, null, null);
    }
}
