package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.control.db.AliasEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderFactory;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The load-bearing rule (§4): the data plane makes zero synchronous DB calls.
 * It reads the AtomicReference; control-plane writes call rebuild().
 */
@Service
public class ConfigSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ConfigSnapshotService.class);

    private final ProviderRepository providerRepository;
    private final AliasRepository aliasRepository;
    private final ProviderFactory factory;
    private final ObjectMapper mapper;
    private final AtomicReference<ProviderRegistry> snapshot = new AtomicReference<>(ProviderRegistry.empty());

    public ConfigSnapshotService(ProviderRepository providerRepository, AliasRepository aliasRepository,
                                 ProviderFactory factory, ObjectMapper mapper) {
        this.providerRepository = providerRepository;
        this.aliasRepository = aliasRepository;
        this.factory = factory;
        this.mapper = mapper;
    }

    public ProviderRegistry registry() {
        return snapshot.get();
    }

    public Mono<Void> rebuild() {
        return providerRepository.findAllByOrderByCreatedAt().collectList()
                .zipWith(aliasRepository.findAll().collectList())
                .map(t -> build(t.getT1(), t.getT2()))
                .doOnNext(snapshot::set)
                .then();
    }

    private ProviderRegistry build(List<ProviderEntity> providerRows, List<AliasEntity> aliasRows) {
        SequencedMap<String, LLMProvider> providers = new LinkedHashMap<>();
        Map<UUID, String> namesById = new HashMap<>();
        for (ProviderEntity row : providerRows) {
            namesById.put(row.id(), row.name());
            if (!row.isActive()) {
                continue;
            }
            try {
                providers.put(row.name(), factory.create(
                        row.name(), row.type(), row.baseUrl(), row.apiKey(),
                        row.streamIdleTimeoutMs() == null ? null : Duration.ofMillis(row.streamIdleTimeoutMs())));
            } catch (Exception e) {
                // One broken row must not take down every other provider's routing.
                log.error("Skipping provider '{}' in config snapshot: {}", row.name(), e.getMessage());
            }
        }
        Map<String, ProviderRegistry.AliasRoute> aliases = new HashMap<>();
        for (AliasEntity row : aliasRows) {
            String providerName = namesById.get(row.targetProviderId());
            if (providerName == null) {
                continue;
            }
            aliases.put(row.alias(), new ProviderRegistry.AliasRoute(
                    providerName, row.targetModel(), readFallbacks(row)));
        }
        log.info("Config snapshot rebuilt: {} active providers, {} aliases", providers.size(), aliases.size());
        return new ProviderRegistry(providers, aliases);
    }

    private List<String> readFallbacks(AliasEntity row) {
        if (row.fallbackChain() == null) {
            return List.of();
        }
        return mapper.readValue(row.fallbackChain().asString(), new TypeReference<List<String>>() {
        });
    }
}
