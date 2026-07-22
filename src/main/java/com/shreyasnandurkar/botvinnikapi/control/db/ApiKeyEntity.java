package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("api_keys")
public record ApiKeyEntity(
        @Id UUID id,
        String keyHash,
        String name,
        Integer rateLimitRpm,
        BigDecimal spendCapUsd,
        boolean logContent,
        Integer maxTokensCap,
        Instant createdAt,
        Instant lastUsedAt) {
}
