package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** encryptedApiKey is AES-GCM ciphertext; a null nonce marks a legacy plaintext row (§11). */
@Table("providers")
public record ProviderEntity(
        @Id UUID id,
        String name,
        String type,
        String baseUrl,
        String encryptedApiKey,
        String nonce,
        Long streamIdleTimeoutMs,
        UUID poolId,
        String status,
        Instant createdAt) {

    public boolean isActive() {
        return "active".equals(status);
    }
}
