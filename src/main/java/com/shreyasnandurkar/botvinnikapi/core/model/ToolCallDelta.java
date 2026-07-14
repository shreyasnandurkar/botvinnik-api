package com.shreyasnandurkar.botvinnikapi.core.model;

/**
 * A fragment of a streamed tool call. OpenAI streams arguments as
 * incremental string fragments keyed by index; the accumulator reassembles them.
 *
 * @param index             which tool call this fragment belongs to
 * @param id                call id - present on the first fragment only
 * @param name              function name - present on the first fragment only
 * @param argumentsFragment partial JSON text; only valid once concatenated
 */
public record ToolCallDelta(int index, String id, String name, String argumentsFragment) {
}