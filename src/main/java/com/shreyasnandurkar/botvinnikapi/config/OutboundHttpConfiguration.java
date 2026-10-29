package com.shreyasnandurkar.botvinnikapi.config;

import com.shreyasnandurkar.botvinnikapi.security.ssrf.GuardedResolverGroup;
import com.shreyasnandurkar.botvinnikapi.security.ssrf.SsrfPolicy;
import io.netty.channel.ChannelOption;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Every outbound WebClient resolves through the SSRF guard, and redirects stay
 * off — a 302 to 169.254.169.254 would reopen the hole after every check (§11).
 * This is the only connector: adapters that install their own bypass the guard.
 */
@Configuration
public class OutboundHttpConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    @Bean
    public WebClientCustomizer ssrfGuardedConnector(SsrfPolicy policy) {
        HttpClient httpClient = HttpClient.create()
                .resolver(new GuardedResolverGroup(policy))
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(RESPONSE_TIMEOUT);
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
