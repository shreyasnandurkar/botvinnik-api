package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.security.KeyCrypto;
import com.shreyasnandurkar.botvinnikapi.telemetry.SpendTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Seeds application.yaml providers into the DB (insert-if-absent, so operator
 * edits via the API survive restarts) and builds the first config snapshot.
 */
@Component
public class ConfigBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigBootstrap.class);

    private final GatewayProperties properties;
    private final ProviderRepository providerRepository;
    private final ConfigSnapshotService snapshots;
    private final KeyCrypto crypto;
    private final SpendTracker spendTracker;
    private final DatabaseClient db;

    public ConfigBootstrap(GatewayProperties properties, ProviderRepository providerRepository,
                           ConfigSnapshotService snapshots, KeyCrypto crypto,
                           SpendTracker spendTracker, DatabaseClient db) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.snapshots = snapshots;
        this.crypto = crypto;
        this.spendTracker = spendTracker;
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        encryptLegacyRows()
                .thenMany(Flux.fromIterable(properties.providers()).concatMap(this::seed))
                .then(seedSpend())
                .then(snapshots.rebuild())
                .block();
    }

    /** One-time upgrade: rows written before V2 hold plaintext keys (nonce is null). */
    private Mono<Void> encryptLegacyRows() {
        return providerRepository.findAll()
                .filter(p -> p.encryptedApiKey() != null && p.nonce() == null)
                .concatMap(p -> {
                    log.info("Encrypting stored key for provider '{}'", p.name());
                    KeyCrypto.Encrypted enc = crypto.encrypt(p.encryptedApiKey());
                    return providerRepository.save(new ProviderEntity(
                            p.id(), p.name(), p.type(), p.baseUrl(), enc.ciphertext(), enc.nonce(),
                            p.streamIdleTimeoutMs(), p.poolId(), p.status(), p.createdAt()));
                })
                .then();
    }

    private Mono<Void> seed(GatewayProperties.ProviderProps p) {
        return providerRepository.findByName(p.name())
                .hasElement()
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.empty();
                    }
                    log.info("Seeding provider '{}' ({}) from application.yaml", p.name(), p.type());
                    KeyCrypto.Encrypted enc = p.apiKey() == null ? null : crypto.encrypt(p.apiKey());
                    return providerRepository.save(new ProviderEntity(
                            null, p.name(), p.type(), p.baseUrl(),
                            enc == null ? null : enc.ciphertext(), enc == null ? null : enc.nonce(),
                            p.streamIdleTimeout() == null ? null : p.streamIdleTimeout().toMillis(),
                            null, "active", null)).then();
                });
    }

    /** Spend caps survive restarts by re-summing request_logs into the tracker. */
    private Mono<Void> seedSpend() {
        return db.sql("""
                        SELECT api_key_id, SUM(cost_usd) AS spent FROM request_logs
                        WHERE api_key_id IS NOT NULL AND cost_usd IS NOT NULL
                        GROUP BY api_key_id
                        """)
                .map((row, meta) -> {
                    spendTracker.seed(row.get("api_key_id", UUID.class), row.get("spent", BigDecimal.class));
                    return 1;
                })
                .all()
                .then();
    }
}
