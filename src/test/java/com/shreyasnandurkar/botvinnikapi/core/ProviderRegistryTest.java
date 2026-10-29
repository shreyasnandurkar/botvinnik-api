package com.shreyasnandurkar.botvinnikapi.core;

import com.shreyasnandurkar.botvinnikapi.core.error.UnknownModelException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryTest {

    private static LLMProvider provider(String name) {
        return new LLMProvider() {
            public String name() { return name; }
            public Mono<ChatResponse> chat(ChatRequest request) { return Mono.empty(); }
            public Flux<ChatChunk> stream(ChatRequest request) { return Flux.empty(); }
            public Mono<HealthStatus> healthCheck() { return Mono.empty(); }
            public Mono<List<ModelInfo>> listModels() { return Mono.just(List.of()); }
        };
    }

    private static ProviderRegistry registry(String... names) {
        LinkedHashMap<String, LLMProvider> providers = new LinkedHashMap<>();
        for (String n : names) {
            providers.put(n, provider(n));
        }
        return new ProviderRegistry(providers);
    }

    private static ProviderRegistry.ModelIndex index(Map<String, Set<String>> models) {
        return models::get;
    }

    private static List<String> candidates(ProviderRegistry.RoutePlan plan) {
        return plan.primary().candidates().stream().map(LLMProvider::name).toList();
    }

    @Test
    void registeredPrefixPinsAndKeepsTheRestAsModel() {
        ProviderRegistry.RoutePlan plan = registry("local-ollama", "groq").resolveRoute("groq/openai/gpt-oss-20b");

        assertThat(candidates(plan)).containsExactly("groq");
        assertThat(plan.primary().model()).isEqualTo("openai/gpt-oss-20b");
    }

    @Test
    void slashedModelIdWithUnregisteredPrefixIsABareName() {
        ProviderRegistry.RoutePlan plan = registry("local-ollama", "groq").resolveRoute("openai/gpt-oss-20b",
                index(Map.of("local-ollama", Set.of("qwen3:4b"), "groq", Set.of("openai/gpt-oss-20b"))));

        assertThat(candidates(plan)).containsExactly("groq");
        assertThat(plan.primary().model()).isEqualTo("openai/gpt-oss-20b");
    }

    @Test
    void bareNameGoesToEveryProviderServingIt() {
        ProviderRegistry.RoutePlan plan = registry("a", "b", "c").resolveRoute("qwen3:4b",
                index(Map.of("a", Set.of("llama3"), "b", Set.of("qwen3:4b"), "c", Set.of("qwen3:4b"))));

        assertThat(candidates(plan)).containsExactly("b", "c");
    }

    @Test
    void bareNameNobodyServesIs404WhenEveryProviderIsDiscovered() {
        ProviderRegistry registry = registry("a", "b");
        ProviderRegistry.ModelIndex idx = index(Map.of("a", Set.of("llama3"), "b", Set.of("qwen3:4b")));

        assertThatThrownBy(() -> registry.resolveRoute("mistral", idx)).isInstanceOf(UnknownModelException.class);
    }

    @Test
    void bareNameFallsBackToFirstProviderWhileDiscoveryIsIncomplete() {
        ProviderRegistry.RoutePlan plan = registry("a", "b").resolveRoute("mistral",
                index(Map.of("b", Set.of("qwen3:4b"))));

        assertThat(candidates(plan)).containsExactly("a");
    }
}
