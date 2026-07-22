package com.shreyasnandurkar.botvinnikapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "botvinnik")
public record GatewayProperties(List<ProviderProps> providers, SecurityProps security,
                                LimitsProps limits, List<PriceProps> pricing) {

    public GatewayProperties {
        providers = providers == null ? List.of() : providers;
        security = security == null ? new SecurityProps(null, null, null) : security;
        limits = limits == null ? new LimitsProps(null) : limits;
        pricing = pricing == null ? List.of() : pricing;
    }

    /**
     * @param name              operator-chosen instance name, e.g. "office-gpu"
     * @param type              adapter type, e.g. "ollama" | "gemini"
     * @param baseUrl           provider endpoint; optional for cloud providers with a well-known default
     * @param apiKey            credential for cloud providers; unused for local ones
     * @param streamIdleTimeout max silence between stream chunks before the stream is
     *                          declared dead (§10); null uses the adapter default
     */
    public record ProviderProps(String name, String type, String baseUrl, String apiKey,
                                Duration streamIdleTimeout) {
    }

    /**
     * @param allowedCidrs SSRF exemptions (§11). This gateway's whole point is routing to
     *                     LAN GPUs, so loopback + RFC1918 default open; link-local /
     *                     metadata / CGNAT stay blocked unless explicitly listed.
     */
    public record SecurityProps(Boolean authEnabled, String encryptionKey, List<String> allowedCidrs) {

        public SecurityProps {
            authEnabled = authEnabled == null || authEnabled;
            allowedCidrs = allowedCidrs == null
                    ? List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
                    : allowedCidrs;
        }
    }

    public record LimitsProps(Integer maxPromptChars) {

        public LimitsProps {
            maxPromptChars = maxPromptChars == null ? 400_000 : maxPromptChars;
        }
    }

    /** Longest matching model prefix wins; unpriced models cost $0 (local inference). */
    public record PriceProps(String model, BigDecimal inputUsdPer1m, BigDecimal outputUsdPer1m) {
    }
}
