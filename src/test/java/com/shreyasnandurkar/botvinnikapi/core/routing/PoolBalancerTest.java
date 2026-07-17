package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.LLMProvider;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.HealthStatus;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PoolBalancerTest {

    private ProviderStatsRegistry stats;
    private CircuitBreakers breakers;
    private PoolBalancer balancer;

    private final LLMProvider a = new FakeProvider("a");
    private final LLMProvider b = new FakeProvider("b");
    private final LLMProvider c = new FakeProvider("c");

    @BeforeEach
    void setUp() {
        stats = new ProviderStatsRegistry();
        breakers = new CircuitBreakers();
        balancer = new PoolBalancer(stats, breakers, new Random(42));
    }

    private ProviderRegistry.Attempt attempt(String strategy, LLMProvider... members) {
        return new ProviderRegistry.Attempt("pool", List.of(members), "m", strategy);
    }

    @Test
    void leastConnectionsPicksTheIdleNode() {
        stats.of("a").started();
        stats.of("a").started();
        stats.of("b").started();

        assertThat(balancer.pick(attempt("least_conn", a, b)).name()).isEqualTo("b");
    }

    @Test
    void roundRobinAlternatesStrictly() {
        ProviderRegistry.Attempt attempt = attempt("round_robin", a, b);
        List<String> picks = List.of(
                balancer.pick(attempt).name(), balancer.pick(attempt).name(),
                balancer.pick(attempt).name(), balancer.pick(attempt).name());

        assertThat(picks).containsExactly("a", "b", "a", "b");
    }

    @Test
    void p2cPicksTheLessLoadedOfTwoSamples() {
        // Load "a" heavily: whatever pair is sampled, "a" must lose to an idle node.
        for (int i = 0; i < 10; i++) {
            stats.of("a").started();
        }
        for (int i = 0; i < 50; i++) {
            assertThat(balancer.pick(attempt("p2c", a, b, c)).name()).isNotEqualTo("a");
        }
    }

    @Test
    void circuitOpenMembersGetNoTraffic() {
        breakers.of("a").transitionToOpenState();

        for (int i = 0; i < 20; i++) {
            assertThat(balancer.pick(attempt("p2c", a, b)).name()).isEqualTo("b");
        }
    }

    @Test
    void allCircuitsOpenStillHandsOutACandidateSoTheProbeCanHappen() {
        breakers.of("a").transitionToOpenState();
        breakers.of("b").transitionToOpenState();

        assertThat(balancer.pick(attempt("p2c", a, b))).isNotNull();
    }

    @Test
    void degradedMembersAreDeprioritized() {
        // Establish a ~100ms baseline for "a", then spike it: fast EWMA races
        // ahead of the slow baseline → DEGRADED (§9).
        for (int i = 0; i < 20; i++) {
            stats.of("a").recordTtft(100);
        }
        for (int i = 0; i < 5; i++) {
            stats.of("a").recordTtft(2000);
        }
        assertThat(stats.of("a").isDegraded()).isTrue();

        for (int i = 0; i < 20; i++) {
            assertThat(balancer.pick(attempt("p2c", a, b)).name()).isEqualTo("b");
        }
    }

    private record FakeProvider(String name) implements LLMProvider {

        @Override
        public Mono<ChatResponse> chat(ChatRequest request) {
            return Mono.empty();
        }

        @Override
        public Flux<ChatChunk> stream(ChatRequest request) {
            return Flux.empty();
        }

        @Override
        public Mono<HealthStatus> healthCheck() {
            return Mono.just(HealthStatus.healthy(0));
        }

        @Override
        public Mono<List<ModelInfo>> listModels() {
            return Mono.just(List.of());
        }
    }
}
