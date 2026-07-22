package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import com.shreyasnandurkar.botvinnikapi.core.error.NoAvailableProviderException;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
import com.shreyasnandurkar.botvinnikapi.core.error.StreamIdleTimeoutException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.Message;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.telemetry.RequestLogEntry;
import com.shreyasnandurkar.botvinnikapi.telemetry.RequestTelemetry;
import com.shreyasnandurkar.botvinnikapi.telemetry.TelemetryContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Dispatch with failover (§9/§10): walk the route plan's attempts at connect
 * time; once the first stream chunk is out, the point of no return has passed
 * and errors stay in-band.
 */
@Component
public class RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);
    private static final int EXCERPT_CHARS = 2_000;

    private final PoolBalancer balancer;
    private final ProviderStatsRegistry stats;
    private final CircuitBreakers breakers;
    private final RequestTelemetry telemetry;

    public RequestRouter(PoolBalancer balancer, ProviderStatsRegistry stats, CircuitBreakers breakers,
                         RequestTelemetry telemetry) {
        this.balancer = balancer;
        this.stats = stats;
        this.breakers = breakers;
        this.telemetry = telemetry;
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
            return instrumented(provider, requestFor.apply(attempt.model()));
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
            return instrumentedStream(provider, requestFor.apply(attempt.model()));
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

    private Mono<ChatResponse> instrumented(LLMProvider provider, ChatRequest request) {
        ProviderStatsRegistry.ProviderStats providerStats = stats.of(provider.name());
        return Mono.deferContextual(ctx -> {
            TelemetryContext tele = ctx.getOrDefault(TelemetryContext.class, TelemetryContext.NONE);
            long start = System.nanoTime();
            providerStats.started();
            AtomicLong ttft = new AtomicLong(-1);
            AtomicReference<ChatResponse> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            return provider.chat(request)
                    .transformDeferred(CircuitBreakerOperator.of(breakers.of(provider.name())))
                    .doOnNext(r -> {
                        long millis = millisSince(start);
                        providerStats.recordTtft(millis);
                        ttft.set(millis);
                        result.set(r);
                    })
                    .doOnError(failure::set)
                    .doFinally(signal -> {
                        providerStats.finished();
                        ChatResponse response = result.get();
                        telemetry.record(entry(tele, provider, request, signal,
                                millisSince(start), ttft.get(), failure.get(), response == null,
                                response == null ? null : response.usage(),
                                response == null ? null : response.content()));
                    });
        });
    }

    private Flux<ChatChunk> instrumentedStream(LLMProvider provider, ChatRequest request) {
        ProviderStatsRegistry.ProviderStats providerStats = stats.of(provider.name());
        return Flux.deferContextual(ctx -> {
            TelemetryContext tele = ctx.getOrDefault(TelemetryContext.class, TelemetryContext.NONE);
            long start = System.nanoTime();
            providerStats.started();
            AtomicLong ttft = new AtomicLong(-1);
            AtomicReference<TokenUsage> usage = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            StringBuilder content = tele.logContent() ? new StringBuilder() : null;
            return provider.stream(request)
                    .transformDeferred(CircuitBreakerOperator.of(breakers.of(provider.name())))
                    .doOnNext(chunk -> {
                        if (ttft.compareAndSet(-1, 0)) {
                            long millis = millisSince(start);
                            providerStats.recordTtft(millis);
                            ttft.set(millis);
                        }
                        if (chunk.usage() != null) {
                            usage.set(chunk.usage());
                        }
                        if (content != null && chunk.contentDelta() != null
                                && content.length() < EXCERPT_CHARS) {
                            content.append(chunk.contentDelta());
                        }
                    })
                    .doOnError(failure::set)
                    .doFinally(signal -> {
                        providerStats.finished();
                        telemetry.record(entry(tele, provider, request, signal,
                                millisSince(start), ttft.get(), failure.get(), ttft.get() < 0,
                                usage.get(), content == null ? null : content.toString()));
                    });
        });
    }

    private RequestLogEntry entry(TelemetryContext tele, LLMProvider provider, ChatRequest request,
                                  SignalType signal, long latencyMs, long ttftMs, Throwable error,
                                  boolean nothingReceived, TokenUsage usage, String responseContent) {
        String outcome = switch (signal) {
            case ON_COMPLETE -> "success";
            case CANCEL -> "cancelled";
            // A stream that dies after the first chunk is a partial failure (§10).
            case ON_ERROR -> nothingReceived ? "error" : "partial";
            default -> signal.toString();
        };
        String errorCode = error == null ? null
                : error instanceof GatewayException ge ? ge.code() : error.getClass().getSimpleName();
        return new RequestLogEntry(
                tele.apiKeyId(), provider.name(), request.model(), Instant.now(),
                latencyMs, ttftMs < 0 ? null : ttftMs,
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                outcome, errorCode,
                tele.logContent() ? excerpt(lastUserMessage(request)) : null,
                tele.logContent() ? excerpt(responseContent) : null);
    }

    private static String lastUserMessage(ChatRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if ("user".equals(m.role()) && m.content() != null) {
                return m.content();
            }
        }
        return null;
    }

    private static String excerpt(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= EXCERPT_CHARS ? text : text.substring(0, EXCERPT_CHARS);
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
