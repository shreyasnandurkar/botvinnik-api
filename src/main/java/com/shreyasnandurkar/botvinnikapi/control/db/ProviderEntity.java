package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("providers")
public record ProviderEntity(
        @Id UUID id,
        String name,
        String type,
        String baseUrl,
        String apiKey,
        Long streamIdleTimeoutMs,
        UUID poolId,
        String status,
        Instant createdAt) {

    public boolean isActive() {
        return "active".equals(status);
    }
}
