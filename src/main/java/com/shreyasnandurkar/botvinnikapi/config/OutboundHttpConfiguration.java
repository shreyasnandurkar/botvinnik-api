package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.security.ssrf.GuardedResolverGroup;
import com.shreyasnandurkar.botvinnikapi.security.ssrf.SsrfPolicy;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

/**
 * Every outbound WebClient resolves through the SSRF guard, and redirects stay
 * off — a 302 to 169.254.169.254 would reopen the hole after every check (§11).
 */
@Configuration
public class OutboundHttpConfiguration {

    @Bean
    public WebClientCustomizer ssrfGuardedConnector(SsrfPolicy policy) {
        HttpClient httpClient = HttpClient.create()
                .resolver(new GuardedResolverGroup(policy))
                .followRedirect(false);
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
