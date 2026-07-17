package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.core.error.UnknownModelException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * One immutable snapshot of routing config: every provider instance plus the
 * alias table. The data plane resolves against this and never touches the DB.
 */
public class ProviderRegistry {

    private final SequencedMap<String, LLMProvider> providers;
    private final Map<String, AliasRoute> aliases;

    public ProviderRegistry(SequencedMap<String, LLMProvider> providers) {
        this(providers, Map.of());
    }

    public ProviderRegistry(SequencedMap<String, LLMProvider> providers, Map<String, AliasRoute> aliases) {
        this.providers = providers;
        this.aliases = aliases;
    }

    public static ProviderRegistry empty() {
        return new ProviderRegistry(new LinkedHashMap<>(), Map.of());
    }

    /** @param fallbacks "provider/model" entries, held for step 6 failover; unused in resolution today */
    public record AliasRoute(String providerName, String model, List<String> fallbacks) {
    }

    public List<LLMProvider> all() {
        return List.copyOf(providers.values());
    }

    /**
     * Resolution rules, in order:
     * <ul>
     *   <li>alias — the operator-defined routing table wins;</li>
     *   <li>"provider/model" — explicit provider prefix;</li>
     *   <li>bare "model" — routed to the first configured provider.</li>
     * </ul>
     */
    public Resolution resolve(String model) {
        AliasRoute route = aliases.get(model);
        if (route != null) {
            LLMProvider provider = providers.get(route.providerName());
            if (provider == null) {
                throw new UnknownModelException(model);
            }
            return new Resolution(provider, route.model());
        }
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
