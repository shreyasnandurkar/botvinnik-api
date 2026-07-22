package com.shreyasnandurkar.botvinnikapi.telemetry;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory running spend per API key, seeded from request_logs at boot so a
 * restart doesn't reset every cap. Enforcement reads this — never the DB (§4).
 */
@Component
public class SpendTracker {

    private final Map<UUID, AtomicReference<BigDecimal>> spentByKey = new ConcurrentHashMap<>();

    public void seed(UUID apiKeyId, BigDecimal spent) {
        spentByKey.put(apiKeyId, new AtomicReference<>(spent));
    }

    public void add(UUID apiKeyId, BigDecimal cost) {
        if (cost.signum() <= 0) {
            return;
        }
        spentByKey.computeIfAbsent(apiKeyId, id -> new AtomicReference<>(BigDecimal.ZERO))
                .accumulateAndGet(cost, BigDecimal::add);
    }

    public BigDecimal spent(UUID apiKeyId) {
        AtomicReference<BigDecimal> ref = spentByKey.get(apiKeyId);
        return ref == null ? BigDecimal.ZERO : ref.get();
    }
}
