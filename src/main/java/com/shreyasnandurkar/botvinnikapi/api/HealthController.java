package com.shreyasnandurkar.botvinnikapi.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import com.shreyasnandurkar.botvinnikapi.core.routing.CircuitBreakers;
import com.shreyasnandurkar.botvinnikapi.core.routing.ProviderStatsRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /v1/health — per-provider status, TTFT, circuit state (§15). */
@RestController
public class HealthController {

    private final ConfigSnapshotService snapshots;
    private final ProviderStatsRegistry stats;
    private final CircuitBreakers breakers;

    public HealthController(ConfigSnapshotService snapshots, ProviderStatsRegistry stats,
                            CircuitBreakers breakers) {
        this.snapshots = snapshots;
        this.stats = stats;
        this.breakers = breakers;
    }

    @GetMapping("/v1/health")
    public Mono<Map<String, ProviderHealthView>> health() {
        return Flux.fromIterable(snapshots.registry().all())
                .flatMap(provider -> provider.healthCheck()
                        .map(liveness -> {
                            boolean reachable = liveness.state() != HealthStatus.State.OPEN;
                            return Tuples.of(provider.name(), view(provider.name(), reachable,
                                    reachable ? liveness.latencyMs() : null));
                        }))
                .collectMap(t -> t.getT1(), t -> t.getT2(), LinkedHashMap::new);
    }

    private ProviderHealthView view(String name, boolean reachable, Long pingMs) {
        ProviderStatsRegistry.ProviderStats providerStats = stats.of(name);
        String circuit = breakers.of(name).getState().name();
        String status;
        if (breakers.isOpen(name)) {
            status = "OPEN";
        } else if (!reachable) {
            status = "DOWN";
        } else if (providerStats.isDegraded()) {
            status = "DEGRADED";
        } else {
            status = "HEALTHY";
        }
        double ttft = providerStats.ttftEwmaMs();
        double baseline = providerStats.ttftBaselineMs();
        return new ProviderHealthView(status, circuit, providerStats.inFlight(),
                ttft < 0 ? null : Math.round(ttft * 10) / 10.0,
                baseline < 0 ? null : Math.round(baseline * 10) / 10.0, pingMs);
    }

    /** baseline = the slow EWMA; the fast/baseline gap is the TTFT trend (§9). */
    public record ProviderHealthView(
            String status,
            String circuit,
            @JsonProperty("in_flight") int inFlight,
            @JsonProperty("ttft_ewma_ms") Double ttftEwmaMs,
            @JsonProperty("ttft_baseline_ms") Double ttftBaselineMs,
            @JsonProperty("ping_ms") Long pingMs) {
    }
}
