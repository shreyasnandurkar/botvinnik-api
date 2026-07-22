package com.shreyasnandurkar.botvinnikapi.security;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyRepository;
import com.shreyasnandurkar.botvinnikapi.telemetry.SpendTracker;
import com.shreyasnandurkar.botvinnikapi.telemetry.TelemetryContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ingress stage ① (§5): SHA-256 the bearer, look it up in the config snapshot —
 * a cache miss is a 401, and the DB is never on this path.
 */
@Component
@Order(-100)
public class ApiKeyAuthFilter implements WebFilter {

    public static final String KEY_ATTR = "botvinnik.apiKey";
    private static final Set<String> DATA_PLANE = Set.of("/v1/chat/completions", "/v1/models");
    private static final long TOUCH_INTERVAL_MS = 60_000;

    private final GatewayProperties properties;
    private final ConfigSnapshotService snapshots;
    private final SpendTracker spendTracker;
    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper mapper;
    private final Map<UUID, Long> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyAuthFilter(GatewayProperties properties, ConfigSnapshotService snapshots,
                            SpendTracker spendTracker, ApiKeyRepository apiKeyRepository,
                            ObjectMapper mapper) {
        this.properties = properties;
        this.snapshots = snapshots;
        this.spendTracker = spendTracker;
        this.apiKeyRepository = apiKeyRepository;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.security().authEnabled()
                || !DATA_PLANE.contains(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "Missing API key. Pass it as 'Authorization: Bearer sk_live_...'.",
                    "invalid_request_error", "missing_api_key", null);
        }
        ApiKeyEntity key = snapshots.apiKeyByHash(Sha256.hex(header.substring("Bearer ".length()).trim()));
        if (key == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid API key.",
                    "invalid_request_error", "invalid_api_key", null);
        }
        if (key.spendCapUsd() != null && spendTracker.spent(key.id()).compareTo(key.spendCapUsd()) >= 0) {
            return reject(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    "Spend cap of $" + key.spendCapUsd().toPlainString() + " reached for this API key.",
                    "insufficient_quota", "spend_cap_exceeded", null);
        }
        touch(key.id());
        exchange.getAttributes().put(KEY_ATTR, key);
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TelemetryContext.class,
                        new TelemetryContext(key.id(), key.logContent())));
    }

    /** Fire-and-forget, at most once a minute per key — not a write per request. */
    private void touch(UUID keyId) {
        long now = System.currentTimeMillis();
        Long previous = lastTouched.get(keyId);
        if (previous != null && now - previous < TOUCH_INTERVAL_MS) {
            return;
        }
        lastTouched.put(keyId, now);
        apiKeyRepository.touch(keyId, Instant.now()).subscribe();
    }

    Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message,
                      String type, String code, String retryAfterSeconds) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (retryAfterSeconds != null) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        byte[] body = mapper.writeValueAsBytes(new OpenAiDtos.ErrorBody(
                new OpenAiDtos.ErrorDetail(message, type, null, code)));
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
