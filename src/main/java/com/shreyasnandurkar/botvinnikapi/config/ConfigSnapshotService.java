package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.control.db.AliasEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.PoolEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.PoolRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderFactory;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.security.KeyCrypto;
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
    private final PoolRepository poolRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ProviderFactory factory;
    private final KeyCrypto crypto;
    private final ObjectMapper mapper;
    private final AtomicReference<ProviderRegistry> snapshot = new AtomicReference<>(ProviderRegistry.empty());
    private final AtomicReference<Map<String, ApiKeyEntity>> apiKeysByHash = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, UUID>> providerIdsByName = new AtomicReference<>(Map.of());

    public ConfigSnapshotService(ProviderRepository providerRepository, AliasRepository aliasRepository,
                                 PoolRepository poolRepository, ApiKeyRepository apiKeyRepository,
                                 ProviderFactory factory, KeyCrypto crypto, ObjectMapper mapper) {
        this.providerRepository = providerRepository;
        this.aliasRepository = aliasRepository;
        this.poolRepository = poolRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.factory = factory;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public ProviderRegistry registry() {
        return snapshot.get();
    }

    public ApiKeyEntity apiKeyByHash(String hash) {
        return apiKeysByHash.get().get(hash);
    }

    public UUID providerId(String name) {
        return providerIdsByName.get().get(name);
    }

    public Mono<Void> rebuild() {
        return Mono.zip(
                        providerRepository.findAllByOrderByCreatedAt().collectList(),
                        aliasRepository.findAll().collectList(),
                        poolRepository.findAll().collectList(),
                        apiKeyRepository.findAll().collectList())
                .doOnNext(t -> {
                    snapshot.set(build(t.getT1(), t.getT2(), t.getT3()));
                    Map<String, ApiKeyEntity> keys = new HashMap<>();
                    t.getT4().forEach(k -> keys.put(k.keyHash(), k));
                    apiKeysByHash.set(keys);
                    Map<String, UUID> ids = new HashMap<>();
                    t.getT1().forEach(p -> ids.put(p.name(), p.id()));
                    providerIdsByName.set(ids);
                })
                .then();
    }

    private ProviderRegistry build(List<ProviderEntity> providerRows, List<AliasEntity> aliasRows,
                                   List<PoolEntity> poolRows) {
        SequencedMap<String, LLMProvider> providers = new LinkedHashMap<>();
        Map<UUID, String> namesById = new HashMap<>();
        for (ProviderEntity row : providerRows) {
            namesById.put(row.id(), row.name());
            if (!row.isActive()) {
                continue;
            }
            try {
                providers.put(row.name(), factory.create(
                        row.name(), row.type(), row.baseUrl(), plaintextKey(row),
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
        Map<String, ProviderRegistry.PoolDef> poolsByName = new HashMap<>();
        Map<String, String> poolByProvider = new HashMap<>();
        for (PoolEntity pool : poolRows) {
            List<String> members = providerRows.stream()
                    .filter(p -> pool.id().equals(p.poolId()) && providers.containsKey(p.name()))
                    .map(ProviderEntity::name)
                    .toList();
            poolsByName.put(pool.name(), new ProviderRegistry.PoolDef(pool.name(), pool.strategy(), members));
            members.forEach(member -> poolByProvider.put(member, pool.name()));
        }
        log.info("Config snapshot rebuilt: {} active providers, {} aliases, {} pools",
                providers.size(), aliases.size(), poolsByName.size());
        return new ProviderRegistry(providers, aliases, poolsByName, poolByProvider);
    }

    /** null nonce = legacy plaintext row (pre-V2, or mid-upgrade at boot). */
    private String plaintextKey(ProviderEntity row) {
        if (row.encryptedApiKey() == null || row.nonce() == null) {
            return row.encryptedApiKey();
        }
        return crypto.decrypt(row.encryptedApiKey(), row.nonce());
    }

    private List<String> readFallbacks(AliasEntity row) {
        if (row.fallbackChain() == null) {
            return List.of();
        }
        return mapper.readValue(row.fallbackChain().asString(), new TypeReference<List<String>>() {
        });
    }
}
