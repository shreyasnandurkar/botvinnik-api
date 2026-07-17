package com.shreyasnandurkar.botvinnikapi.control.db;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("aliases")
public record AliasEntity(
        @Id UUID id,
        String alias,
        UUID targetProviderId,
        String targetModel,
        Json fallbackChain) {
}
