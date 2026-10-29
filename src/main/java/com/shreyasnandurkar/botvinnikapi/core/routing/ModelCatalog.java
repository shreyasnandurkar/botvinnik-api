package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Which models each provider serves, discovered in the background via listModels().
 * Bare-name routing reads it; requests never trigger discovery.
 */
@Component
public class ModelCatalog implements ProviderRegistry.ModelIndex {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalog.class);

    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(10);

    private final AtomicReference<Map<String, Set<String>>> modelsByProvider = new AtomicReference<>(Map.of());

    @Override
    public Set<String> modelsOf(String providerName) {
        return modelsByProvider.get().get(providerName);
    }

    public Mono<Void> refresh(List<LLMProvider> providers) {
        return Flux.fromIterable(providers)
                .flatMap(p -> p.listModels()
                        .timeout(LIST_TIMEOUT)
                        .map(models -> Map.entry(p.name(),
                                models.stream().map(ModelInfo::id).collect(Collectors.toUnmodifiableSet())))
                        .onErrorResume(e -> {
                            log.debug("Model discovery failed for provider '{}': {}", p.name(), e.getMessage());
                            return Mono.empty();
                        }))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doOnNext(fresh -> {
                    Map<String, Set<String>> previous = modelsByProvider.get();
                    Map<String, Set<String>> next = new HashMap<>();
                    for (LLMProvider p : providers) {
                        // A provider that is down keeps its last known list instead of vanishing.
                        Set<String> models = fresh.containsKey(p.name()) ? fresh.get(p.name()) : previous.get(p.name());
                        if (models != null) {
                            next.put(p.name(), models);
                        }
                    }
                    modelsByProvider.set(Map.copyOf(next));
                })
                .then();
    }
}
