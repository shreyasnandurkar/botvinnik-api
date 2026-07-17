package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
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

    private final ProviderRegistry registry;

    public HealthController(ProviderRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/v1/health")
    public Mono<Map<String, HealthStatus>> health() {
        return Flux.fromIterable(registry.all())
                .flatMap(provider -> provider.healthCheck()
                        .map(status -> Tuples.of(provider.name(), status)))
                .collectMap(t -> t.getT1(), t -> t.getT2(), LinkedHashMap::new);
    }
}