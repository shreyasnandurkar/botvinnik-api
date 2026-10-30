package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Only availability failures may open a breaker; capability gaps and caller mistakes may not. */
class CircuitBreakersTest {

    @Test
    void outagesAndThrottlingCount() {
        assertThat(CircuitBreakers.isInfrastructureFailure(new UpstreamException("p", 500, ""))).isTrue();
        assertThat(CircuitBreakers.isInfrastructureFailure(new UpstreamException("p", 503, ""))).isTrue();
        assertThat(CircuitBreakers.isInfrastructureFailure(new UpstreamException("p", 429, ""))).isTrue();
    }

    @Test
    void missingCapabilityAndBadRequestsDoNot() {
        assertThat(CircuitBreakers.isInfrastructureFailure(new UpstreamException("p", 501, ""))).isFalse();
        assertThat(CircuitBreakers.isInfrastructureFailure(new UpstreamException("p", 400, ""))).isFalse();
        assertThat(CircuitBreakers.isInfrastructureFailure(new InvalidRequestException("no", "model"))).isFalse();
    }
}
