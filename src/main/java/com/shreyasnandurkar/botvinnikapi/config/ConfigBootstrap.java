package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    public ConfigBootstrap(GatewayProperties properties, ProviderRepository providerRepository,
                           ConfigSnapshotService snapshots) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.snapshots = snapshots;
    }

    @Override
    public void run(ApplicationArguments args) {
        Flux.fromIterable(properties.providers())
                .concatMap(this::seed)
                .then(snapshots.rebuild())
                .block();
    }

    private Mono<Void> seed(GatewayProperties.ProviderProps p) {
        return providerRepository.findByName(p.name())
                .hasElement()
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.empty();
                    }
                    log.info("Seeding provider '{}' ({}) from application.yaml", p.name(), p.type());
                    return providerRepository.save(new ProviderEntity(
                            null, p.name(), p.type(), p.baseUrl(), p.apiKey(),
                            p.streamIdleTimeout() == null ? null : p.streamIdleTimeout().toMillis(),
                            null, "active", null)).then();
                });
    }
}
