package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProviderRepository extends ReactiveCrudRepository<ProviderEntity, UUID> {

    Mono<ProviderEntity> findByName(String name);

    Flux<ProviderEntity> findAllByOrderByCreatedAt();
}
