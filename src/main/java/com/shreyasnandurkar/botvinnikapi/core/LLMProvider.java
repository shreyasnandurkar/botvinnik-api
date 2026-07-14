package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
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

    /** Liveness + load signal. */
    Mono<HealthStatus> healthCheck();

    /** Models available on this provider. */
    Mono<List<ModelInfo>> listModels();
}