package com.shreyasnandurkar.botvinnikapi.core.model;

/** Token accounting for one request. */
public record TokenUsage(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static final TokenUsage ZERO = new TokenUsage(0, 0);
}
