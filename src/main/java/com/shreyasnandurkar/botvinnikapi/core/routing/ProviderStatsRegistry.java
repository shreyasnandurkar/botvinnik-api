package com.shreyasnandurkar.botvinnikapi.core.routing;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live per-provider signals for balancing (§9): in-flight depth and TTFT.
 * Keyed by provider name so stats survive config-snapshot rebuilds.
 */
@Component
public class ProviderStatsRegistry {

    private final ConcurrentHashMap<String, ProviderStats> stats = new ConcurrentHashMap<>();

    public ProviderStats of(String providerName) {
        return stats.computeIfAbsent(providerName, name -> new ProviderStats());
    }

    public static final class ProviderStats {

        private static final double FAST_ALPHA = 0.3;
        private static final double SLOW_ALPHA = 0.05;
        private static final double DEGRADED_FACTOR = 2.0;

        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile double fastTtftMs = -1;
        private volatile double slowTtftMs = -1;

        public void started() {
            inFlight.incrementAndGet();
        }

        public void finished() {
            inFlight.decrementAndGet();
        }

        public int inFlight() {
            return inFlight.get();
        }

        public synchronized void recordTtft(long millis) {
            fastTtftMs = fastTtftMs < 0 ? millis : FAST_ALPHA * millis + (1 - FAST_ALPHA) * fastTtftMs;
            slowTtftMs = slowTtftMs < 0 ? millis : SLOW_ALPHA * millis + (1 - SLOW_ALPHA) * slowTtftMs;
        }

        public double ttftEwmaMs() {
            return fastTtftMs;
        }

        public double ttftBaselineMs() {
            return slowTtftMs;
        }

        /** TTFT climbing well above its own baseline — queue is building (§9). */
        public boolean isDegraded() {
            return slowTtftMs > 0 && fastTtftMs > DEGRADED_FACTOR * slowTtftMs;
        }
    }
}
