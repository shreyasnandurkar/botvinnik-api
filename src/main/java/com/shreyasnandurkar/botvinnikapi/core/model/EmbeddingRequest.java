package com.shreyasnandurkar.botvinnikapi.core.model;

import java.util.List;

/**
 * The universal, provider-agnostic embedding request.
 *
 * @param input      texts to embed, one vector each, in order
 * @param dimensions requested vector size where the model supports shortening, or null
 */
public record EmbeddingRequest(String model, List<String> input, Integer dimensions) {
}
