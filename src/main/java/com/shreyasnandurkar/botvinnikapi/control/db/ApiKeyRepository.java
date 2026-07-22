package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyEntity, UUID> {

    Flux<ApiKeyEntity> findAllByOrderByCreatedAt();

    @Modifying
    @Query("UPDATE api_keys SET last_used_at = :ts WHERE id = :id")
    Mono<Integer> touch(UUID id, Instant ts);
}
