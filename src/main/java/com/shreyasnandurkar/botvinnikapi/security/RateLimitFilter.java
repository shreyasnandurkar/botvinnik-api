package com.shreyasnandurkar.botvinnikapi.security;

import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ingress stage ② (§5): Bucket4j token bucket per key, exhausted before any
 * routing work — the cheapest possible rejection.
 */
@Component
@Order(-90)
public class RateLimitFilter implements WebFilter {

    private final ApiKeyAuthFilter auth;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(ApiKeyAuthFilter auth) {
        this.auth = auth;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ApiKeyEntity key = exchange.getAttribute(ApiKeyAuthFilter.KEY_ATTR);
        if (key == null || key.rateLimitRpm() == null) {
            return chain.filter(exchange);
        }
        Bucket bucket = buckets.computeIfAbsent(key.id(), id -> Bucket.builder()
                .addLimit(limit -> limit.capacity(key.rateLimitRpm())
                        .refillGreedy(key.rateLimitRpm(), Duration.ofMinutes(1)))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return chain.filter(exchange);
        }
        long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        return auth.reject(exchange, HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit of " + key.rateLimitRpm() + " requests/min exceeded for this API key.",
                "rate_limit_error", "rate_limit_exceeded", Long.toString(waitSeconds));
    }
}
