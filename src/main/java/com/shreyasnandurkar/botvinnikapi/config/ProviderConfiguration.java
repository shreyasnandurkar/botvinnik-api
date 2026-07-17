package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.providers.gemini.GeminiProvider;
import com.shreyasnandurkar.botvinnikapi.providers.ollama.OllamaProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * Instantiates one adapter per configured provider. Adding a provider type means
 * adding a case here and an adapter class — nothing downstream changes (§7).
 */
@Configuration
public class ProviderConfiguration {

    @Bean
    public ProviderRegistry providerRegistry(GatewayProperties properties,
                                             WebClient.Builder webClientBuilder,
                                             ObjectMapper objectMapper) {
        LinkedHashMap<String, LLMProvider> providers = new LinkedHashMap<>();
        for (GatewayProperties.ProviderProps p : properties.providers()) {
            LLMProvider provider = switch (p.type()) {
                case "ollama" -> new OllamaProvider(p.name(), p.baseUrl(), webClientBuilder, objectMapper);
                case "gemini" -> {
                    if (p.apiKey() == null || p.apiKey().isBlank()) {
                        throw new IllegalStateException(
                                "Provider '" + p.name() + "' (gemini) requires an api-key");
                    }
                    yield new GeminiProvider(p.name(), p.baseUrl(), p.apiKey(), webClientBuilder, objectMapper);
                }
                default -> throw new IllegalStateException(
                        "Unknown provider type '" + p.type() + "' for provider '" + p.name() + "'");
            };
            if (providers.putIfAbsent(p.name(), provider) != null) {
                throw new IllegalStateException("Duplicate provider name '" + p.name() + "'");
            }
        }
        return new ProviderRegistry(providers);
    }
}
