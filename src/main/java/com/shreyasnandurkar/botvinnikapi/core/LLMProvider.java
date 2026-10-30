package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.EmbeddingResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The provider abstraction. One implementation per wire format. Adding a provider type means adding an implementation.
 */
public interface LLMProvider {

    /** This provider instance's registered name (e.g. "office-gpu"). */
    String name();

    /** Non-streaming completion. */
    Mono<ChatResponse> chat(ChatRequest request);

    /** Streaming completion — normalized chunks. */
    Flux<ChatChunk> stream(ChatRequest request);

    /** Text embeddings; adapters for backends without an embeddings API keep this default. */
    default Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return Mono.error(new InvalidRequestException(
                "Provider '" + name() + "' does not support embeddings.", "model"));
    }

    /** Liveness + load signal. */
    Mono<HealthStatus> healthCheck();

    /** Models available on this provider. */
    Mono<List<ModelInfo>> listModels();
}