package com.shreyasnandurkar.botvinnikapi.control.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.core.ProviderFactory;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ControlDtos {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private ControlDtos() {
    }

    public record RegisterProviderRequest(
            String name,
            String type,
            @JsonProperty("base_url") String baseUrl,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("stream_idle_timeout_ms") Long streamIdleTimeoutMs,
            String pool) {

        public void validate() {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new InvalidRequestException(
                        "'name' is required and may only contain letters, digits, '.', '_' and '-'.", "name");
            }
            if (type == null || !ProviderFactory.SUPPORTED_TYPES.contains(type)) {
                throw new InvalidRequestException(
                        "'type' must be one of " + String.join(", ", ProviderFactory.SUPPORTED_TYPES) + ".", "type");
            }
            if (type.equals("ollama") && (baseUrl == null || baseUrl.isBlank())) {
                throw new InvalidRequestException("'base_url' is required for type ollama.", "base_url");
            }
            if (type.equals("gemini") && (apiKey == null || apiKey.isBlank())) {
                throw new InvalidRequestException("'api_key' is required for type gemini.", "api_key");
            }
            if (streamIdleTimeoutMs != null && streamIdleTimeoutMs <= 0) {
                throw new InvalidRequestException(
                        "'stream_idle_timeout_ms' must be positive.", "stream_idle_timeout_ms");
            }
        }
    }

    /** pool: null leaves membership untouched; "" clears it. */
    public record UpdateProviderRequest(
            @JsonProperty("base_url") String baseUrl,
            @JsonProperty("api_key") String apiKey,
            String status,
            @JsonProperty("stream_idle_timeout_ms") Long streamIdleTimeoutMs,
            String pool) {

        public void validate() {
            if (status != null && !status.equals("active") && !status.equals("disabled")) {
                throw new InvalidRequestException("'status' must be 'active' or 'disabled'.", "status");
            }
            if (streamIdleTimeoutMs != null && streamIdleTimeoutMs <= 0) {
                throw new InvalidRequestException(
                        "'stream_idle_timeout_ms' must be positive.", "stream_idle_timeout_ms");
            }
        }
    }

    /** api_key is write-only: it goes upstream, never back to clients. */
    public record ProviderResponse(
            UUID id,
            String name,
            String type,
            @JsonProperty("base_url") String baseUrl,
            String status,
            @JsonProperty("pool_id") UUID poolId,
            @JsonProperty("created_at") Instant createdAt) {

        public static ProviderResponse from(ProviderEntity e) {
            return new ProviderResponse(e.id(), e.name(), e.type(), e.baseUrl(), e.status(),
                    e.poolId(), e.createdAt());
        }
    }

    public record CreatePoolRequest(String name, String strategy) {

        private static final List<String> STRATEGIES = List.of("p2c", "least_conn", "round_robin");

        public void validate() {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new InvalidRequestException(
                        "'name' is required and may only contain letters, digits, '.', '_' and '-'.", "name");
            }
            if (strategy != null && !STRATEGIES.contains(strategy)) {
                throw new InvalidRequestException(
                        "'strategy' must be one of " + String.join(", ", STRATEGIES) + ".", "strategy");
            }
        }

        public String strategyOrDefault() {
            return strategy == null ? "p2c" : strategy;
        }
    }

    public record PoolResponse(
            UUID id,
            String name,
            String strategy,
            List<String> members) {
    }

    public record CreateAliasRequest(
            String alias,
            String primary,
            List<String> fallbacks) {

        public void validate() {
            if (alias == null || !NAME.matcher(alias).matches()) {
                throw new InvalidRequestException(
                        "'alias' is required and may only contain letters, digits, '.', '_' and '-'.", "alias");
            }
            requireProviderSlashModel(primary, "primary");
            if (fallbacks != null) {
                fallbacks.forEach(f -> requireProviderSlashModel(f, "fallbacks"));
            }
        }
    }

    public record UpdateAliasRequest(
            String primary,
            List<String> fallbacks) {

        public void validate() {
            if (primary != null) {
                requireProviderSlashModel(primary, "primary");
            }
            if (fallbacks != null) {
                fallbacks.forEach(f -> requireProviderSlashModel(f, "fallbacks"));
            }
        }
    }

    public record AliasResponse(
            UUID id,
            String alias,
            String primary,
            List<String> fallbacks) {

        public static AliasResponse from(AliasEntity e, String providerName, List<String> fallbacks) {
            return new AliasResponse(e.id(), e.alias(), providerName + "/" + e.targetModel(), fallbacks);
        }
    }

    public record CreateApiKeyRequest(
            String name,
            @JsonProperty("rate_limit_rpm") Integer rateLimitRpm,
            @JsonProperty("spend_cap_usd") BigDecimal spendCapUsd,
            @JsonProperty("log_content") Boolean logContent,
            @JsonProperty("max_tokens_cap") Integer maxTokensCap) {

        public void validate() {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new InvalidRequestException(
                        "'name' is required and may only contain letters, digits, '.', '_' and '-'.", "name");
            }
            if (rateLimitRpm != null && rateLimitRpm <= 0) {
                throw new InvalidRequestException("'rate_limit_rpm' must be positive.", "rate_limit_rpm");
            }
            if (spendCapUsd != null && spendCapUsd.signum() <= 0) {
                throw new InvalidRequestException("'spend_cap_usd' must be positive.", "spend_cap_usd");
            }
            if (maxTokensCap != null && maxTokensCap <= 0) {
                throw new InvalidRequestException("'max_tokens_cap' must be positive.", "max_tokens_cap");
            }
        }
    }

    /** The only response that ever carries the raw key — it is not stored (§11). */
    public record ApiKeyCreatedResponse(
            UUID id,
            String key,
            String name,
            @JsonProperty("rate_limit_rpm") Integer rateLimitRpm,
            @JsonProperty("spend_cap_usd") BigDecimal spendCapUsd,
            @JsonProperty("log_content") boolean logContent,
            @JsonProperty("max_tokens_cap") Integer maxTokensCap) {
    }

    public record ApiKeyResponse(
            UUID id,
            String name,
            @JsonProperty("rate_limit_rpm") Integer rateLimitRpm,
            @JsonProperty("spend_cap_usd") BigDecimal spendCapUsd,
            @JsonProperty("log_content") boolean logContent,
            @JsonProperty("max_tokens_cap") Integer maxTokensCap,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("last_used_at") Instant lastUsedAt) {

        public static ApiKeyResponse from(ApiKeyEntity e) {
            return new ApiKeyResponse(e.id(), e.name(), e.rateLimitRpm(), e.spendCapUsd(),
                    e.logContent(), e.maxTokensCap(), e.createdAt(), e.lastUsedAt());
        }
    }

    static void requireProviderSlashModel(String value, String param) {
        if (value == null || value.indexOf('/') <= 0 || value.indexOf('/') == value.length() - 1) {
            throw new InvalidRequestException(
                    "'" + param + "' must be of the form \"provider/model\".", param);
        }
    }
}
