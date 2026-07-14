package com.shreyasnandurkar.botvinnikapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "botvinnik")
public record GatewayProperties(List<ProviderProps> providers) {

    public GatewayProperties {
        providers = providers == null ? List.of() : providers;
    }

    /**
     * @param name    operator-chosen instance name, e.g. "office-gpu"
     * @param type    adapter type, e.g. "ollama"
     * @param baseUrl provider endpoint, e.g. "http://localhost:11434"
     */
    public record ProviderProps(String name, String type, String baseUrl) {
    }
}