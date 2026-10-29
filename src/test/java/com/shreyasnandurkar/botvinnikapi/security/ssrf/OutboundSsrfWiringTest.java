package com.shreyasnandurkar.botvinnikapi.security.ssrf;

import com.shreyasnandurkar.botvinnikapi.TestcontainersConfiguration;
import com.shreyasnandurkar.botvinnikapi.core.ProviderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The guard must survive Boot's own connector auto-configuration, not just exist as a bean. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboundSsrfWiringTest {

    @Autowired
    ProviderFactory factory;

    @Test
    void factoryBuiltProvidersCannotReachTheMetadataEndpoint() {
        var provider = factory.create("probe", "ollama", "http://169.254.169.254", null, null);
        assertThatThrownBy(() -> provider.listModels().block())
                .hasRootCauseInstanceOf(SsrfBlockedException.class);
    }
}
