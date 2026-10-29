package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.core.error.UnknownModelException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/**
 * One immutable snapshot of routing config: provider instances, the alias
 * table, and pool membership. The data plane resolves against this and never
 * touches the DB.
 */
public class ProviderRegistry {

    private final SequencedMap<String, LLMProvider> providers;
    private final Map<String, AliasRoute> aliases;
    private final Map<String, PoolDef> poolsByName;
    private final Map<String, String> poolByProvider;

    public ProviderRegistry(SequencedMap<String, LLMProvider> providers) {
        this(providers, Map.of(), Map.of(), Map.of());
    }

    public ProviderRegistry(SequencedMap<String, LLMProvider> providers, Map<String, AliasRoute> aliases) {
        this(providers, aliases, Map.of(), Map.of());
    }

    public ProviderRegistry(SequencedMap<String, LLMProvider> providers, Map<String, AliasRoute> aliases,
                            Map<String, PoolDef> poolsByName, Map<String, String> poolByProvider) {
        this.providers = providers;
        this.aliases = aliases;
        this.poolsByName = poolsByName;
        this.poolByProvider = poolByProvider;
    }

    public static ProviderRegistry empty() {
        return new ProviderRegistry(new LinkedHashMap<>());
    }

    /** @param fallbacks "provider/model" entries tried in order at connect time (§9) */
    public record AliasRoute(String providerName, String model, List<String> fallbacks) {
    }

    public record PoolDef(String name, String strategy, List<String> members) {
    }

    /** One rung of the failover ladder: the candidate set one balancer pick chooses from. */
    public record Attempt(String poolKey, List<LLMProvider> candidates, String model, String strategy) {
    }

    /** Models each provider serves; null means not yet discovered. */
    @FunctionalInterface
    public interface ModelIndex {
        ModelIndex NONE = providerName -> null;

        Set<String> modelsOf(String providerName);
    }

    public record RoutePlan(String requestedModel, List<Attempt> attempts) {

        public Attempt primary() {
            return attempts.getFirst();
        }
    }

    public List<LLMProvider> all() {
        return List.copyOf(providers.values());
    }

    public RoutePlan resolveRoute(String model) {
        return resolveRoute(model, ModelIndex.NONE);
    }

    /**
     * Resolution rules, in order:
     * <ul>
     *   <li>alias — primary plus its fallback chain, each widened to its pool;</li>
     *   <li>"provider/model" — pinned to exactly that instance, only if the prefix is a
     *       registered provider (model ids like "openai/gpt-oss-20b" contain slashes too);</li>
     *   <li>bare "model" — every provider whose discovered models include it; if none does
     *       and some provider is still undiscovered, the first configured provider.</li>
     * </ul>
     */
    public RoutePlan resolveRoute(String model, ModelIndex index) {
        AliasRoute route = aliases.get(model);
        if (route != null) {
            List<Attempt> attempts = new ArrayList<>();
            Attempt primary = widen(route.providerName(), route.model());
            if (primary != null) {
                attempts.add(primary);
            }
            for (String fallback : route.fallbacks()) {
                int slash = fallback.indexOf('/');
                Attempt next = widen(fallback.substring(0, slash), fallback.substring(slash + 1));
                if (next != null) {
                    attempts.add(next);
                }
            }
            if (attempts.isEmpty()) {
                throw new UnknownModelException(model);
            }
            return new RoutePlan(model, attempts);
        }
        int slash = model.indexOf('/');
        if (slash > 0) {
            String providerName = model.substring(0, slash);
            LLMProvider provider = providers.get(providerName);
            if (provider != null) {
                return new RoutePlan(model, List.of(new Attempt(
                        providerName, List.of(provider), model.substring(slash + 1), "least_conn")));
            }
        }
        if (providers.isEmpty()) {
            throw new UnknownModelException(model);
        }
        List<LLMProvider> serving = new ArrayList<>();
        boolean undiscovered = false;
        for (Map.Entry<String, LLMProvider> e : providers.entrySet()) {
            Set<String> models = index.modelsOf(e.getKey());
            if (models == null) {
                undiscovered = true;
            } else if (models.contains(model)) {
                serving.add(e.getValue());
            }
        }
        if (!serving.isEmpty()) {
            return new RoutePlan(model, List.of(new Attempt("model:" + model, serving, model, "least_conn")));
        }
        // Every provider answered and none has it: fail here rather than send it somewhere that will 404.
        if (!undiscovered) {
            throw new UnknownModelException(model);
        }
        Attempt attempt = widen(providers.firstEntry().getKey(), model);
        if (attempt == null) {
            throw new UnknownModelException(model);
        }
        return new RoutePlan(model, List.of(attempt));
    }

    private Attempt widen(String providerName, String model) {
        String poolName = poolByProvider.get(providerName);
        if (poolName != null) {
            PoolDef pool = poolsByName.get(poolName);
            if (pool != null) {
                List<LLMProvider> members = pool.members().stream()
                        .map(providers::get)
                        .filter(Objects::nonNull)
                        .toList();
                if (!members.isEmpty()) {
                    return new Attempt(poolName, members, model, pool.strategy());
                }
            }
        }
        LLMProvider provider = providers.get(providerName);
        return provider == null ? null : new Attempt(providerName, List.of(provider), model, "least_conn");
    }
}
