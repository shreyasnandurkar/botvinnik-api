package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.error.NoAvailableProviderException;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
import com.shreyasnandurkar.botvinnikapi.core.error.StreamIdleTimeoutException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Dispatch with failover (§9/§10): walk the route plan's attempts at connect
 * time; once the first stream chunk is out, the point of no return has passed
 * and errors stay in-band.
 */
@Component
public class RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);

    private final PoolBalancer balancer;
    private final ProviderStatsRegistry stats;
    private final CircuitBreakers breakers;

    public RequestRouter(PoolBalancer balancer, ProviderStatsRegistry stats, CircuitBreakers breakers) {
        this.balancer = balancer;
        this.stats = stats;
        this.breakers = breakers;
    }

    public Mono<ChatResponse> chat(ProviderRegistry.RoutePlan plan, Function<String, ChatRequest> requestFor) {
        return chatAttempt(plan, 0, requestFor)
                .onErrorMap(CallNotPermittedException.class,
                        e -> new NoAvailableProviderException(plan.requestedModel(), e));
    }

    public Flux<ChatChunk> stream(ProviderRegistry.RoutePlan plan, Function<String, ChatRequest> requestFor) {
        return streamAttempt(plan, 0, requestFor)
                .onErrorMap(CallNotPermittedException.class,
                        e -> new NoAvailableProviderException(plan.requestedModel(), e));
    }

    private Mono<ChatResponse> chatAttempt(ProviderRegistry.RoutePlan plan, int index,
                                           Function<String, ChatRequest> requestFor) {
        ProviderRegistry.Attempt attempt = plan.attempts().get(index);
        return Mono.defer(() -> {
            LLMProvider provider = balancer.pick(attempt);
            return instrumented(provider, provider.chat(requestFor.apply(attempt.model())));
        }).onErrorResume(e -> {
            if (index + 1 < plan.attempts().size() && isFailoverable(e)) {
                log.warn("Attempt {} for '{}' failed at connect ({}); failing over",
                        index, plan.requestedModel(), e.toString());
                return chatAttempt(plan, index + 1, requestFor);
            }
            return Mono.error(exhausted(plan, e));
        });
    }

    private Flux<ChatChunk> streamAttempt(ProviderRegistry.RoutePlan plan, int index,
                                          Function<String, ChatRequest> requestFor) {
        ProviderRegistry.Attempt attempt = plan.attempts().get(index);
        AtomicBoolean emitted = new AtomicBoolean();
        return Flux.defer(() -> {
            LLMProvider provider = balancer.pick(attempt);
            return instrumented(provider, provider.stream(requestFor.apply(attempt.model())));
        }).doOnNext(chunk -> emitted.set(true))
                .onErrorResume(e -> {
                    // §10: failover only while nothing has been flushed to the client.
                    if (!emitted.get() && index + 1 < plan.attempts().size() && isFailoverable(e)) {
                        log.warn("Stream attempt {} for '{}' failed before first chunk ({}); failing over",
                                index, plan.requestedModel(), e.toString());
                        return streamAttempt(plan, index + 1, requestFor);
                    }
                    return Flux.error(emitted.get() ? e : exhausted(plan, e));
                });
    }

    /**
     * §10: a failover chain that dies at connect ends in 503, not the last 502 —
     * "we have nowhere to send this" differs from "the provider answered badly".
     * A single-provider plan keeps its original error.
     */
    private static Throwable exhausted(ProviderRegistry.RoutePlan plan, Throwable last) {
        if (plan.attempts().size() > 1 && isFailoverable(last)) {
            return new NoAvailableProviderException(plan.requestedModel(), last);
        }
        return last;
    }

    private Mono<ChatResponse> instrumented(LLMProvider provider, Mono<ChatResponse> call) {
        ProviderStatsRegistry.ProviderStats providerStats = stats.of(provider.name());
        return Mono.defer(() -> {
            long start = System.nanoTime();
            providerStats.started();
            return call.transformDeferred(CircuitBreakerOperator.of(breakers.of(provider.name())))
                    .doOnNext(r -> providerStats.recordTtft(millisSince(start)))
                    .doFinally(signal -> providerStats.finished());
        });
    }

    private Flux<ChatChunk> instrumented(LLMProvider provider, Flux<ChatChunk> call) {
        ProviderStatsRegistry.ProviderStats providerStats = stats.of(provider.name());
        return Flux.defer(() -> {
            long start = System.nanoTime();
            AtomicBoolean first = new AtomicBoolean(true);
            providerStats.started();
            return call.transformDeferred(CircuitBreakerOperator.of(breakers.of(provider.name())))
                    .doOnNext(chunk -> {
                        if (first.compareAndSet(true, false)) {
                            providerStats.recordTtft(millisSince(start));
                        }
                    })
                    .doFinally(signal -> providerStats.finished());
        });
    }

    /** Connect-class failures only — nothing on the wire yet, so failover is free (§10). */
    private static boolean isFailoverable(Throwable e) {
        if (e instanceof CallNotPermittedException || e instanceof ProviderUnreachableException
                || e instanceof StreamIdleTimeoutException) {
            return true;
        }
        return e instanceof UpstreamException upstream
                && (upstream.upstreamStatus() >= 500 || upstream.upstreamStatus() == 429);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
