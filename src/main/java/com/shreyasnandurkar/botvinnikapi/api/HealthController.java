package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /v1/health — per-provider liveness. */
@RestController
public class HealthController {

    private final ConfigSnapshotService snapshots;

    public HealthController(ConfigSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/v1/health")
    public Mono<Map<String, HealthStatus>> health() {
        return Flux.fromIterable(snapshots.registry().all())
                .flatMap(provider -> provider.healthCheck()
                        .map(status -> Tuples.of(provider.name(), status)))
                .collectMap(t -> t.getT1(), t -> t.getT2(), LinkedHashMap::new);
    }
}