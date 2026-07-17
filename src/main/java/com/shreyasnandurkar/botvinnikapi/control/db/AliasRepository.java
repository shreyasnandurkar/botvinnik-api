package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AliasRepository extends ReactiveCrudRepository<AliasEntity, UUID> {

    Mono<AliasEntity> findByAlias(String alias);

    Flux<AliasEntity> findAllByTargetProviderId(UUID providerId);
}
