package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * Provider-agnostic chat message.
 *
 * @param role       "system" | "user" | "assistant" | "tool"
 * @param content    text content; may be null for assistant messages that only carry tool calls
 * @param toolCalls  tool calls issued by the assistant, if any
 * @param toolCallId for role "tool": the id of the call this message answers
 * @param name       optional participant / tool name
 */
public record Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name) {

    public static Message text(String role, String content) {
        return new Message(role, content, null, null, null);
    }
}
