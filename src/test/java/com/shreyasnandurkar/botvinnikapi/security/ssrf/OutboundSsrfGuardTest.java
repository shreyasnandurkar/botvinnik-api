package com.shreyasnandurkar.botvinnikapi.security.ssrf;

import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import com.shreyasnandurkar.botvinnikapi.config.OutboundHttpConfiguration;
import com.shreyasnandurkar.botvinnikapi.providers.gemini.GeminiProvider;
import com.shreyasnandurkar.botvinnikapi.providers.ollama.OllamaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real provider traffic, not the resolver in isolation — adapters must not swap the guarded connector out. */
class OutboundSsrfGuardTest {

    private static WebClient.Builder guardedBuilder(String... allowedCidrs) {
        SsrfPolicy policy = new SsrfPolicy(new GatewayProperties(null,
                new GatewayProperties.SecurityProps(false, null, List.of(allowedCidrs)), null, null));
        WebClient.Builder builder = WebClient.builder();
        new OutboundHttpConfiguration().ssrfGuardedConnector(policy).customize(builder);
        return builder;
    }

    // Port 1 is closed: an unguarded client fails with connection refused instead.
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:1", "http://127.0.0.1:1"})
    void ollamaTrafficIsGuarded(String baseUrl) {
        assertBlocked(new OllamaProvider("ollama", baseUrl, guardedBuilder(), new ObjectMapper()).listModels());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:1", "http://127.0.0.1:1"})
    void geminiTrafficIsGuarded(String baseUrl) {
        assertBlocked(new GeminiProvider("gemini", baseUrl, "key", guardedBuilder(), new ObjectMapper())
                .listModels());
    }

    @Test
    void allowlistedIpLiteralsStillConnect() {
        OllamaProvider lan = new OllamaProvider("lan", "http://127.0.0.1:1",
                guardedBuilder("127.0.0.0/8"), new ObjectMapper());
        assertThatThrownBy(() -> lan.listModels().block()).hasRootCauseInstanceOf(ConnectException.class);
    }

    private static void assertBlocked(Mono<?> call) {
        assertThatThrownBy(call::block).hasRootCauseInstanceOf(SsrfBlockedException.class);
    }
}
