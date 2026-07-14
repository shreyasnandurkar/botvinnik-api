package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.core.error.UnknownModelException;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Holds every configured provider instance and resolves a client-facing model
 * name to (provider, model).
 */
public class ProviderRegistry {

    private final SequencedMap<String, LLMProvider> providers;

    public ProviderRegistry(SequencedMap<String, LLMProvider> providers) {
        this.providers = providers;
    }

    public List<LLMProvider> all() {
        return List.copyOf(providers.values());
    }

    /**
     * Resolution rules:
     * <ul>
     *   <li>"provider/model" — explicit provider prefix wins;</li>
     *   <li>bare "model" — routed to the first configured provider.</li>
     * </ul>
     */
    public Resolution resolve(String model) {
        int slash = model.indexOf('/');
        if (slash > 0) {
            String providerName = model.substring(0, slash);
            LLMProvider provider = providers.get(providerName);
            if (provider == null) {
                throw new UnknownModelException(model);
            }
            return new Resolution(provider, model.substring(slash + 1));
        }
        if (providers.isEmpty()) {
            throw new UnknownModelException(model);
        }
        return new Resolution(providers.firstEntry().getValue(), model);
    }

    public record Resolution(LLMProvider provider, String model) {
    }
}