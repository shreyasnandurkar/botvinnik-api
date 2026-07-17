package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PoolRepository extends ReactiveCrudRepository<PoolEntity, UUID> {

    Mono<PoolEntity> findByName(String name);
}
