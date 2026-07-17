package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import com.shreyasnandurkar.botvinnikapi.core.error.ProviderUnreachableException;
import com.shreyasnandurkar.botvinnikapi.core.error.StreamIdleTimeoutException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public CircuitBreakers() {
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(CircuitBreakers::isInfrastructureFailure)
                .build());
    }

    public CircuitBreaker of(String providerName) {
        return registry.circuitBreaker(providerName);
    }

    public boolean isOpen(String providerName) {
        CircuitBreaker.State state = of(providerName).getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /**
     * §8/§10: refusals and caller mistakes are not provider failures. Only
     * availability-class errors may trip the breaker.
     */
    static boolean isInfrastructureFailure(Throwable e) {
        if (e instanceof UpstreamException upstream) {
            return upstream.upstreamStatus() >= 500 || upstream.upstreamStatus() == 429;
        }
        if (e instanceof GatewayException) {
            return e instanceof ProviderUnreachableException || e instanceof StreamIdleTimeoutException;
        }
        // Transport-level surprises (connection reset mid-stream etc.) are real failures.
        return true;
    }
}
