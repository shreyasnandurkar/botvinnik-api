package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * §9: request count is not request cost — balancing keys off live in-flight
 * depth, with TTFT-degraded members deprioritized and circuit-open ones skipped.
 */
@Component
public class PoolBalancer {

    private final ProviderStatsRegistry stats;
    private final CircuitBreakers breakers;
    private final Random random;
    private final ConcurrentHashMap<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    @Autowired
    public PoolBalancer(ProviderStatsRegistry stats, CircuitBreakers breakers) {
        this(stats, breakers, new Random());
    }

    PoolBalancer(ProviderStatsRegistry stats, CircuitBreakers breakers, Random random) {
        this.stats = stats;
        this.breakers = breakers;
        this.random = random;
    }

    public LLMProvider pick(ProviderRegistry.Attempt attempt) {
        List<LLMProvider> candidates = attempt.candidates();
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        // OPEN members get no traffic (§9); if everything is open, hand one over
        // anyway so the breaker's half-open probe can happen and the failure is honest.
        List<LLMProvider> available = candidates.stream()
                .filter(p -> !breakers.isOpen(p.name()))
                .toList();
        if (available.isEmpty()) {
            available = candidates;
        }
        return switch (attempt.strategy()) {
            case "round_robin" -> roundRobin(attempt.poolKey(), available);
            case "least_conn" -> leastConnections(deprioritizeDegraded(available));
            default -> powerOfTwoChoices(deprioritizeDegraded(available));
        };
    }

    private List<LLMProvider> deprioritizeDegraded(List<LLMProvider> available) {
        List<LLMProvider> healthy = available.stream()
                .filter(p -> !stats.of(p.name()).isDegraded())
                .toList();
        return healthy.isEmpty() ? available : healthy;
    }

    private LLMProvider roundRobin(String poolKey, List<LLMProvider> available) {
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(poolKey, k -> new AtomicInteger());
        return available.get(Math.floorMod(counter.getAndIncrement(), available.size()));
    }

    private LLMProvider leastConnections(List<LLMProvider> available) {
        return available.stream()
                .min(Comparator.comparingInt(p -> stats.of(p.name()).inFlight()))
                .orElseThrow();
    }

    /** Two random samples, lower in-flight wins — near-optimal without herding (§9). */
    private LLMProvider powerOfTwoChoices(List<LLMProvider> available) {
        if (available.size() == 1) {
            return available.getFirst();
        }
        int first = random.nextInt(available.size());
        int second = random.nextInt(available.size() - 1);
        if (second >= first) {
            second++;
        }
        LLMProvider a = available.get(first);
        LLMProvider b = available.get(second);
        return stats.of(a.name()).inFlight() <= stats.of(b.name()).inFlight() ? a : b;
    }
}
