package com.shreyasnandurkar.botvinnikapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "botvinnik")
public record GatewayProperties(List<ProviderProps> providers) {

    public GatewayProperties {
        providers = providers == null ? List.of() : providers;
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
}