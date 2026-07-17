package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * The universal, provider-agnostic completion request.
 * The API layer maps OpenAI wire JSON into this, each adapter maps it out
 * to its provider's native format.
 *
 * @param reasoningEffort OpenAI's reasoning_effort ("none"|"minimal"|"low"|"medium"|"high"),
 *                        or null. The gateway default is reasoning OFF — thinking models
 *                        silently bill thousands of hidden tokens otherwise.
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
        String reasoningEffort,
        List<Tool> tools) {

    /** Reasoning is opt-in: anything except absent/"none" turns it on. */
    public boolean wantsReasoning() {
        return reasoningEffort != null && !reasoningEffort.equals("none");
    }
}
