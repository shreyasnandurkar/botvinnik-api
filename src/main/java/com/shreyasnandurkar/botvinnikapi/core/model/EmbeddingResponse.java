package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * The universal, provider-agnostic embedding response.
 *
 * @param embeddings one vector per input, in input order
 * @param usage      prompt tokens only, or null where the provider reports none (Gemini)
 */
public record EmbeddingResponse(List<float[]> embeddings, TokenUsage usage, String provider, String model) {
}
