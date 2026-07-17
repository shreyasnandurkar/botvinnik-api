package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.providers.gemini.GeminiProvider;
import com.shreyasnandurkar.botvinnikapi.providers.ollama.OllamaProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;

/**
 * Adding a provider type means adding a case here and an adapter class —
 * nothing downstream changes (§7).
 */
@Component
public class ProviderFactory {

    public static final Set<String> SUPPORTED_TYPES = Set.of("ollama", "gemini");

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public ProviderFactory(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    public LLMProvider create(String name, String type, String baseUrl, String apiKey,
                              Duration streamIdleTimeout) {
        return switch (type) {
            case "ollama" -> new OllamaProvider(name, baseUrl, webClientBuilder, objectMapper,
                    streamIdleTimeout);
            case "gemini" -> {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException("Provider '" + name + "' (gemini) requires an api-key");
                }
                yield new GeminiProvider(name, baseUrl, apiKey, webClientBuilder, objectMapper,
                        streamIdleTimeout);
            }
            default -> throw new IllegalStateException(
                    "Unknown provider type '" + type + "' for provider '" + name + "'");
        };
    }
}
